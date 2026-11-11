export class HttpError extends Error {
  constructor(status, message, extra) { super(message); this.status = status; this.extra = extra; }
}

// ---------- Router ----------
export class Router {
  constructor() { this.routes = []; }
  add(method, pattern, handler) {
    const keys = [];
    const re = new RegExp('^' + pattern.replace(/\/:([a-zA-Z]+)/g, (_, k) => { keys.push(k); return '/([^/]+)'; }) + '/?$');
    this.routes.push({ method, re, keys, handler });
  }
  get(p, h) { this.add('GET', p, h); }
  post(p, h) { this.add('POST', p, h); }
  put(p, h) { this.add('PUT', p, h); }
  patch(p, h) { this.add('PATCH', p, h); }
  delete(p, h) { this.add('DELETE', p, h); }
  match(method, pathname) {
    let pathMatched = false;
    for (const r of this.routes) {
      const m = r.re.exec(pathname);
      if (!m) continue;
      pathMatched = true;
      if (r.method !== method && !(method === 'HEAD' && r.method === 'GET')) continue;
      const params = {};
      r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
      return { handler: r.handler, params };
    }
    return pathMatched ? { methodNotAllowed: true } : null;
  }
}

// ---------- Database helper (Cloudflare D1) ----------
export function makeDb(D1) {
  const stmt = (sql, args) => D1.prepare(sql).bind(...args.map((a) => (a === undefined ? null : a)));
  return {
    first: (sql, ...args) => stmt(sql, args).first(),
    all: async (sql, ...args) => (await stmt(sql, args).all()).results,
    run: (sql, ...args) => stmt(sql, args).run(),
    /** Runs several statements atomically: [[sql, ...args], ...] */
    batch: (list) => D1.batch(list.map(([sql, ...args]) => stmt(sql, args))),
    lastId: (r) => Number(r.meta.last_row_id),
  };
}

// ---------- Request helpers ----------
export function clientIp(request) {
  return request.headers.get('cf-connecting-ip') || request.headers.get('x-real-ip') || 'unknown';
}
export function parseCookies(request) {
  const out = {};
  for (const part of String(request.headers.get('cookie') || '').split(';')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  }
  return out;
}
export async function readJson(request, limit = 1024 * 1024) {
  const ct = request.headers.get('content-type') || '';
  if (!ct.startsWith('application/json')) throw new HttpError(415, 'Content-Type must be application/json');
  const declared = Number(request.headers.get('content-length') || 0);
  if (declared > limit) throw new HttpError(413, 'Request body too large');
  const text = await request.text();
  if (text.length > limit) throw new HttpError(413, 'Request body too large');
  if (!text) return {};
  try {
    const v = JSON.parse(text);
    if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error();
    return v;
  } catch { throw new HttpError(400, 'Invalid JSON body'); }
}

// ---------- Responses ----------
export function json(status, body, headers = {}) {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers },
  });
}

/** Parses a Range header for an object of `size` bytes. Returns null (whole file), {offset,length}, or 'invalid'. */
export function parseRange(header, size) {
  if (!header) return null;
  const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!m || (m[1] === '' && m[2] === '')) return 'invalid';
  let start, end = size - 1;
  if (m[1] === '') { start = Math.max(0, size - Number(m[2])); }
  else { start = Number(m[1]); if (m[2] !== '') end = Math.min(Number(m[2]), size - 1); }
  if (start > end || start >= size) return 'invalid';
  return { offset: start, length: end - start + 1 };
}

/** Serves an object from R2 with Range (resume) support. */
export async function serveR2(request, bucket, key, { contentType, downloadName, cache = 'no-cache', extraHeaders = {} } = {}) {
  const head = await bucket.head(key);
  if (!head) throw new HttpError(404, 'File not found');
  const etag = head.httpEtag;
  const base = { 'Content-Type': contentType || head.httpMetadata?.contentType || 'application/octet-stream', 'Accept-Ranges': 'bytes', ETag: etag, 'Cache-Control': cache, ...extraHeaders };
  if (downloadName) base['Content-Disposition'] = `attachment; filename="${downloadName.replace(/[^\w.\-]/g, '_')}"`;
  const rangeHeader = request.headers.get('range');
  const ifRange = request.headers.get('if-range');
  if (!rangeHeader && request.headers.get('if-none-match') === etag) return { response: new Response(null, { status: 304, headers: base }), status: 304 };
  let range = rangeHeader && (!ifRange || ifRange === etag) ? parseRange(rangeHeader, head.size) : null;
  if (range === 'invalid') return { response: new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${head.size}` } }), status: 416 };
  if (request.method === 'HEAD') {
    return { response: new Response(null, { status: 200, headers: { ...base, 'Content-Length': String(head.size) } }), status: 200 };
  }
  const obj = await bucket.get(key, range ? { range } : undefined);
  if (!obj) throw new HttpError(404, 'File not found');
  if (range) {
    return {
      response: new Response(obj.body, { status: 206, headers: { ...base, 'Content-Range': `bytes ${range.offset}-${range.offset + range.length - 1}/${head.size}`, 'Content-Length': String(range.length) } }),
      status: 206, start: range.offset,
    };
  }
  return { response: new Response(obj.body, { status: 200, headers: { ...base, 'Content-Length': String(head.size) } }), status: 200, start: 0 };
}

// ---------- Rate limiting ----------
// In-memory limits are per Worker instance (best effort, good against simple floods).
const buckets = new Map();
export function rateLimit(key, limit, windowMs, scale = 1) {
  const now = Date.now();
  let b = buckets.get(key);
  if (!b || b.reset <= now) {
    b = { count: 0, reset: now + windowMs }; buckets.set(key, b);
    if (buckets.size > 5000) for (const [k, v] of buckets) if (v.reset <= now) buckets.delete(k);
  }
  b.count++;
  if (b.count > limit * scale) throw new HttpError(429, 'Too many requests. Please wait and try again.', { retryAfter: Math.ceil((b.reset - now) / 1000) });
}
// Database-backed limit, shared by all Worker instances. Used for sign-in.
export async function rateLimitDb(db, key, limit, windowMs, scale = 1) {
  const now = Date.now();
  const row = await db.first(`INSERT INTO rate_limits (key, count, reset_at) VALUES (?, 1, ?)
    ON CONFLICT(key) DO UPDATE SET count = CASE WHEN reset_at <= ? THEN 1 ELSE count + 1 END,
      reset_at = CASE WHEN reset_at <= ? THEN ? ELSE reset_at END
    RETURNING count, reset_at`, key, now + windowMs, now, now, now + windowMs);
  if (row.count > limit * scale) throw new HttpError(429, 'Too many attempts. Please wait a minute and try again.', { retryAfter: Math.ceil((row.reset_at - now) / 1000) });
}

// ---------- Validation ----------
export function str(v, name, { min = 0, max = 1000, required = true, pattern } = {}) {
  if (v === undefined || v === null || v === '') {
    if (required && min > 0) throw new HttpError(400, `${name} is required`);
    return '';
  }
  if (typeof v !== 'string') throw new HttpError(400, `${name} must be text`);
  const s = v.trim();
  if (s.length < min) throw new HttpError(400, `${name} is too short`);
  if (s.length > max) throw new HttpError(400, `${name} is too long (max ${max})`);
  if (pattern && !pattern.test(s)) throw new HttpError(400, `${name} has an invalid format`);
  return s;
}
export function int(v, name, { min = -Infinity, max = Infinity } = {}) {
  const n = typeof v === 'string' && v.trim() !== '' ? Number(v) : v;
  if (!Number.isInteger(n) || n < min || n > max) throw new HttpError(400, `${name} must be a whole number between ${min} and ${max}`);
  return n;
}
export function num(v, name, { min = -Infinity, max = Infinity } = {}) {
  const n = typeof v === 'string' && v.trim() !== '' ? Number(v) : v;
  if (typeof n !== 'number' || !Number.isFinite(n) || n < min || n > max) throw new HttpError(400, `${name} must be a number between ${min} and ${max}`);
  return n;
}
export function oneOf(v, name, options) {
  if (!options.includes(v)) throw new HttpError(400, `${name} must be one of: ${options.join(', ')}`);
  return v;
}
export const DEVICE_ID_RE = /^[A-Za-z0-9_-]{16,64}$/;
