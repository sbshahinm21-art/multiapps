import { HttpError, json, readJson, rateLimit, serveR2, str, int, oneOf, DEVICE_ID_RE } from './http.js';
import { decideUpdate, effectiveConfig, isTester, getSetting } from './data.js';
import { randomToken, safeEqual } from './security.js';

const EVENTS = ['download_started', 'download_completed', 'download_failed', 'install_success', 'install_failed', 'update_dismissed'];
const TICKET_CATEGORIES = ['bug', 'clone_problem', 'proxy_problem', 'account', 'feature_request', 'other'];

const limit = (ctx, name, n, windowMs = 60_000) => rateLimit(`pub:${name}:${ctx.ip}`, n, windowMs, ctx.cfg.rateLimitScale);
const deviceIdOf = (v) => str(v, 'deviceId', { min: 16, max: 64, pattern: DEVICE_ID_RE });
const versionCodeOf = (v) => int(v, 'versionCode', { min: 0, max: 2_100_000_000 });

const latestDownloadableStable = (ctx) => ctx.db.first(`SELECT * FROM releases WHERE status = 'live' AND channel = 'stable'
  AND rollout_percent >= 100 AND sha256 IS NOT NULL ORDER BY version_code DESC LIMIT 1`);

async function serveApk(ctx, r) {
  const out = await serveR2(ctx.request, ctx.env.BUCKET, r.file_key, {
    contentType: 'application/vnd.android.package-archive',
    downloadName: `MultiApps-${r.version_name}.apk`,
    extraHeaders: { 'X-Checksum-SHA256': r.sha256 },
  });
  // Count real downloads only: whole file, or the first chunk of a resumable download.
  if (ctx.request.method === 'GET' && (out.status === 200 || (out.status === 206 && out.start === 0))) {
    ctx.waitUntil(ctx.db.run('UPDATE releases SET download_count = download_count + 1 WHERE id = ?', r.id));
  }
  return out.response;
}

export function registerPublicRoutes(router) {
  router.get('/api/v1/health', () => json(200, { ok: true, time: Date.now() }));

  router.get('/api/v1/update/check', async (ctx) => {
    limit(ctx, 'check', 120);
    const q = ctx.query;
    const deviceId = deviceIdOf(q.get('deviceId'));
    const versionCode = versionCodeOf(q.get('versionCode'));
    const channel = q.get('channel') === 'beta' ? 'beta' : 'stable';
    const now = Date.now();
    const result = await decideUpdate(ctx, { deviceId, versionCode });
    if (result.release?.channel === 'beta') result.release.downloadUrl += `?deviceId=${encodeURIComponent(deviceId)}`;
    await ctx.db.batch([
      [`INSERT INTO devices (device_id, version_code, channel, model, sdk, first_seen, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(device_id) DO UPDATE SET version_code = excluded.version_code, channel = excluded.channel,
        model = COALESCE(excluded.model, devices.model), sdk = COALESCE(excluded.sdk, devices.sdk), last_seen = excluded.last_seen`,
        deviceId, versionCode, channel, (q.get('model') || '').slice(0, 80) || null, Number(q.get('sdk')) || null, now, now],
      ['INSERT INTO update_events (device_id, release_id, version_code, event, created_at) VALUES (?, ?, ?, ?, ?)',
        deviceId, result.release?.id ?? null, versionCode, 'check', now],
    ]);
    return json(200, result);
  });

  router.post('/api/v1/events', async (ctx) => {
    limit(ctx, 'events', 240);
    const b = await readJson(ctx.request, 16 * 1024);
    const deviceId = deviceIdOf(b.deviceId);
    const versionCode = versionCodeOf(b.versionCode);
    const event = oneOf(b.event, 'event', EVENTS);
    const releaseId = b.releaseId == null ? null : int(b.releaseId, 'releaseId', { min: 1 });
    if (releaseId && !(await ctx.db.first('SELECT id FROM releases WHERE id = ?', releaseId))) throw new HttpError(400, 'Unknown releaseId');
    const detail = str(b.detail, 'detail', { max: 500, required: false });
    await ctx.db.run('INSERT INTO update_events (device_id, release_id, version_code, event, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)',
      deviceId, releaseId, versionCode, event, detail || null, Date.now());
    return json(200, { ok: true });
  });

  router.get('/api/v1/config', async (ctx) => {
    limit(ctx, 'config', 120);
    const deviceId = ctx.query.get('deviceId') || '';
    let channel = ctx.query.get('channel') === 'beta' ? 'beta' : 'stable';
    if (channel === 'beta' && !(DEVICE_ID_RE.test(deviceId) && (await isTester(ctx, deviceId)))) channel = 'stable';
    const { config, version, etag } = await effectiveConfig(ctx, channel);
    if (ctx.request.headers.get('if-none-match') === etag) return new Response(null, { status: 304, headers: { ETag: etag } });
    return json(200, { version, channel, config }, { ETag: etag, 'Cache-Control': 'no-cache' });
  });

  router.post('/api/v1/beta/join', async (ctx) => {
    limit(ctx, 'beta', 10);
    const b = await readJson(ctx.request, 4096);
    const deviceId = deviceIdOf(b.deviceId);
    const code = (await getSetting(ctx, 'beta.join_code')) || '';
    if (!code) throw new HttpError(403, 'The beta program is not open right now');
    if (!safeEqual(String(b.code || '').trim(), code)) throw new HttpError(403, 'Beta code is not correct');
    await ctx.db.run(`INSERT OR IGNORE INTO beta_testers (device_id, label, source, created_at) VALUES (?, '', 'join_code', ?)`, deviceId, Date.now());
    return json(200, { ok: true, tester: true });
  });
  router.post('/api/v1/beta/leave', async (ctx) => {
    limit(ctx, 'beta', 10);
    const b = await readJson(ctx.request, 4096);
    await ctx.db.run('DELETE FROM beta_testers WHERE device_id = ?', deviceIdOf(b.deviceId));
    return json(200, { ok: true, tester: false });
  });

  // ---- Support tickets ----
  router.post('/api/v1/support/tickets', async (ctx) => {
    limit(ctx, 'ticket', 5, 10 * 60_000);
    const b = await readJson(ctx.request, 32 * 1024);
    const deviceId = deviceIdOf(b.deviceId);
    const email = str(b.email, 'email', { max: 200, required: false, pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/ });
    const category = oneOf(b.category, 'category', TICKET_CATEGORIES);
    const subject = str(b.subject, 'subject', { min: 3, max: 150 });
    const message = str(b.message, 'message', { min: 5, max: 5000 });
    const appVersion = str(b.appVersion, 'appVersion', { max: 40, required: false });
    const deviceInfo = str(b.deviceInfo, 'deviceInfo', { max: 2000, required: false });
    const token = randomToken(24);
    const now = Date.now();
    const row = await ctx.db.first(`INSERT INTO support_tickets (device_id, access_token, email, category, subject, app_version, device_info, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`, deviceId, token, email, category, subject, appVersion, deviceInfo, now, now);
    await ctx.db.run(`INSERT INTO support_messages (ticket_id, sender, body, created_at) VALUES (?, 'user', ?, ?)`, row.id, message, now);
    return json(201, { id: row.id, accessToken: token });
  });
  async function ticketFor(ctx) {
    const t = await ctx.db.first('SELECT * FROM support_tickets WHERE id = ?', Number(ctx.params.id) || 0);
    if (!t || !safeEqual(ctx.request.headers.get('x-ticket-token') || '', t.access_token)) throw new HttpError(404, 'Ticket not found');
    return t;
  }
  router.get('/api/v1/support/tickets/:id', async (ctx) => {
    limit(ctx, 'ticketread', 60);
    const t = await ticketFor(ctx);
    const messages = await ctx.db.all('SELECT sender, body, created_at AS createdAt FROM support_messages WHERE ticket_id = ? ORDER BY id', t.id);
    return json(200, { id: t.id, subject: t.subject, category: t.category, status: t.status, createdAt: t.created_at, updatedAt: t.updated_at, messages });
  });
  router.post('/api/v1/support/tickets/:id/messages', async (ctx) => {
    limit(ctx, 'ticketmsg', 20, 10 * 60_000);
    const t = await ticketFor(ctx);
    if (t.status === 'closed') throw new HttpError(400, 'This ticket is closed. Please open a new one.');
    const b = await readJson(ctx.request, 16 * 1024);
    const body = str(b.message, 'message', { min: 1, max: 5000 });
    const now = Date.now();
    await ctx.db.batch([
      [`INSERT INTO support_messages (ticket_id, sender, body, created_at) VALUES (?, 'user', ?, ?)`, t.id, body, now],
      [`UPDATE support_tickets SET status = 'open', updated_at = ? WHERE id = ?`, now, t.id],
    ]);
    return json(201, { ok: true });
  });

  // ---- Public website data ----
  router.get('/api/v1/public/site', async (ctx) => {
    limit(ctx, 'site', 120);
    const latest = await latestDownloadableStable(ctx);
    const settings = Object.fromEntries((await ctx.db.all(`SELECT key, value FROM settings WHERE key LIKE 'site.%'`)).map((r) => [r.key, r.value]));
    const shots = await ctx.db.all('SELECT file_name, caption FROM screenshots ORDER BY sort, id');
    let features = [];
    try { features = JSON.parse(settings['site.features'] || '[]'); } catch { /* keep empty */ }
    return json(200, {
      appName: settings['site.app_name'], tagline: settings['site.tagline'], description: settings['site.description'],
      supportEmail: settings['site.support_email'], features,
      screenshots: shots.map((s) => ({ url: `${ctx.cfg.publicUrl}/media/${s.file_name}`, caption: s.caption })),
      latest: latest ? {
        versionName: latest.version_name, versionCode: latest.version_code, fileSize: latest.file_size,
        sha256: latest.sha256, publishedAt: latest.published_at, downloadUrl: `${ctx.cfg.publicUrl}/download/latest`,
      } : null,
    }, { 'Cache-Control': 'public, max-age=60' });
  });
  router.get('/api/v1/public/releases', async (ctx) => {
    limit(ctx, 'releases', 120);
    const rows = await ctx.db.all(`SELECT version_name, version_code, changelog, published_at, file_size FROM releases
      WHERE channel = 'stable' AND status IN ('live','halted') AND published_at IS NOT NULL ORDER BY version_code DESC LIMIT 50`);
    return json(200, {
      releases: rows.map((r) => ({ versionName: r.version_name, versionCode: r.version_code, changelog: r.changelog, publishedAt: r.published_at, fileSize: r.file_size })),
    }, { 'Cache-Control': 'public, max-age=60' });
  });

  // ---- Downloads ----
  router.get('/download/latest', async (ctx) => {
    limit(ctx, 'dl', 30);
    const r = await latestDownloadableStable(ctx);
    if (!r) throw new HttpError(404, 'No release is available yet');
    return serveApk(ctx, r);
  });
  router.get('/download/release/:id', async (ctx) => {
    limit(ctx, 'dl', 30);
    const r = await ctx.db.first(`SELECT * FROM releases WHERE id = ? AND status = 'live' AND sha256 IS NOT NULL`, Number(ctx.params.id) || 0);
    if (!r) throw new HttpError(404, 'This release is not available');
    if (r.channel === 'beta') {
      const d = ctx.query.get('deviceId') || '';
      if (!DEVICE_ID_RE.test(d) || !(await isTester(ctx, d))) throw new HttpError(403, 'This build is for beta testers only');
    }
    return serveApk(ctx, r);
  });
  router.get('/media/:file', async (ctx) => {
    if (!/^[a-f0-9]{24}\.(png|jpg|webp)$/.test(ctx.params.file)) throw new HttpError(404, 'Not found');
    return (await serveR2(ctx.request, ctx.env.BUCKET, `media/${ctx.params.file}`, { cache: 'public, max-age=86400' })).response;
  });
}
