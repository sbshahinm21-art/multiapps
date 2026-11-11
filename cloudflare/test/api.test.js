// End-to-end API test: spins up the real server on a temp data dir and exercises every flow.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';

import { startLocal } from './cf-local.js';
const { hashPassword, totpAt, hotp, base32Encode, base32Decode, rolloutBucket } = await import('../src/security.js');

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ma-cf-test-'));
const SETUP_TOKEN = 'setup-token-for-tests-123';
let local, base, D1;
const PASSWORD = 'Correct-Horse-42!';
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');
before(async () => {
  local = await startLocal({ dataDir, vars: { COOKIE_SECURE: 'false', RATE_LIMIT_SCALE: '100', SETUP_TOKEN } });
  base = local.url; D1 = local.env.DB;
});
after(() => { local.server.close(); fs.rmSync(dataDir, { recursive: true, force: true }); });

test('first-admin setup: needs the token, works once', async () => {
  const c = client();
  assert.equal((await c.call('GET', '/admin/api/session')).data.needsSetup, true);
  assert.equal((await c.call('POST', '/admin/api/setup', { setupToken: 'wrong', username: 'owner', password: PASSWORD })).status, 403);
  assert.equal((await c.call('POST', '/admin/api/setup', { setupToken: SETUP_TOKEN, username: 'owner', password: 'short' })).status, 400);
  assert.equal((await c.call('POST', '/admin/api/setup', { setupToken: SETUP_TOKEN, username: 'owner', password: PASSWORD })).status, 200);
  assert.equal((await c.call('POST', '/admin/api/setup', { setupToken: SETUP_TOKEN, username: 'evil', password: PASSWORD })).status, 409);
  assert.equal((await c.call('GET', '/admin/api/session')).data.needsSetup, false);
});

// Simple cookie-aware client
function client() {
  let cookie = ''; let csrf = '';
  const call = async (method, p, body, headers = {}) => {
    const h = { ...headers };
    if (cookie) h.cookie = cookie;
    if (csrf) h['x-csrf-token'] = csrf;
    let payload = body;
    if (body && !(body instanceof Buffer) && typeof body === 'object') { payload = JSON.stringify(body); h['content-type'] = 'application/json'; }
    const res = await fetch(base + p, { method, headers: h, body: payload, redirect: 'manual' });
    const sc = res.headers.get('set-cookie');
    if (sc) cookie = sc.split(';')[0];
    const ct = res.headers.get('content-type') || '';
    const data = ct.includes('json') ? await res.json() : Buffer.from(await res.arrayBuffer());
    if (data && data.csrf) csrf = data.csrf;
    return { status: res.status, data, headers: res.headers };
  };
  return { call, setCsrf: (c) => { csrf = c; }, get csrf() { return csrf; } };
}

const fakeApk = (n = 4096) => Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), crypto.randomBytes(n)]);
const dev = (i) => `device-${String(i).padStart(12, '0')}`;
let admin; let secret; let recoveryCodes;

test('TOTP matches RFC 6238 test vector', async () => {
  // RFC 6238 SHA-1 secret "12345678901234567890", T=59s → 94287082 (8 digits) → last 6 = 287082
  const s = base32Encode(Buffer.from('12345678901234567890'));
  assert.equal(await hotp(s, 1), '287082');
  assert.equal(Buffer.from(base32Decode(s)).toString(), '12345678901234567890');
});

test('admin API is protected', async () => {
  const c = client();
  assert.equal((await c.call('GET', '/admin/api/releases')).status, 401);
  assert.equal((await c.call('GET', '/admin/api/session')).data.stage, 'none');
});

test('wrong password rejected; lockout after 5 failures', async () => {
  await D1.prepare('INSERT INTO admins (username, password_hash, created_at) VALUES (?, ?, ?)').bind('victim', await hashPassword(PASSWORD), Date.now()).run();
  const c = client();
  for (let i = 0; i < 5; i++) assert.equal((await c.call('POST', '/admin/api/login', { username: 'victim', password: 'nope' })).status, 401);
  const r = await c.call('POST', '/admin/api/login', { username: 'victim', password: PASSWORD });
  assert.equal(r.status, 423, 'locked even with the right password');
});

test('login → forced 2FA setup → full session', async () => {
  admin = client();
  const r = await admin.call('POST', '/admin/api/login', { username: 'owner', password: PASSWORD });
  assert.equal(r.status, 200); assert.equal(r.data.next, 'totp_setup');
  // Password-stage session cannot use the admin API
  assert.equal((await admin.call('GET', '/admin/api/releases')).status, 401);
  const setup = await admin.call('POST', '/admin/api/2fa/setup');
  assert.equal(setup.status, 200);
  secret = setup.data.secret;
  assert.match(setup.data.otpauthUri, /^otpauth:\/\/totp\//);
  assert.equal((await admin.call('POST', '/admin/api/2fa/verify', { code: '000000' })).status, 401);
  const v = await admin.call('POST', '/admin/api/2fa/verify', { code: await totpAt(secret) });
  assert.equal(v.status, 200);
  assert.equal(v.data.recoveryCodes.length, 10);
  recoveryCodes = v.data.recoveryCodes;
  assert.equal((await admin.call('GET', '/admin/api/releases')).status, 200);
});

test('CSRF token required for writes', async () => {
  const saved = admin.csrf;
  admin.setCsrf('bad');
  assert.equal((await admin.call('POST', '/admin/api/releases', { versionCode: 1, versionName: '1.0' })).status, 403);
  admin.setCsrf(saved);
});

test('second login needs TOTP; replayed code rejected; recovery code works once', async () => {
  const c = client();
  const r = await c.call('POST', '/admin/api/login', { username: 'owner', password: PASSWORD });
  assert.equal(r.data.next, 'totp');
  // The code used during setup is in the same 30s step → replay must fail
  const same = await c.call('POST', '/admin/api/2fa/verify', { code: await totpAt(secret) });
  if (same.status === 200) {
    // Time step rolled over between calls — acceptable; still verify recovery separately.
  } else {
    assert.equal(same.status, 401);
  }
  const c2 = client();
  await c2.call('POST', '/admin/api/login', { username: 'owner', password: PASSWORD });
  const rec = await c2.call('POST', '/admin/api/2fa/recovery', { code: recoveryCodes[0].toLowerCase() });
  assert.equal(rec.status, 200); assert.equal(rec.data.remainingRecoveryCodes, 9);
  const c3 = client();
  await c3.call('POST', '/admin/api/login', { username: 'owner', password: PASSWORD });
  assert.equal((await c3.call('POST', '/admin/api/2fa/recovery', { code: recoveryCodes[0] })).status, 401);
});

let rel1, rel2, rel3;
async function uploadApk(id) {
  const apk = fakeApk();
  const r = await admin.call('PUT', `/admin/api/releases/${id}/file`, apk, { 'content-type': 'application/octet-stream', 'x-content-sha256': sha(apk) });
  assert.equal(r.status, 200, JSON.stringify(r.data));
}
test('release: create, upload APK, publish', async () => {
  const c = await admin.call('POST', '/admin/api/releases', { versionCode: 10, versionName: '1.0.0', channel: 'stable', changelog: 'First release' });
  assert.equal(c.status, 201); rel1 = c.data.release;
  assert.equal((await admin.call('POST', `/admin/api/releases/${rel1.id}/publish`)).status, 409, 'cannot publish without file');
  // Non-APK rejected
  const notZip = Buffer.from('hello world, not a zip'.repeat(100));
  const bad = await admin.call('PUT', `/admin/api/releases/${rel1.id}/file`, notZip, { 'content-type': 'application/octet-stream', 'x-content-sha256': sha(notZip) });
  assert.equal(bad.status, 400);
  assert.match(bad.data.error, /not a valid APK/);
  const apk = fakeApk();
  // Corrupted on the way: checksum does not match the bytes → R2 rejects
  const corrupt = await admin.call('PUT', `/admin/api/releases/${rel1.id}/file`, apk, { 'content-type': 'application/octet-stream', 'x-content-sha256': sha(Buffer.from('other')) });
  assert.equal(corrupt.status, 400);
  assert.match(corrupt.data.error, /corrupted/);
  assert.equal((await admin.call('PUT', `/admin/api/releases/${rel1.id}/file`, apk, { 'content-type': 'application/octet-stream' })).status, 400, 'checksum required');
  const up = await admin.call('PUT', `/admin/api/releases/${rel1.id}/file`, apk, { 'content-type': 'application/vnd.android.package-archive', 'x-content-sha256': sha(apk) });
  assert.equal(up.status, 200);
  assert.equal(up.data.release.sha256, crypto.createHash('sha256').update(apk).digest('hex'));
  const p = await admin.call('POST', `/admin/api/releases/${rel1.id}/publish`);
  assert.equal(p.status, 200); assert.equal(p.data.release.status, 'live');
  // Lower or equal version code refused
  assert.equal((await admin.call('POST', '/admin/api/releases', { versionCode: 10, versionName: 'x' })).status, 409);
});

test('public site + download latest with Range resume and checksum', async () => {
  const pub = client();
  const site = await pub.call('GET', '/api/v1/public/site');
  assert.equal(site.data.latest.versionName, '1.0.0');
  const full = await pub.call('GET', '/download/latest');
  assert.equal(full.status, 200);
  assert.equal(crypto.createHash('sha256').update(full.data).digest('hex'), site.data.latest.sha256);
  assert.match(full.headers.get('content-disposition'), /MultiApps-1\.0\.0\.apk/);
  const part = await pub.call('GET', '/download/latest', null, { range: 'bytes=100-199' });
  assert.equal(part.status, 206); assert.equal(part.data.length, 100);
  assert.deepEqual(part.data, full.data.subarray(100, 200));
  const rels = await pub.call('GET', '/api/v1/public/releases');
  assert.equal(rels.data.releases[0].changelog, 'First release');
});

test('update check: optional update, staged rollout is deterministic', async () => {
  const pub = client();
  let r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(1)}&versionCode=5`);
  assert.equal(r.data.updateAvailable, true); assert.equal(r.data.mandatory, false); assert.equal(r.data.release.versionCode, 10);
  r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(1)}&versionCode=10`);
  assert.equal(r.data.updateAvailable, false);

  const c = await admin.call('POST', '/admin/api/releases', { versionCode: 11, versionName: '1.1.0', rolloutPercent: 20, changelog: 'Staged' });
  rel2 = c.data.release;
  await uploadApk(rel2.id);
  await admin.call('POST', `/admin/api/releases/${rel2.id}/publish`);
  let inRollout = 0; const N = 300;
  for (let i = 0; i < N; i++) {
    const res = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(1000 + i)}&versionCode=10`);
    const expected = (await rolloutBucket(dev(1000 + i), rel2.id)) < 20;
    assert.equal(res.data.updateAvailable, expected);
    if (expected) inRollout++;
  }
  assert.ok(inRollout > N * 0.1 && inRollout < N * 0.3, `~20% got it (${inRollout}/${N})`);
  // Public "latest" stays at 1.0.0 because 1.1.0 is not at 100%
  assert.equal((await pub.call('GET', '/api/v1/public/site')).data.latest.versionName, '1.0.0');
  await admin.call('PATCH', `/admin/api/releases/${rel2.id}`, { rolloutPercent: 100 });
  assert.equal((await pub.call('GET', '/api/v1/public/site')).data.latest.versionName, '1.1.0');
});

test('mandatory update and min supported version', async () => {
  const pub = client();
  await admin.call('PATCH', `/admin/api/releases/${rel2.id}`, { minSupportedVersionCode: 10 });
  let r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(2)}&versionCode=9`);
  assert.equal(r.data.mandatory, true, 'below min supported → mandatory');
  r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(2)}&versionCode=10`);
  assert.equal(r.data.mandatory, false);
  await admin.call('PATCH', `/admin/api/releases/${rel2.id}`, { updateType: 'mandatory' });
  r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(2)}&versionCode=10`);
  assert.equal(r.data.mandatory, true);
  await admin.call('PATCH', `/admin/api/releases/${rel2.id}`, { updateType: 'optional' });
});

test('beta channel: only testers get beta builds; join code works', async () => {
  const pub = client();
  const c = await admin.call('POST', '/admin/api/releases', { versionCode: 12, versionName: '1.2.0-beta1', channel: 'beta' });
  rel3 = c.data.release;
  await uploadApk(rel3.id);
  await admin.call('POST', `/admin/api/releases/${rel3.id}/publish`);
  let r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(3)}&versionCode=11`);
  assert.equal(r.data.updateAvailable, false);
  assert.equal((await pub.call('GET', `/download/release/${rel3.id}`)).status, 403);
  assert.equal((await pub.call('POST', '/api/v1/beta/join', { deviceId: dev(3), code: 'anything' })).status, 403, 'closed program');
  await admin.call('POST', '/admin/api/testers', { deviceId: dev(3), label: 'My Pixel' });
  r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(3)}&versionCode=11`);
  assert.equal(r.data.release.versionCode, 12); assert.equal(r.data.tester, true);
  const dl = await pub.call('GET', new URL(r.data.release.downloadUrl).pathname + new URL(r.data.release.downloadUrl).search);
  assert.equal(dl.status, 200);
  await admin.call('PUT', '/admin/api/testers/join-code', { code: 'BETA-2026' });
  assert.equal((await pub.call('POST', '/api/v1/beta/join', { deviceId: dev(4), code: 'WRONG-123' })).status, 403);
  assert.equal((await pub.call('POST', '/api/v1/beta/join', { deviceId: dev(4), code: 'BETA-2026' })).status, 200);
  r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(4)}&versionCode=11`);
  assert.equal(r.data.release.versionCode, 12);
  // Promote beta → stable
  const pr = await admin.call('PATCH', `/admin/api/releases/${rel3.id}`, { channel: 'stable' });
  assert.equal(pr.data.release.channel, 'stable');
  r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(5)}&versionCode=11`);
  assert.equal(r.data.release.versionCode, 12);
});

test('halt, resume and rollback', async () => {
  const pub = client();
  await admin.call('POST', `/admin/api/releases/${rel3.id}/halt`);
  let r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(6)}&versionCode=10`);
  assert.equal(r.data.release.versionCode, 11, 'halted release skipped, falls back to 11');
  await admin.call('POST', `/admin/api/releases/${rel3.id}/resume`);
  r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(6)}&versionCode=10`);
  assert.equal(r.data.release.versionCode, 12);
  assert.equal((await admin.call('POST', `/admin/api/releases/${rel3.id}/rollback`, { confirmVersionCode: 999 })).status, 400);
  const rb = await admin.call('POST', `/admin/api/releases/${rel3.id}/rollback`, { confirmVersionCode: 12 });
  assert.equal(rb.status, 200); assert.equal(rb.data.release.status, 'rolled_back');
  assert.equal(rb.data.fallback.versionCode, 11);
  r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(6)}&versionCode=10`);
  assert.equal(r.data.release.versionCode, 11);
  r = await pub.call('GET', `/api/v1/update/check?deviceId=${dev(4)}&versionCode=12`);
  assert.equal(r.data.currentWithdrawn, true, 'devices on the bad build are told');
  assert.equal((await pub.call('GET', `/download/release/${rel3.id}`)).status, 404);
  assert.equal((await admin.call('POST', `/admin/api/releases/${rel3.id}/resume`)).status, 409, 'rollback is permanent');
});

test('events and statistics', async () => {
  const pub = client();
  for (const e of ['download_started', 'download_completed', 'install_success']) {
    assert.equal((await pub.call('POST', '/api/v1/events', { deviceId: dev(1), versionCode: 10, releaseId: rel2.id, event: e })).status, 200);
  }
  assert.equal((await pub.call('POST', '/api/v1/events', { deviceId: dev(1), versionCode: 10, event: 'hack' })).status, 400);
  const s = await admin.call('GET', '/admin/api/stats');
  assert.equal(s.status, 200);
  const r2 = s.data.releases.find((x) => x.id === rel2.id);
  assert.equal(r2.installSuccess, 1); assert.equal(r2.downloadCompleted, 1);
  assert.ok(r2.offeredDevices > 0);
  assert.ok(s.data.totals.devices > 300);
  assert.equal(s.data.daily.length, 14);
  assert.ok(s.data.daily[13].checks > 0, 'today has checks');
});

test('remote settings: scopes merge, ETag, history and revert', async () => {
  const pub = client();
  let r = await pub.call('GET', `/api/v1/config?deviceId=${dev(1)}`);
  assert.equal(r.data.config.ads.enabled, false);
  const etag = r.headers.get('etag');
  const again = await fetch(`${base}/api/v1/config?deviceId=${dev(1)}`, { headers: { 'if-none-match': etag } });
  assert.equal(again.status, 304);
  assert.equal((await admin.call('PUT', '/admin/api/config/all', { config: { ads: { enabled: 'yes' } } })).status, 400);
  await admin.call('PUT', '/admin/api/config/all', { config: { ads: { enabled: true, units: { banner: 'ca-app-pub-x/1' } } }, note: 'Ads on' });
  await admin.call('PUT', '/admin/api/config/beta', { config: { ads: { enabled: false } }, note: 'No ads for testers' });
  r = await pub.call('GET', `/api/v1/config?deviceId=${dev(1)}`);
  assert.equal(r.data.config.ads.enabled, true);
  assert.equal(r.data.config.ads.units.banner, 'ca-app-pub-x/1');
  assert.equal(r.data.config.ads.frequency.interstitialMinIntervalSec, 180, 'defaults kept');
  r = await pub.call('GET', `/api/v1/config?deviceId=${dev(3)}&channel=beta`);
  assert.equal(r.data.channel, 'beta'); assert.equal(r.data.config.ads.enabled, false);
  r = await pub.call('GET', `/api/v1/config?deviceId=${dev(9)}&channel=beta`);
  assert.equal(r.data.channel, 'stable', 'non-testers cannot pick beta config');
  const h = await admin.call('GET', '/admin/api/config/all/history');
  assert.equal(h.data.history.length, 1);
  await admin.call('PUT', '/admin/api/config/all', { config: {} });
  await admin.call('POST', `/admin/api/config/all/revert/${h.data.history[0].id}`);
  r = await pub.call('GET', `/api/v1/config?deviceId=${dev(1)}`);
  assert.equal(r.data.config.ads.enabled, true, 'revert restored');
});

test('support tickets round trip', async () => {
  const pub = client();
  const t = await pub.call('POST', '/api/v1/support/tickets', { deviceId: dev(1), category: 'proxy_problem', subject: 'Proxy fails', message: 'My SOCKS5 proxy does not connect.', appVersion: '1.1.0' });
  assert.equal(t.status, 201);
  assert.equal((await pub.call('GET', `/api/v1/support/tickets/${t.data.id}`)).status, 404, 'token required');
  await admin.call('POST', `/admin/api/tickets/${t.data.id}/reply`, { message: 'Please check the port.' });
  const view = await pub.call('GET', `/api/v1/support/tickets/${t.data.id}`, null, { 'x-ticket-token': t.data.accessToken });
  assert.equal(view.data.status, 'answered'); assert.equal(view.data.messages.length, 2);
  assert.equal(view.data.messages[1].sender, 'admin');
});

test('screenshots and site content', async () => {
  const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), crypto.randomBytes(200)]);
  assert.equal((await admin.call('POST', '/admin/api/screenshots?caption=Home', png, { 'content-type': 'image/png' })).status, 201);
  assert.equal((await admin.call('POST', '/admin/api/screenshots', Buffer.from('<svg></svg>'), { 'content-type': 'image/svg+xml' })).status, 400);
  await admin.call('PUT', '/admin/api/site', { appName: 'Multi Apps', tagline: 'T', description: 'D', supportEmail: '', features: [{ title: 'A', text: 'B' }] });
  const site = await client().call('GET', '/api/v1/public/site');
  assert.equal(site.data.screenshots.length, 1);
  assert.equal(site.data.features[0].title, 'A');
  const img = await client().call('GET', new URL(site.data.screenshots[0].url).pathname);
  assert.equal(img.status, 200); assert.deepEqual(img.data, png);
});

test('path traversal blocked; audit log recorded', async () => {
  for (const p of ['/admin/..%2f..%2fsrc%2findex.js', '/admin/../src/index.js', '/admin/%2e%2e/wrangler.toml']) {
    const text = await (await fetch(`${base}${p}`)).text();
    assert.ok(!text.includes('export default') && !text.includes('d1_databases'), `source must never be served: ${p}`);
  }
  const page = await fetch(`${base}/admin/`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-security-policy'), /script-src 'self'/);
  const a = await admin.call('GET', '/admin/api/audit');
  const actions = a.data.entries.map((e) => e.action);
  for (const x of ['release.publish', 'release.rollback', 'config.updated', '2fa.enabled']) assert.ok(actions.includes(x), x);
});
