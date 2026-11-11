# Multi Apps — System Architecture

Three separate parts that share one backend API:

```
                ┌────────────────────────────┐
                │  Android app "Multi Apps"  │
                │  (Kotlin, Jetpack Compose) │
                └──────┬───────────────┬─────┘
     update check,     │               │  remote config (ads, flags),
     APK download,     │ HTTPS (JSON)  │  beta join, support tickets
     update events     ▼               ▼
┌──────────────────────────────────────────────────────────┐
│ Backend API  (Node.js 22, zero npm dependencies, SQLite) │
│  /api/v1/*      public + device endpoints                │
│  /download/*    APK delivery (Range/resume support)      │
│  /admin/api/*   admin-only (session + 2FA + CSRF)        │
│  storage: data/app.db, data/apks/, data/media/           │
└───────▲───────────────────────────────▲──────────────────┘
        │ same-origin cookies           │ CORS, read-only
┌───────┴─────────────────┐   ┌─────────┴──────────────────┐
│ Admin Web Dashboard     │   │ Public Download Website     │
│ served at /admin        │   │ static files (any host:     │
│ (put behind its own     │   │ same server, Cloudflare     │
│  subdomain if desired)  │   │ Pages, Netlify…)            │
└─────────────────────────┘   └────────────────────────────┘

Hosting: Cloudflare Workers (API + admin) with D1 (database) and R2 (APKs, screenshots); public website on Cloudflare Pages.
```

## 1. Backend API (Phase 1 — built and tested; Cloudflare Workers version)

| Area | Design |
|---|---|
| Runtime | Cloudflare Workers, Web Crypto only, no npm runtime dependencies. D1 for data, R2 for files. |
| Database | SQLite file (WAL mode). Tables: admins, sessions, releases, devices, beta_testers, update_events, config_versions, site_content, screenshots, support_tickets, audit_log. |
| Passwords | PBKDF2-SHA256 with per-user salt (30,000 iterations to fit the Workers Free CPU budget; set 100,000 on a paid plan). |
| 2FA | TOTP (RFC 6238, 30 s, 6 digits, SHA-1) — works with Google Authenticator, Authy, 1Password. Mandatory for every admin. 10 single-use recovery codes (stored hashed). |
| Sessions | Random 256-bit token in an HttpOnly, SameSite=Strict, Secure cookie; only the SHA-256 of the token is stored. Two stages: `password` (only 2FA endpoints allowed) → `full`. 12 h absolute expiry. |
| CSRF | Per-session token required in `X-CSRF-Token` on every state-changing admin request. |
| Brute force | Per-IP rate limits + account lockout (5 failed attempts → 15 min). |
| Headers | Strict CSP (no inline scripts), X-Frame-Options DENY, nosniff, Referrer-Policy, HSTS (when HTTPS). |
| Audit | Every admin action is written to `audit_log` with IP. |

### Release Center logic
- **Release** = APK file + versionCode + versionName + channel (`stable`/`beta`) + update type (`optional`/`mandatory`) + rollout % + min supported versionCode + changelog. SHA-256 computed on upload.
- **Lifecycle:** `draft → live → halted ⇄ live`, or `live/halted → rolled_back` (permanent).
- **Staged rollout:** each device gets a stable bucket 0–99.99 from `SHA-256(deviceId:releaseId)`. Raising the % only adds devices; nobody flips in and out.
- **Beta testing:** beta releases are offered only to devices in the tester list. Devices join by admin adding their Device ID, or by entering the beta join code in the app.
- **Mandatory updates:** forced if any newer eligible release is mandatory, or the device is below the target's min supported versionCode. The app blocks usage until updated.
- **Rollback — what is actually possible on Android:** Android refuses to install a lower versionCode over a higher one (without uninstalling, which wipes data). So "rollback" here means: the bad release is withdrawn instantly, no new device receives it, devices fall back to the previous live release as their update target, and devices already on the bad build get a `withdrawn` flag so the app can tell the user a fix is coming. To truly "roll back" users who already installed it, rebuild the old code with a **higher** versionCode and publish it. The dashboard says this in the UI.
- **Statistics:** devices report `check`, `download_started`, `download_completed`, `install_success`, `install_failed`. Dashboard shows per-release funnel, active-device version distribution (30 days), and daily checks.
- **Remote settings / ads:** versioned JSON documents per scope (`all`, `stable`, `beta`), merged as `all` ← channel. Full history with one-click revert. App caches with ETag.

## 2. Admin Web Dashboard (Phase 1 — built and tested)
Vanilla JS single-page app (no build step). Sections: Overview · Releases (upload, publish, rollout slider, halt/resume, rollback, edit) · Beta Testers · Statistics · Remote Settings (ads editor + raw JSON + history/revert) · Website Content (texts, screenshots) · Support Tickets · Audit Log · Account (password, 2FA recovery codes).

## 3. Public Download Website (Phase 1 — built and tested)
Static HTML/CSS/JS. Reads `/api/v1/public/site` and `/api/v1/public/releases`. Shows hero, features, screenshots, changelog, file size, SHA-256, install guide, and a button for `/download/latest` (latest **stable**, **100 %**-rolled-out, live release). Can be hosted anywhere; set `API_BASE` in `config.js`.

## 4. Android app (Phases 2–4)

Kotlin, Jetpack Compose (Material 3, dark premium theme), minSdk 26, targetSdk 35.

```
app/
 ├─ ui/           Compose screens: Home grid, Add clone, Clone settings, Proxy manager,
 │                Backup, Support, Settings/About, Update dialog
 ├─ data/         Room DB (clones, proxies, settings), DataStore
 ├─ engine/       Clone engine interface  ← see 4.1
 ├─ vpn/          MultiAppsVpnService + userspace TCP/UDP stack → SOCKS5/HTTP proxy
 ├─ update/       Update checker (WorkManager), downloader w/ SHA-256 verify, PackageInstaller
 ├─ backup/       AES-256-GCM encrypted backup (key from password via PBKDF2)
 ├─ resource/     Smart resource manager
 └─ remote/       Remote config + ads gate
```

### 4.1 The clone engine — honest feasibility

This is the hardest part of the whole system, and you should know where the limits are before we build it.

Android has **no public API** for running several copies of one app. There are two real ways:

| Approach | Clones per app | Reliability | Notes |
|---|---|---|---|
| **A. Managed (work) profile** via `DevicePolicyManager` | **1** extra copy | Very high — it's an official Android feature | Fully separate data. Facebook works normally. Only one profile per device for third-party apps. |
| **B. App virtualization engine** (how Parallel Space / Dual Space work): host app loads the target APK into its own process and hooks system services | Unlimited | Medium; breaks on some Android versions and some apps; needs ongoing maintenance | Requires an engine. Writing one from scratch is months of work. Open-source bases exist (e.g. BlackBox, Apache-2.0); the original VirtualApp is GPL-3 (would force you to open-source your app) and its newer versions are commercial. Facebook actively detects some virtual environments. |

**Plan:** the app defines a `CloneEngine` interface with two implementations: `WorkProfileEngine` (fully working, built by us) and `VirtualEngine` (integrates an open-source virtualization library). The UI works with either. I will test what I can, and report exactly which apps/Android versions were verified on a real device — I cannot run Android in this workspace, so device testing has to happen on your phone with builds you install.

### 4.2 Per-app and per-clone proxy (VpnService)
- `VpnService` captures traffic; routing is decided per **app package** with `addAllowedApplication`. A userspace TCP/IP stack (tun2socks style) forwards captured traffic to the chosen SOCKS5/HTTP proxy, with optional username/password, and DNS through the proxy to avoid leaks.
- **Limit:** Android identifies traffic by app UID. Work-profile clones have their own UID, so per-clone proxy works there. Virtual-engine clones all run under the Multi Apps UID, so VpnService alone **cannot** tell clone #2 from clone #3. Per-clone proxy for virtual clones requires the engine to redirect each clone process's sockets to a per-clone local port (e.g. clone 3 → 127.0.0.1:10803), which the proxy layer maps to that clone's upstream proxy. This is planned and will be marked working only after it is verified.
- When opening a clone the user chooses **No proxy** or **its own proxy**, as you asked.
- Only one VPN can be active on a device at a time (Android rule).

### 4.3 Other app features
- **Smart resource management:** stop background clones after an idle timeout, cap concurrently running clones, optional "battery saver" mode, per-clone memory/run status on the home screen.
- **Per-clone customization:** custom name, icon tint/badge, home-screen shortcut, lock with PIN/biometric, proxy choice.
- **Notification controls:** per-clone on/off, mute, badge counts, notification grouping by clone name.
- **Encrypted backup:** clone list, settings, proxies → AES-256-GCM file, key derived from user password (PBKDF2-SHA256, 310k iterations). Restore with the same password.
- **Support system:** FAQ screen, in-app ticket form → `/api/v1/support` (admin answers in dashboard), app shows replies; diagnostic info attached only with consent.
- **Updates:** checks `/api/v1/update/check`, verifies SHA-256 after download, installs with `PackageInstaller`; mandatory updates block the app.
- **Ads:** controlled entirely by remote settings (enabled, provider, unit IDs, placements, frequency caps).

## 5. Phases
1. **Backend + Admin Dashboard + Public Website** ← done in this delivery, tested.
2. Android app shell: Compose UI, Room DB, update system, remote config, support, backup, Device ID / beta join.
3. Clone engine: Work-profile engine, then virtual-engine integration.
4. VpnService proxy routing (per-app, then per-clone), resource manager, notification controls.
5. Hardening, Play Protect / signing guide, release checklist.

## 6. Policy notes
- Facebook's terms allow one personal account per person; running many accounts can get them checkpointed or banned regardless of the app. Proxies do not change that.
- Apps distributed outside Google Play need users to allow "Install unknown apps". Keep your signing key safe: every update must be signed with the same key or Android will refuse it.
