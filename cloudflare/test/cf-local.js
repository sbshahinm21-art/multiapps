// Local stand-ins for Cloudflare D1, R2 and Static Assets, so the Worker can be tested on plain Node.js.
// They follow the documented Cloudflare APIs for the calls this project uses. They are for testing only;
// the real deployment runs on Cloudflare.
import { DatabaseSync } from 'node:sqlite';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { Readable } from 'node:stream';

// ---------------- D1 ----------------
class D1Statement {
  constructor(db, sql, args = []) { this.db = db; this.sql = sql; this.args = args; }
  bind(...args) {
    for (const a of args) {
      if (a === undefined) throw new Error('D1_TYPE_ERROR: Type \'undefined\' not supported for value \'undefined\'');
    }
    return new D1Statement(this.db, this.sql, args.map((a) => (typeof a === 'boolean' ? (a ? 1 : 0) : a)));
  }
  _s() { return this.db.prepare(this.sql); }
  async first(col) {
    const row = this._s().get(...this.args);
    if (!row) return null;
    const plain = { ...row };
    return col ? plain[col] : plain;
  }
  async all() { return { success: true, results: this._s().all(...this.args).map((r) => ({ ...r })), meta: {} }; }
  async run() {
    if (/\bRETURNING\b/i.test(this.sql)) { const results = this._s().all(...this.args).map((r) => ({ ...r })); return { success: true, results, meta: { changes: results.length } }; }
    const r = this._s().run(...this.args);
    return { success: true, meta: { changes: Number(r.changes), last_row_id: Number(r.lastInsertRowid) } };
  }
}
export class LocalD1 {
  constructor(file) { this.db = new DatabaseSync(file); this.db.exec('PRAGMA foreign_keys = ON'); }
  prepare(sql) { return new D1Statement(this.db, sql); }
  exec(sql) { this.db.exec(sql); return { count: 1 }; }
  async batch(stmts) {
    this.db.exec('BEGIN');
    try { const out = []; for (const s of stmts) out.push(await s.run()); this.db.exec('COMMIT'); return out; }
    catch (e) { this.db.exec('ROLLBACK'); throw e; }
  }
}

// ---------------- R2 ----------------
async function toBuffer(value) {
  if (value == null) return Buffer.alloc(0);
  if (typeof value === 'string') return Buffer.from(value);
  if (value instanceof ArrayBuffer) return Buffer.from(value);
  if (ArrayBuffer.isView(value)) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  if (typeof value.getReader === 'function') {
    const chunks = []; const reader = value.getReader();
    for (;;) { const { done, value: v } = await reader.read(); if (done) break; chunks.push(Buffer.from(v)); }
    return Buffer.concat(chunks);
  }
  throw new TypeError('Unsupported R2 put value');
}
export class LocalR2 {
  constructor(dir) { this.dir = dir; fs.mkdirSync(dir, { recursive: true }); }
  _p(key) { return path.join(this.dir, encodeURIComponent(key)); }
  _meta(key) { try { return JSON.parse(fs.readFileSync(this._p(key) + '.meta')); } catch { return null; } }
  async put(key, value, opts = {}) {
    const buf = await toBuffer(value);
    if (opts.sha256) {
      const actual = crypto.createHash('sha256').update(buf).digest('hex');
      if (actual !== String(opts.sha256).toLowerCase()) throw new Error("put: The SHA-256 checksum you specified did not match what we received. (10037)");
    }
    const etag = crypto.createHash('md5').update(buf).digest('hex');
    fs.writeFileSync(this._p(key), buf);
    const meta = { key, size: buf.length, etag, httpEtag: `"${etag}"`, httpMetadata: opts.httpMetadata || {}, uploaded: new Date().toISOString() };
    fs.writeFileSync(this._p(key) + '.meta', JSON.stringify(meta));
    return meta;
  }
  async head(key) { return this._meta(key); }
  async get(key, opts = {}) {
    const meta = this._meta(key);
    if (!meta) return null;
    let buf = fs.readFileSync(this._p(key));
    let range;
    if (opts.range) {
      const { offset = 0, length, suffix } = opts.range;
      if (suffix !== undefined) buf = buf.subarray(buf.length - suffix);
      else buf = buf.subarray(offset, length !== undefined ? offset + length : undefined);
      range = opts.range;
    }
    const body = Readable.toWeb(Readable.from([buf]));
    return { ...meta, range, body, arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.length) };
  }
  async delete(key) { fs.rmSync(this._p(key), { force: true }); fs.rmSync(this._p(key) + '.meta', { force: true }); }
}

// ---------------- Static assets ----------------
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png' };
export class LocalAssets {
  constructor(dir) { this.dir = path.resolve(dir); }
  async fetch(request) {
    let p = decodeURIComponent(new URL(request.url).pathname);
    let full = path.resolve(this.dir, '.' + path.posix.normalize(p));
    if (!full.startsWith(this.dir)) return new Response('Not found', { status: 404 });
    if (fs.existsSync(full) && fs.statSync(full).isDirectory()) full = path.join(full, 'index.html');
    if (!fs.existsSync(full)) return new Response('Not found', { status: 404 });
    return new Response(fs.readFileSync(full), { headers: { 'Content-Type': MIME[path.extname(full)] || 'application/octet-stream' } });
  }
}

/** Starts a Node HTTP server that hands every request to the Worker's fetch(). */
export async function startLocal({ dataDir, vars = {}, port = 0 }) {
  fs.mkdirSync(dataDir, { recursive: true });
  const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
  const D1 = new LocalD1(path.join(dataDir, 'd1.sqlite'));
  const migrated = D1.db.prepare("SELECT name FROM sqlite_master WHERE name = 'admins'").get();
  if (!migrated) D1.exec(fs.readFileSync(path.join(root, 'migrations/0001_init.sql'), 'utf8'));
  const env = { DB: D1, BUCKET: new LocalR2(path.join(dataDir, 'r2')), ASSETS: new LocalAssets(path.join(root, 'assets')), ...vars };
  const worker = (await import('../src/index.js')).default;
  const pending = new Set();
  const server = http.createServer(async (req, res) => {
    try {
      const url = `http://${req.headers.host}${req.url}`;
      const hasBody = !['GET', 'HEAD'].includes(req.method);
      const headers = new Headers();
      for (const [k, v] of Object.entries(req.headers)) headers.set(k, Array.isArray(v) ? v.join(', ') : v);
      if (!headers.has('cf-connecting-ip')) headers.set('cf-connecting-ip', req.socket.remoteAddress);
      const request = new Request(url, { method: req.method, headers, body: hasBody ? Readable.toWeb(req) : undefined, duplex: 'half' });
      const response = await worker.fetch(request, env, { waitUntil: (p) => { pending.add(p); p.finally(() => pending.delete(p)); } });
      const out = {};
      response.headers.forEach((v, k) => { if (k !== 'set-cookie') out[k] = v; });
      const cookies = response.headers.getSetCookie();
      if (cookies.length) out['set-cookie'] = cookies;
      res.writeHead(response.status, out);
      if (response.body && req.method !== 'HEAD') Readable.fromWeb(response.body).pipe(res); else res.end();
    } catch (e) { console.error(e); res.writeHead(500); res.end(); }
  });
  await new Promise((r) => server.listen(port, '127.0.0.1', r));
  return { server, env, url: `http://127.0.0.1:${server.address().port}`, settle: () => Promise.all([...pending]) };
}
