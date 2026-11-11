// Security helpers using only Web Crypto (available in Cloudflare Workers without compatibility flags).
const enc = new TextEncoder();

export const toHex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
const toB64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const fromB64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
export const randomBytes = (n) => crypto.getRandomValues(new Uint8Array(n));
export function randomToken(bytes = 32) {
  return toB64(randomBytes(bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
export async function sha256(input) {
  const data = typeof input === 'string' ? enc.encode(input) : input;
  return toHex(await crypto.subtle.digest('SHA-256', data));
}
/** Constant-time string comparison. */
export function safeEqual(a, b) {
  const x = enc.encode(String(a)); const y = enc.encode(String(b));
  if (x.length !== y.length) return false;
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

// ---------- Password hashing: PBKDF2-SHA256 ----------
// Cloudflare Workers allow at most 100,000 PBKDF2 iterations, and the Free plan gives each request
// about 10 ms of CPU. 30,000 iterations (~5 ms) keeps sign-in inside that budget. Sign-in is also
// protected by mandatory 2FA, account lockout and rate limits. On a paid plan set PBKDF2_ITERATIONS=100000.
export const DEFAULT_PBKDF2_ITER = 30_000;
async function pbkdf2(password, salt, iterations) {
  const key = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  return crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, key, 256);
}
export async function hashPassword(password, iterations = DEFAULT_PBKDF2_ITER) {
  const iter = Math.min(100_000, Math.max(10_000, Math.floor(iterations) || DEFAULT_PBKDF2_ITER));
  const salt = randomBytes(16);
  const bits = await pbkdf2(password, salt, iter);
  return `pbkdf2-sha256$${iter}$${toB64(salt)}$${toB64(bits)}`;
}
export async function verifyPassword(password, stored) {
  try {
    const [algo, iter, saltB64, hashB64] = String(stored).split('$');
    if (algo !== 'pbkdf2-sha256') return false;
    const bits = await pbkdf2(password, fromB64(saltB64), Number(iter));
    return safeEqual(toB64(bits), hashB64);
  } catch { return false; }
}
export function validatePasswordStrength(pw) {
  if (typeof pw !== 'string' || pw.length < 12) return 'Password must be at least 12 characters.';
  if (pw.length > 256) return 'Password is too long.';
  const classes = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^a-zA-Z0-9]/].filter((re) => re.test(pw)).length;
  if (classes < 3) return 'Use at least three of: lowercase, uppercase, numbers, symbols.';
  return null;
}

// ---------- Base32 ----------
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
export function base32Encode(bytes) {
  let bits = 0, value = 0, out = '';
  for (const byte of bytes) {
    value = (value << 8) | byte; bits += 8;
    while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}
export function base32Decode(str) {
  const clean = String(str).toUpperCase().replace(/=+$/, '').replace(/\s/g, '');
  let bits = 0, value = 0; const out = [];
  for (const ch of clean) {
    const idx = B32.indexOf(ch);
    if (idx === -1) throw new Error('Invalid base32');
    value = (value << 5) | idx; bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 255); bits -= 8; }
  }
  return new Uint8Array(out);
}

// ---------- TOTP (RFC 6238, SHA-1, 6 digits, 30 s) ----------
export const generateTotpSecret = () => base32Encode(randomBytes(20));

export async function hotp(secretB32, counter, digits = 6) {
  const msg = new Uint8Array(8);
  let c = BigInt(counter);
  for (let i = 7; i >= 0; i--) { msg[i] = Number(c & 0xffn); c >>= 8n; }
  const key = await crypto.subtle.importKey('raw', base32Decode(secretB32), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
  const h = new Uint8Array(await crypto.subtle.sign('HMAC', key, msg));
  const o = h[h.length - 1] & 0xf;
  const code = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(code % 10 ** digits).padStart(digits, '0');
}
export const totpAt = (secret, timeMs = Date.now()) => hotp(secret, Math.floor(timeMs / 30000));

/** Returns the matched time step (for replay protection) or -1. Allows ±1 step drift. */
export async function verifyTotp(secret, code, lastUsedStep = 0, timeMs = Date.now()) {
  if (!secret || !/^\d{6}$/.test(String(code || ''))) return -1;
  const now = Math.floor(timeMs / 30000);
  for (const s of [now - 1, now, now + 1]) {
    if (s <= lastUsedStep) continue;
    if (safeEqual(await hotp(secret, s), code)) return s;
  }
  return -1;
}
export function otpauthUri(secret, account, issuer) {
  return `otpauth://totp/${encodeURIComponent(`${issuer}:${account}`)}?secret=${secret}&issuer=${encodeURIComponent(issuer)}&algorithm=SHA1&digits=6&period=30`;
}

// ---------- Recovery codes ----------
export function generateRecoveryCodes(n = 10) {
  return Array.from({ length: n }, () => {
    const raw = toHex(randomBytes(5)).toUpperCase();
    return `${raw.slice(0, 5)}-${raw.slice(5)}`;
  });
}
export const normalizeRecovery = (c) => String(c || '').toUpperCase().replace(/[^0-9A-F]/g, '');

// ---------- Staged rollout bucket: stable value in [0, 100) ----------
export async function rolloutBucket(deviceId, releaseId) {
  const h = new DataView(await crypto.subtle.digest('SHA-256', enc.encode(`${deviceId}:${releaseId}`)));
  return (h.getUint32(0) % 10000) / 100;
}
