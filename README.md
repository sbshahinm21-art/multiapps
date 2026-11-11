# Multi Apps — Phase 1 (Cloudflare version)

```
multi-apps/
├─ docs/ARCHITECTURE.md      full system plan
├─ cloudflare/               backend API + admin dashboard (Cloudflare Workers + D1 + R2)
│  ├─ DEPLOY.md              step-by-step setup guide (Bangla)  ← start here
│  ├─ wrangler.toml          Cloudflare settings
│  ├─ migrations/            database tables
│  ├─ src/                   server code
│  ├─ assets/admin/          admin dashboard
│  └─ test/                  automated tests (run: npm test)
└─ public-site/              public download website (Cloudflare Pages)
```

**Tested:** 18 API tests and a full browser run (setup → 2FA → upload → publish → rollout → rollback →
settings → screenshots → public site on a separate origin → download) on Node.js with local stand-ins
for D1, R2 and static assets. **Not yet run on real Cloudflare** — the first real run is your deploy.

**Limits on the Cloudflare Free plan:** APK max 100 MB per upload; 100,000 requests per day.

**Not built yet:** the Android app (next phases). Its server API is ready:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/update/check?deviceId=&versionCode=&channel=&model=&sdk=` | Update decision |
| POST | `/api/v1/events` | Download/install funnel events |
| GET | `/api/v1/config?deviceId=&channel=` | Remote settings and ads (ETag) |
| POST | `/api/v1/beta/join`, `/api/v1/beta/leave` | Beta program |
| POST/GET | `/api/v1/support/tickets`, `/api/v1/support/tickets/:id` | Support |
| GET | `/download/latest`, `/download/release/:id` | APK downloads (resumable) |
