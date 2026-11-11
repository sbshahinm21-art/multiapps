import { HttpError, parseCookies, rateLimitDb } from './http.js';
import {
  verifyPassword, hashPassword, validatePasswordStrength, randomToken, sha256, safeEqual,
  generateTotpSecret, verifyTotp, otpauthUri, generateRecoveryCodes, normalizeRecovery,
} from './security.js';
import { audit } from './data.js';

const MAX_FAILED = 5;
const LOCK_MS = 15 * 60 * 1000;
const PASSWORD_STAGE_MS = 10 * 60 * 1000;
// Fixed dummy hash so sign-in takes similar time when the username does not exist.
const DUMMY_HASH = 'pbkdf2-sha256$30000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=';

const cookieName = (cfg) => (cfg.cookieSecure ? '__Host-ma_admin' : 'ma_admin');
function setCookie(ctx, token, maxAgeSec) {
  const parts = [`${cookieName(ctx.cfg)}=${token}`, 'Path=/', 'HttpOnly', 'SameSite=Strict', `Max-Age=${maxAgeSec}`];
  if (ctx.cfg.cookieSecure) parts.push('Secure');
  ctx.setCookies.push(parts.join('; '));
}

export async function getSession(ctx) {
  if (ctx._session !== undefined) return ctx._session;
  const token = parseCookies(ctx.request)[cookieName(ctx.cfg)];
  let s = null;
  if (token && token.length <= 100) {
    s = await ctx.db.first(`SELECT s.*, a.username, a.totp_enabled, a.totp_secret, a.totp_last_step
      FROM sessions s JOIN admins a ON a.id = s.admin_id WHERE s.id = ?`, await sha256(token));
    if (s && s.expires_at < Date.now()) { await ctx.db.run('DELETE FROM sessions WHERE id = ?', s.id); s = null; }
  }
  ctx._session = s;
  return s;
}

/** Fully signed-in admin (password + 2FA). Checks the CSRF token on every write. */
export async function requireAdmin(ctx) {
  const s = await getSession(ctx);
  if (!s || s.stage !== 'full') throw new HttpError(401, 'Not signed in');
  if (!['GET', 'HEAD'].includes(ctx.request.method) && !safeEqual(ctx.request.headers.get('x-csrf-token') || '', s.csrf)) {
    throw new HttpError(403, 'Invalid security token. Reload the page.');
  }
  return { id: s.admin_id, username: s.username, session: s };
}

async function requirePasswordStage(ctx) {
  const s = await getSession(ctx);
  if (!s || s.stage !== 'password') throw new HttpError(401, 'Sign in with your password first');
  if (!safeEqual(ctx.request.headers.get('x-csrf-token') || '', s.csrf)) throw new HttpError(403, 'Invalid security token');
  return s;
}

async function upgradeSession(ctx, s) {
  const token = randomToken();
  const csrf = randomToken(24);
  const ttl = ctx.cfg.sessionHours * 3600 * 1000;
  const now = Date.now();
  await ctx.db.batch([
    ['DELETE FROM sessions WHERE id = ?', s.id],
    [`INSERT INTO sessions (id, admin_id, stage, csrf, ip, ua, created_at, expires_at) VALUES (?, ?, 'full', ?, ?, ?, ?, ?)`,
      await sha256(token), s.admin_id, csrf, s.ip, s.ua, now, now + ttl],
    ['UPDATE admins SET last_login_at = ? WHERE id = ?', now, s.admin_id],
    ['DELETE FROM sessions WHERE expires_at < ?', now],
  ]);
  setCookie(ctx, token, Math.floor(ttl / 1000));
  return csrf;
}

export async function needsSetup(ctx) {
  return !(await ctx.db.first('SELECT id FROM admins LIMIT 1'));
}

/** Creates the very first admin. Needs the SETUP_TOKEN secret and only works while no admin exists. */
export async function setupFirstAdmin(ctx, body) {
  await rateLimitDb(ctx.db, `setup:${ctx.ip}`, 5, 10 * 60_000, ctx.cfg.rateLimitScale);
  if (!ctx.cfg.setupToken) throw new HttpError(403, 'Setup is disabled. Set the SETUP_TOKEN secret first (see the guide).');
  if (!(await needsSetup(ctx))) throw new HttpError(409, 'An admin already exists. Sign in instead.');
  if (!safeEqual(String(body.setupToken || ''), ctx.cfg.setupToken)) throw new HttpError(403, 'Setup token is not correct');
  const username = String(body.username || '').trim();
  if (!/^[a-zA-Z0-9_.-]{3,40}$/.test(username)) throw new HttpError(400, 'Username: 3–40 letters, numbers, dot, dash or underscore');
  const err = validatePasswordStrength(body.password);
  if (err) throw new HttpError(400, err);
  await ctx.db.run('INSERT INTO admins (username, password_hash, created_at) VALUES (?, ?, ?)', username, await hashPassword(body.password, ctx.cfg.pbkdf2Iterations), Date.now());
  await audit(ctx, null, 'setup.first_admin', { username });
  return { ok: true };
}

export async function login(ctx, body) {
  await rateLimitDb(ctx.db, `login:${ctx.ip}`, 10, 60_000, ctx.cfg.rateLimitScale);
  const username = String(body.username || '').trim().slice(0, 60);
  const password = String(body.password || '').slice(0, 300);
  const admin = await ctx.db.first('SELECT * FROM admins WHERE username = ?', username);
  const now = Date.now();
  if (admin && admin.locked_until > now) {
    throw new HttpError(423, `Account locked after too many failed attempts. Try again in ${Math.ceil((admin.locked_until - now) / 60000)} minutes.`);
  }
  const passOk = await verifyPassword(password, admin ? admin.password_hash : DUMMY_HASH);
  if (!admin || !passOk) {
    if (admin) {
      const fails = admin.failed_attempts + 1;
      await ctx.db.run('UPDATE admins SET failed_attempts = ?, locked_until = ? WHERE id = ?',
        fails >= MAX_FAILED ? 0 : fails, fails >= MAX_FAILED ? now + LOCK_MS : 0, admin.id);
      await audit(ctx, admin, 'login.failed');
    }
    throw new HttpError(401, 'Wrong username or password');
  }
  const token = randomToken();
  const csrf = randomToken(24);
  await ctx.db.batch([
    ['UPDATE admins SET failed_attempts = 0, locked_until = 0 WHERE id = ?', admin.id],
    [`INSERT INTO sessions (id, admin_id, stage, csrf, ip, ua, created_at, expires_at) VALUES (?, ?, 'password', ?, ?, ?, ?, ?)`,
      await sha256(token), admin.id, csrf, ctx.ip, (ctx.request.headers.get('user-agent') || '').slice(0, 300), now, now + PASSWORD_STAGE_MS],
  ]);
  setCookie(ctx, token, PASSWORD_STAGE_MS / 1000);
  return { next: admin.totp_enabled ? 'totp' : 'totp_setup', csrf };
}

export async function startTotpSetup(ctx) {
  const s = await requirePasswordStage(ctx);
  if (s.totp_enabled) throw new HttpError(400, 'Two-factor authentication is already set up');
  const secret = generateTotpSecret();
  await ctx.db.run('UPDATE sessions SET pending_totp_secret = ? WHERE id = ?', secret, s.id);
  return { secret, otpauthUri: otpauthUri(secret, s.username, ctx.cfg.totpIssuer) };
}

const hashCodes = async (codes) => JSON.stringify(await Promise.all(codes.map((c) => sha256(normalizeRecovery(c)))));

export async function verifyTotpLogin(ctx, body) {
  await rateLimitDb(ctx.db, `totp:${ctx.ip}`, 10, 60_000, ctx.cfg.rateLimitScale);
  const s = await requirePasswordStage(ctx);
  const code = String(body.code || '').replace(/\s/g, '');
  const admin = { id: s.admin_id, username: s.username };
  if (!s.totp_enabled) {
    if (!s.pending_totp_secret) throw new HttpError(400, 'Start two-factor setup first');
    const step = await verifyTotp(s.pending_totp_secret, code);
    if (step < 0) throw new HttpError(401, 'Code is not correct. Check the time on your phone and try again.');
    const codes = generateRecoveryCodes();
    await ctx.db.run('UPDATE admins SET totp_secret = ?, totp_enabled = 1, totp_last_step = ?, recovery_codes = ? WHERE id = ?',
      s.pending_totp_secret, step, await hashCodes(codes), s.admin_id);
    await audit(ctx, admin, '2fa.enabled');
    const csrf = await upgradeSession(ctx, s);
    await audit(ctx, admin, 'login.success');
    return { ok: true, csrf, recoveryCodes: codes };
  }
  const step = await verifyTotp(s.totp_secret, code, s.totp_last_step);
  if (step < 0) { await audit(ctx, admin, 'login.2fa_failed'); throw new HttpError(401, 'Code is not correct or was already used'); }
  await ctx.db.run('UPDATE admins SET totp_last_step = ? WHERE id = ?', step, s.admin_id);
  const csrf = await upgradeSession(ctx, s);
  await audit(ctx, admin, 'login.success');
  return { ok: true, csrf };
}

export async function useRecoveryCode(ctx, body) {
  await rateLimitDb(ctx.db, `totp:${ctx.ip}`, 10, 60_000, ctx.cfg.rateLimitScale);
  const s = await requirePasswordStage(ctx);
  if (!s.totp_enabled) throw new HttpError(400, 'Two-factor authentication is not set up');
  const row = await ctx.db.first('SELECT recovery_codes FROM admins WHERE id = ?', s.admin_id);
  const list = JSON.parse(row.recovery_codes);
  const h = await sha256(normalizeRecovery(body.code));
  const idx = list.findIndex((x) => safeEqual(x, h));
  if (idx < 0) throw new HttpError(401, 'Recovery code is not valid');
  list.splice(idx, 1);
  await ctx.db.run('UPDATE admins SET recovery_codes = ? WHERE id = ?', JSON.stringify(list), s.admin_id);
  const csrf = await upgradeSession(ctx, s);
  await audit(ctx, { id: s.admin_id, username: s.username }, 'login.recovery_code', { remaining: list.length });
  return { ok: true, csrf, remainingRecoveryCodes: list.length };
}

export async function logout(ctx) {
  const s = await getSession(ctx);
  if (s) await ctx.db.run('DELETE FROM sessions WHERE id = ?', s.id);
  setCookie(ctx, '', 0);
}

export async function changePassword(ctx, admin, body) {
  const row = await ctx.db.first('SELECT * FROM admins WHERE id = ?', admin.id);
  if (!(await verifyPassword(String(body.currentPassword || ''), row.password_hash))) throw new HttpError(400, 'Current password is wrong');
  const err = validatePasswordStrength(body.newPassword);
  if (err) throw new HttpError(400, err);
  const step = await verifyTotp(row.totp_secret, String(body.code || ''), row.totp_last_step);
  if (step < 0) throw new HttpError(400, 'Authenticator code is not correct');
  await ctx.db.batch([
    ['UPDATE admins SET password_hash = ?, totp_last_step = ? WHERE id = ?', await hashPassword(body.newPassword, ctx.cfg.pbkdf2Iterations), step, admin.id],
    ['DELETE FROM sessions WHERE admin_id = ? AND id != ?', admin.id, admin.session.id],
  ]);
  await audit(ctx, admin, 'account.password_changed');
}

export async function regenerateRecoveryCodes(ctx, admin, body) {
  const row = await ctx.db.first('SELECT * FROM admins WHERE id = ?', admin.id);
  const step = await verifyTotp(row.totp_secret, String(body.code || ''), row.totp_last_step);
  if (step < 0) throw new HttpError(400, 'Authenticator code is not correct');
  const codes = generateRecoveryCodes();
  await ctx.db.run('UPDATE admins SET recovery_codes = ?, totp_last_step = ? WHERE id = ?', await hashCodes(codes), step, admin.id);
  await audit(ctx, admin, '2fa.recovery_regenerated');
  return codes;
}
