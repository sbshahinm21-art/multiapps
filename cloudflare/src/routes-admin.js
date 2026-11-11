import { HttpError, json, readJson, str, int, num, oneOf, DEVICE_ID_RE } from './http.js';
import {
  getSession, requireAdmin, login, startTotpSetup, verifyTotpLogin, useRecoveryCode, logout,
  changePassword, regenerateRecoveryCodes, needsSetup, setupFirstAdmin,
} from './auth.js';
import { audit, getSetting, setSettingStmt, DEFAULT_REMOTE_CONFIG, effectiveConfig, latestConfig } from './data.js';
import { randomBytes, toHex } from './security.js';

const DAY = 86_400_000;
const VERSION_NAME_RE = /^[0-9A-Za-z.\-+_ ]+$/;

function releaseDto(r) {
  return {
    id: r.id, versionCode: r.version_code, versionName: r.version_name, channel: r.channel, status: r.status,
    updateType: r.update_type, rolloutPercent: r.rollout_percent, minSupportedVersionCode: r.min_supported_version_code,
    changelog: r.changelog, fileSize: r.file_size, sha256: r.sha256, hasFile: !!r.sha256, downloadCount: r.download_count,
    createdAt: r.created_at, publishedAt: r.published_at, statusChangedAt: r.status_changed_at,
  };
}
async function getRelease(ctx, id) {
  const r = await ctx.db.first('SELECT * FROM releases WHERE id = ?', Number(id) || 0);
  if (!r) throw new HttpError(404, 'Release not found');
  return r;
}
async function setStatus(ctx, admin, r, from, to, action) {
  if (!from.includes(r.status)) throw new HttpError(409, `Cannot ${action} a release that is ${r.status.replace('_', ' ')}`);
  const now = Date.now();
  // Guarded update so two admins clicking at once cannot both succeed.
  const res = await ctx.db.run(`UPDATE releases SET status = ?, status_changed_at = ?,
    published_at = COALESCE(published_at, CASE WHEN ? = 'live' THEN ? END) WHERE id = ? AND status = ?`, to, now, to, now, r.id, r.status);
  if (!res.meta.changes) throw new HttpError(409, 'The release changed in the meantime. Reload and try again.');
  await audit(ctx, admin, `release.${action}`, { id: r.id, versionCode: r.version_code });
  return releaseDto(await getRelease(ctx, r.id));
}
function validateConfigObject(v) {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new HttpError(400, 'Settings must be a JSON object');
  const s = JSON.stringify(v);
  if (s.length > 64 * 1024) throw new HttpError(400, 'Settings are too large (max 64 KB)');
  const ads = v.ads;
  if (ads !== undefined) {
    if (typeof ads !== 'object' || Array.isArray(ads) || ads === null) throw new HttpError(400, 'ads must be an object');
    if (ads.enabled !== undefined && typeof ads.enabled !== 'boolean') throw new HttpError(400, 'ads.enabled must be true or false');
    for (const [k, val] of Object.entries(ads.frequency || {})) {
      if (!Number.isInteger(val) || val < 0 || val > 100000) throw new HttpError(400, `ads.frequency.${k} must be a whole number ≥ 0`);
    }
  }
  return s;
}
function imageType(b) {
  if (b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return ['png', 'image/png'];
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return ['jpg', 'image/jpeg'];
  const ascii = (s, e) => String.fromCharCode(...b.slice(s, e));
  if (b.length >= 12 && ascii(0, 4) === 'RIFF' && ascii(8, 12) === 'WEBP') return ['webp', 'image/webp'];
  return null;
}
const body = (ctx, limit) => readJson(ctx.request, limit);

export function registerAdminRoutes(router) {
  // ---------- Authentication ----------
  router.get('/admin/api/session', async (ctx) => {
    const s = await getSession(ctx);
    if (!s) return json(200, { stage: 'none', needsSetup: await needsSetup(ctx) });
    return json(200, { stage: s.stage, username: s.username, totpEnabled: !!s.totp_enabled, csrf: s.csrf });
  });
  router.post('/admin/api/setup', async (ctx) => json(200, await setupFirstAdmin(ctx, await body(ctx, 4096))));
  router.post('/admin/api/login', async (ctx) => json(200, await login(ctx, await body(ctx, 4096))));
  router.post('/admin/api/2fa/setup', async (ctx) => json(200, await startTotpSetup(ctx)));
  router.post('/admin/api/2fa/verify', async (ctx) => json(200, await verifyTotpLogin(ctx, await body(ctx, 4096))));
  router.post('/admin/api/2fa/recovery', async (ctx) => json(200, await useRecoveryCode(ctx, await body(ctx, 4096))));
  router.post('/admin/api/logout', async (ctx) => { await logout(ctx); return json(200, { ok: true }); });

  router.post('/admin/api/account/password', async (ctx) => {
    const admin = await requireAdmin(ctx);
    await changePassword(ctx, admin, await body(ctx, 4096));
    return json(200, { ok: true });
  });
  router.post('/admin/api/account/recovery-codes', async (ctx) => {
    const admin = await requireAdmin(ctx);
    return json(200, { recoveryCodes: await regenerateRecoveryCodes(ctx, admin, await body(ctx, 4096)) });
  });
  router.get('/admin/api/account', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const a = await ctx.db.first('SELECT username, created_at, last_login_at, recovery_codes FROM admins WHERE id = ?', admin.id);
    const sessions = await ctx.db.all(`SELECT ip, ua, created_at, expires_at, (id = ?) AS current FROM sessions
      WHERE admin_id = ? AND stage = 'full' AND expires_at > ? ORDER BY created_at DESC`, admin.session.id, admin.id, Date.now());
    return json(200, { username: a.username, createdAt: a.created_at, lastLoginAt: a.last_login_at, recoveryCodesLeft: JSON.parse(a.recovery_codes).length, sessions });
  });

  // ---------- Releases ----------
  router.get('/admin/api/releases', async (ctx) => {
    await requireAdmin(ctx);
    return json(200, { releases: (await ctx.db.all('SELECT * FROM releases ORDER BY version_code DESC')).map(releaseDto) });
  });

  router.post('/admin/api/releases', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const b = await body(ctx, 64 * 1024);
    const versionCode = int(b.versionCode, 'Version code', { min: 1, max: 2_100_000_000 });
    const versionName = str(b.versionName, 'Version name', { min: 1, max: 40, pattern: VERSION_NAME_RE });
    const channel = oneOf(b.channel || 'stable', 'Channel', ['stable', 'beta']);
    const updateType = oneOf(b.updateType || 'optional', 'Update type', ['optional', 'mandatory']);
    const rolloutPercent = num(b.rolloutPercent ?? 100, 'Rollout', { min: 0, max: 100 });
    const minSupported = int(b.minSupportedVersionCode ?? 0, 'Min supported version code', { min: 0, max: versionCode });
    const changelog = str(b.changelog, 'Changelog', { max: 10000, required: false });
    const max = (await ctx.db.first('SELECT MAX(version_code) AS m FROM releases')).m || 0;
    if (versionCode <= max) throw new HttpError(409, `Version code must be higher than ${max} (the highest existing release). Android cannot install a lower version code over a higher one.`);
    const row = await ctx.db.first(`INSERT INTO releases (version_code, version_name, channel, update_type, rollout_percent, min_supported_version_code, changelog, created_at, created_by)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`, versionCode, versionName, channel, updateType, rolloutPercent, minSupported, changelog, Date.now(), admin.id);
    await audit(ctx, admin, 'release.created', { id: row.id, versionCode, versionName, channel });
    return json(201, { release: releaseDto(await getRelease(ctx, row.id)) });
  });

  // APK upload. The browser sends the file's SHA-256 in X-Content-SHA256; R2 rejects the upload if the bytes do not match.
  router.put('/admin/api/releases/:id/file', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const r = await getRelease(ctx, ctx.params.id);
    if (r.status !== 'draft') throw new HttpError(409, 'The APK can only be replaced while the release is a draft');
    const size = Number(ctx.request.headers.get('content-length') || 0);
    if (!size) throw new HttpError(411, 'Upload size is missing');
    if (size < 1024) throw new HttpError(400, 'This file is too small to be an APK');
    if (size > ctx.cfg.maxApkBytes) throw new HttpError(413, `APK is larger than the limit (${Math.round(ctx.cfg.maxApkBytes / 1048576)} MB)`);
    const sha = String(ctx.request.headers.get('x-content-sha256') || '').toLowerCase();
    if (!/^[a-f0-9]{64}$/.test(sha)) throw new HttpError(400, 'Missing file checksum. Reload the dashboard and try again.');
    const dup = await ctx.db.first('SELECT version_code FROM releases WHERE sha256 = ? AND id != ?', sha, r.id);
    if (dup) throw new HttpError(409, `This exact APK is already used by version code ${dup.version_code}.`);
    const key = `apks/${r.id}-${sha.slice(0, 12)}.apk`;
    let obj;
    try {
      obj = await ctx.env.BUCKET.put(key, ctx.request.body, { httpMetadata: { contentType: 'application/vnd.android.package-archive' }, sha256: sha });
    } catch (e) {
      throw new HttpError(400, `Upload failed or the file was corrupted on the way (${String(e.message || e).slice(0, 120)}). Please try again.`);
    }
    const head = await ctx.env.BUCKET.get(key, { range: { offset: 0, length: 4 } });
    const magic = head ? new Uint8Array(await head.arrayBuffer()) : new Uint8Array();
    if (!(magic[0] === 0x50 && magic[1] === 0x4b && magic[2] === 0x03 && magic[3] === 0x04)) {
      await ctx.env.BUCKET.delete(key);
      throw new HttpError(400, 'This file is not a valid APK (APK files are ZIP archives).');
    }
    if (r.file_key && r.file_key !== key) await ctx.env.BUCKET.delete(r.file_key);
    await ctx.db.run('UPDATE releases SET file_key = ?, file_size = ?, sha256 = ? WHERE id = ?', key, obj.size, sha, r.id);
    await audit(ctx, admin, 'release.apk_uploaded', { id: r.id, size: obj.size, sha256: sha });
    return json(200, { release: releaseDto(await getRelease(ctx, r.id)) });
  });

  router.patch('/admin/api/releases/:id', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const r = await getRelease(ctx, ctx.params.id);
    if (r.status === 'rolled_back') throw new HttpError(409, 'A rolled back release cannot be edited');
    const b = await body(ctx, 64 * 1024);
    const sets = {};
    if (b.changelog !== undefined) sets.changelog = str(b.changelog, 'Changelog', { max: 10000, required: false });
    if (b.updateType !== undefined) sets.update_type = oneOf(b.updateType, 'Update type', ['optional', 'mandatory']);
    if (b.rolloutPercent !== undefined) sets.rollout_percent = num(b.rolloutPercent, 'Rollout', { min: 0, max: 100 });
    if (b.minSupportedVersionCode !== undefined) sets.min_supported_version_code = int(b.minSupportedVersionCode, 'Min supported version code', { min: 0, max: r.version_code });
    if (b.versionName !== undefined) {
      if (r.status !== 'draft') throw new HttpError(409, 'Version name can only change while the release is a draft');
      sets.version_name = str(b.versionName, 'Version name', { min: 1, max: 40, pattern: VERSION_NAME_RE });
    }
    if (b.channel !== undefined) {
      const ch = oneOf(b.channel, 'Channel', ['stable', 'beta']);
      if (ch !== r.channel && !(r.status === 'draft' || (r.channel === 'beta' && ch === 'stable'))) {
        throw new HttpError(409, 'A published release can only be promoted from beta to stable, not moved back');
      }
      sets.channel = ch;
    }
    const keys = Object.keys(sets);
    if (!keys.length) throw new HttpError(400, 'Nothing to change');
    // Column names come only from the fixed list above, never from user input.
    await ctx.db.run(`UPDATE releases SET ${keys.map((k) => `${k} = ?`).join(', ')} WHERE id = ?`, ...keys.map((k) => sets[k]), r.id);
    await audit(ctx, admin, 'release.updated', { id: r.id, versionCode: r.version_code, changes: sets });
    return json(200, { release: releaseDto(await getRelease(ctx, r.id)) });
  });

  router.post('/admin/api/releases/:id/publish', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const r = await getRelease(ctx, ctx.params.id);
    if (!r.sha256) throw new HttpError(409, 'Upload the APK file before publishing');
    return json(200, { release: await setStatus(ctx, admin, r, ['draft'], 'live', 'publish') });
  });
  router.post('/admin/api/releases/:id/halt', async (ctx) => {
    const admin = await requireAdmin(ctx);
    return json(200, { release: await setStatus(ctx, admin, await getRelease(ctx, ctx.params.id), ['live'], 'halted', 'halt') });
  });
  router.post('/admin/api/releases/:id/resume', async (ctx) => {
    const admin = await requireAdmin(ctx);
    return json(200, { release: await setStatus(ctx, admin, await getRelease(ctx, ctx.params.id), ['halted'], 'live', 'resume') });
  });
  router.post('/admin/api/releases/:id/rollback', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const b = await body(ctx, 4096);
    const r = await getRelease(ctx, ctx.params.id);
    if (b.confirmVersionCode !== r.version_code) throw new HttpError(400, 'Type the version code to confirm the rollback');
    const release = await setStatus(ctx, admin, r, ['live', 'halted'], 'rolled_back', 'rollback');
    let fallback = await ctx.db.first(`SELECT version_code, version_name FROM releases WHERE status = 'live' AND channel = ? AND version_code < ?
      ORDER BY version_code DESC LIMIT 1`, r.channel, r.version_code);
    if (!fallback && r.channel === 'beta') {
      fallback = await ctx.db.first(`SELECT version_code, version_name FROM releases WHERE status = 'live' AND channel = 'stable' AND version_code < ?
        ORDER BY version_code DESC LIMIT 1`, r.version_code);
    }
    const devicesOnIt = (await ctx.db.first('SELECT COUNT(*) AS n FROM devices WHERE version_code = ? AND last_seen > ?', r.version_code, Date.now() - 30 * DAY)).n;
    return json(200, { release, fallback: fallback ? { versionCode: fallback.version_code, versionName: fallback.version_name } : null, devicesAlreadyOnThisVersion: devicesOnIt });
  });
  router.delete('/admin/api/releases/:id', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const r = await getRelease(ctx, ctx.params.id);
    if (r.status !== 'draft') throw new HttpError(409, 'Only drafts can be deleted. Use halt or rollback for published releases.');
    if (r.file_key) await ctx.env.BUCKET.delete(r.file_key);
    await ctx.db.run('DELETE FROM releases WHERE id = ?', r.id);
    await audit(ctx, admin, 'release.deleted', { id: r.id, versionCode: r.version_code });
    return json(200, { ok: true });
  });

  // ---------- Beta testers ----------
  router.get('/admin/api/testers', async (ctx) => {
    await requireAdmin(ctx);
    const testers = await ctx.db.all(`SELECT t.device_id AS deviceId, t.label, t.source, t.created_at AS createdAt,
      d.version_code AS versionCode, d.model, d.last_seen AS lastSeen FROM beta_testers t LEFT JOIN devices d ON d.device_id = t.device_id ORDER BY t.created_at DESC`);
    return json(200, { testers, joinCode: (await getSetting(ctx, 'beta.join_code')) || '' });
  });
  router.post('/admin/api/testers', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const b = await body(ctx, 4096);
    const deviceId = str(b.deviceId, 'Device ID', { min: 16, max: 64, pattern: DEVICE_ID_RE });
    const label = str(b.label, 'Label', { max: 80, required: false });
    await ctx.db.run(`INSERT INTO beta_testers (device_id, label, source, created_at) VALUES (?, ?, 'admin', ?)
      ON CONFLICT(device_id) DO UPDATE SET label = excluded.label`, deviceId, label, Date.now());
    await audit(ctx, admin, 'tester.added', { deviceId, label });
    return json(201, { ok: true });
  });
  router.delete('/admin/api/testers/:deviceId', async (ctx) => {
    const admin = await requireAdmin(ctx);
    await ctx.db.run('DELETE FROM beta_testers WHERE device_id = ?', ctx.params.deviceId);
    await audit(ctx, admin, 'tester.removed', { deviceId: ctx.params.deviceId });
    return json(200, { ok: true });
  });
  router.put('/admin/api/testers/join-code', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const b = await body(ctx, 4096);
    const code = str(b.code, 'Join code', { max: 40, required: false, pattern: /^[A-Za-z0-9-]{6,40}$/ });
    await ctx.db.run(...setSettingStmt('beta.join_code', code));
    await audit(ctx, admin, 'tester.join_code_changed', { enabled: !!code });
    return json(200, { ok: true, joinCode: code });
  });

  // ---------- Statistics ----------
  router.get('/admin/api/stats', async (ctx) => {
    await requireAdmin(ctx);
    const now = Date.now();
    const count = async (sql, ...a) => (await ctx.db.first(sql, ...a)).n;
    const totals = {
      devices: await count('SELECT COUNT(*) AS n FROM devices'),
      active1d: await count('SELECT COUNT(*) AS n FROM devices WHERE last_seen > ?', now - DAY),
      active7d: await count('SELECT COUNT(*) AS n FROM devices WHERE last_seen > ?', now - 7 * DAY),
      active30d: await count('SELECT COUNT(*) AS n FROM devices WHERE last_seen > ?', now - 30 * DAY),
      testers: await count('SELECT COUNT(*) AS n FROM beta_testers'),
      openTickets: await count(`SELECT COUNT(*) AS n FROM support_tickets WHERE status = 'open'`),
    };
    const versionDistribution = await ctx.db.all(`SELECT d.version_code AS versionCode, r.version_name AS versionName, COUNT(*) AS devices
      FROM devices d LEFT JOIN releases r ON r.version_code = d.version_code WHERE d.last_seen > ? GROUP BY d.version_code ORDER BY d.version_code DESC`, now - 30 * DAY);
    const funnel = {};
    for (const f of await ctx.db.all(`SELECT release_id, event, COUNT(DISTINCT device_id) AS devices FROM update_events
      WHERE release_id IS NOT NULL GROUP BY release_id, event`)) {
      (funnel[f.release_id] ??= {})[f.event] = f.devices;
    }
    const activeByVersion = Object.fromEntries(versionDistribution.map((v) => [v.versionCode, v.devices]));
    const releases = (await ctx.db.all(`SELECT * FROM releases WHERE status != 'draft' ORDER BY version_code DESC`)).map((r) => {
      const d = (e) => funnel[r.id]?.[e] || 0;
      return {
        id: r.id, versionCode: r.version_code, versionName: r.version_name, channel: r.channel, status: r.status, rolloutPercent: r.rollout_percent,
        offeredDevices: d('check'), downloadStarted: d('download_started'), downloadCompleted: d('download_completed'), downloadFailed: d('download_failed'),
        installSuccess: d('install_success'), installFailed: d('install_failed'), dismissed: d('update_dismissed'),
        fileDownloads: r.download_count, activeDevicesOnVersion: activeByVersion[r.version_code] || 0,
      };
    });
    const start = new Date(); start.setUTCHours(0, 0, 0, 0);
    const from = start.getTime() - 13 * DAY;
    const daily = Array.from({ length: 14 }, (_, i) => ({ date: new Date(from + i * DAY).toISOString().slice(0, 10), checks: 0, installs: 0 }));
    for (const r of await ctx.db.all(`SELECT (created_at - ?) / ? AS day, event, COUNT(*) AS n FROM update_events
      WHERE created_at >= ? AND event IN ('check','install_success') GROUP BY day, event`, from, DAY, from)) {
      const i = Math.floor(r.day);
      if (daily[i]) daily[i][r.event === 'check' ? 'checks' : 'installs'] = r.n;
    }
    return json(200, { totals, versionDistribution, releases, daily });
  });

  // ---------- Remote settings ----------
  router.get('/admin/api/config', async (ctx) => {
    await requireAdmin(ctx);
    const scopes = {};
    for (const s of ['all', 'stable', 'beta']) {
      const row = await latestConfig(ctx, s);
      scopes[s] = row ? { id: row.id, config: JSON.parse(row.json), note: row.note, createdAt: row.created_at } : { id: null, config: {}, note: '', createdAt: null };
    }
    return json(200, { defaults: DEFAULT_REMOTE_CONFIG, scopes, effective: { stable: (await effectiveConfig(ctx, 'stable')).config, beta: (await effectiveConfig(ctx, 'beta')).config } });
  });
  router.put('/admin/api/config/:scope', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const scope = oneOf(ctx.params.scope, 'scope', ['all', 'stable', 'beta']);
    const b = await body(ctx, 80 * 1024);
    const jsonText = validateConfigObject(b.config);
    const note = str(b.note, 'Note', { max: 200, required: false });
    const row = await ctx.db.first('INSERT INTO config_versions (scope, json, note, created_at, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id', scope, jsonText, note, Date.now(), admin.id);
    await audit(ctx, admin, 'config.updated', { scope, versionId: row.id, note });
    return json(200, { ok: true, id: row.id });
  });
  router.get('/admin/api/config/:scope/history', async (ctx) => {
    await requireAdmin(ctx);
    const scope = oneOf(ctx.params.scope, 'scope', ['all', 'stable', 'beta']);
    const rows = await ctx.db.all(`SELECT c.id, c.json, c.note, c.created_at AS createdAt, a.username FROM config_versions c LEFT JOIN admins a ON a.id = c.created_by
      WHERE scope = ? ORDER BY c.id DESC LIMIT 50`, scope);
    return json(200, { history: rows.map(({ json: j, ...r }) => ({ ...r, config: JSON.parse(j) })) });
  });
  router.post('/admin/api/config/:scope/revert/:id', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const scope = oneOf(ctx.params.scope, 'scope', ['all', 'stable', 'beta']);
    const old = await ctx.db.first('SELECT * FROM config_versions WHERE id = ? AND scope = ?', Number(ctx.params.id) || 0, scope);
    if (!old) throw new HttpError(404, 'Version not found');
    const row = await ctx.db.first('INSERT INTO config_versions (scope, json, note, created_at, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id',
      scope, old.json, `Reverted to version #${old.id}`, Date.now(), admin.id);
    await audit(ctx, admin, 'config.reverted', { scope, to: old.id });
    return json(200, { ok: true, id: row.id });
  });

  // ---------- Website content ----------
  router.get('/admin/api/site', async (ctx) => {
    await requireAdmin(ctx);
    const s = Object.fromEntries((await ctx.db.all(`SELECT key, value FROM settings WHERE key LIKE 'site.%'`)).map((r) => [r.key, r.value]));
    const shots = await ctx.db.all('SELECT id, file_name AS fileName, caption, sort FROM screenshots ORDER BY sort, id');
    return json(200, {
      appName: s['site.app_name'], tagline: s['site.tagline'], description: s['site.description'], supportEmail: s['site.support_email'],
      features: JSON.parse(s['site.features'] || '[]'), screenshots: shots.map((x) => ({ ...x, url: `/media/${x.fileName}` })),
    });
  });
  router.put('/admin/api/site', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const b = await body(ctx, 64 * 1024);
    const features = Array.isArray(b.features) ? b.features : [];
    if (features.length > 12) throw new HttpError(400, 'At most 12 features');
    const clean = features.map((f, i) => ({
      title: str(f?.title, `Feature ${i + 1} title`, { min: 1, max: 60 }),
      text: str(f?.text, `Feature ${i + 1} text`, { max: 240, required: false }),
    }));
    await ctx.db.batch([
      setSettingStmt('site.app_name', str(b.appName, 'App name', { min: 1, max: 60 })),
      setSettingStmt('site.tagline', str(b.tagline, 'Tagline', { max: 160, required: false })),
      setSettingStmt('site.description', str(b.description, 'Description', { max: 3000, required: false })),
      setSettingStmt('site.support_email', str(b.supportEmail, 'Support email', { max: 200, required: false, pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/ })),
      setSettingStmt('site.features', JSON.stringify(clean)),
    ]);
    await audit(ctx, admin, 'site.updated');
    return json(200, { ok: true });
  });
  router.post('/admin/api/screenshots', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const count = (await ctx.db.first('SELECT COUNT(*) AS n FROM screenshots')).n;
    if (count >= 20) throw new HttpError(400, 'At most 20 screenshots');
    if (Number(ctx.request.headers.get('content-length') || 0) > 8 * 1048576) throw new HttpError(413, 'Image is larger than 8 MB');
    const bytes = new Uint8Array(await ctx.request.arrayBuffer());
    if (bytes.length > 8 * 1048576) throw new HttpError(413, 'Image is larger than 8 MB');
    const t = imageType(bytes);
    if (!t) throw new HttpError(400, 'Only PNG, JPG or WebP images are allowed');
    const caption = str(ctx.query.get('caption') || '', 'Caption', { max: 120, required: false });
    const fileName = `${toHex(randomBytes(12))}.${t[0]}`;
    await ctx.env.BUCKET.put(`media/${fileName}`, bytes, { httpMetadata: { contentType: t[1] } });
    await ctx.db.run('INSERT INTO screenshots (file_name, mime, caption, sort, created_at) VALUES (?, ?, ?, ?, ?)', fileName, t[1], caption, count, Date.now());
    await audit(ctx, admin, 'site.screenshot_added', { fileName });
    return json(201, { ok: true });
  });
  router.patch('/admin/api/screenshots/:id', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const b = await body(ctx, 4096);
    const s = await ctx.db.first('SELECT * FROM screenshots WHERE id = ?', Number(ctx.params.id) || 0);
    if (!s) throw new HttpError(404, 'Screenshot not found');
    const caption = b.caption !== undefined ? str(b.caption, 'Caption', { max: 120, required: false }) : s.caption;
    const sort = b.sort !== undefined ? int(b.sort, 'Order', { min: 0, max: 1000 }) : s.sort;
    await ctx.db.run('UPDATE screenshots SET caption = ?, sort = ? WHERE id = ?', caption, sort, s.id);
    await audit(ctx, admin, 'site.screenshot_updated', { id: s.id });
    return json(200, { ok: true });
  });
  router.delete('/admin/api/screenshots/:id', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const s = await ctx.db.first('SELECT * FROM screenshots WHERE id = ?', Number(ctx.params.id) || 0);
    if (!s) throw new HttpError(404, 'Screenshot not found');
    await ctx.env.BUCKET.delete(`media/${s.file_name}`);
    await ctx.db.run('DELETE FROM screenshots WHERE id = ?', s.id);
    await audit(ctx, admin, 'site.screenshot_deleted', { id: s.id });
    return json(200, { ok: true });
  });

  // ---------- Support tickets ----------
  const ticketDto = (t) => ({ id: t.id, deviceId: t.device_id, email: t.email, category: t.category, subject: t.subject, status: t.status, appVersion: t.app_version, createdAt: t.created_at, updatedAt: t.updated_at });
  router.get('/admin/api/tickets', async (ctx) => {
    await requireAdmin(ctx);
    const status = ctx.query.get('status');
    const rows = ['open', 'answered', 'closed'].includes(status)
      ? await ctx.db.all('SELECT * FROM support_tickets WHERE status = ? ORDER BY updated_at DESC LIMIT 200', status)
      : await ctx.db.all('SELECT * FROM support_tickets ORDER BY updated_at DESC LIMIT 200');
    return json(200, { tickets: rows.map(ticketDto) });
  });
  router.get('/admin/api/tickets/:id', async (ctx) => {
    await requireAdmin(ctx);
    const t = await ctx.db.first('SELECT * FROM support_tickets WHERE id = ?', Number(ctx.params.id) || 0);
    if (!t) throw new HttpError(404, 'Ticket not found');
    const messages = await ctx.db.all('SELECT sender, body, created_at AS createdAt FROM support_messages WHERE ticket_id = ? ORDER BY id', t.id);
    return json(200, { ticket: { ...ticketDto(t), deviceInfo: t.device_info }, messages });
  });
  router.post('/admin/api/tickets/:id/reply', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const t = await ctx.db.first('SELECT id FROM support_tickets WHERE id = ?', Number(ctx.params.id) || 0);
    if (!t) throw new HttpError(404, 'Ticket not found');
    const b = await body(ctx, 16 * 1024);
    const text = str(b.message, 'Reply', { min: 1, max: 5000 });
    const now = Date.now();
    await ctx.db.batch([
      [`INSERT INTO support_messages (ticket_id, sender, body, created_at) VALUES (?, 'admin', ?, ?)`, t.id, text, now],
      [`UPDATE support_tickets SET status = 'answered', updated_at = ? WHERE id = ?`, now, t.id],
    ]);
    await audit(ctx, admin, 'ticket.replied', { id: t.id });
    return json(200, { ok: true });
  });
  router.post('/admin/api/tickets/:id/status', async (ctx) => {
    const admin = await requireAdmin(ctx);
    const b = await body(ctx, 4096);
    const status = oneOf(b.status, 'Status', ['open', 'answered', 'closed']);
    const res = await ctx.db.run('UPDATE support_tickets SET status = ?, updated_at = ? WHERE id = ?', status, Date.now(), Number(ctx.params.id) || 0);
    if (!res.meta.changes) throw new HttpError(404, 'Ticket not found');
    await audit(ctx, admin, 'ticket.status', { id: Number(ctx.params.id), status });
    return json(200, { ok: true });
  });

  // ---------- Audit log ----------
  router.get('/admin/api/audit', async (ctx) => {
    await requireAdmin(ctx);
    const lim = Math.min(500, Math.max(1, Number(ctx.query.get('limit')) || 100));
    return json(200, { entries: await ctx.db.all('SELECT username, action, detail, ip, created_at AS createdAt FROM audit_log ORDER BY id DESC LIMIT ?', lim) });
  });
}
