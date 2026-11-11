// Multi Apps backend for Cloudflare Workers (D1 database + R2 file storage + static admin dashboard).
import { Router, HttpError, json, makeDb, clientIp } from './http.js';
import { registerPublicRoutes } from './routes-public.js';
import { registerAdminRoutes } from './routes-admin.js';

const router = new Router();
registerPublicRoutes(router);
registerAdminRoutes(router);

const ADMIN_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";
const API_CSP = "default-src 'none'; frame-ancestors 'none'";

function readConfig(env, url) {
  return {
    publicUrl: (env.PUBLIC_URL || url.origin).replace(/\/$/, ''),
    publicSiteUrl: (env.PUBLIC_SITE_URL || '').replace(/\/$/, ''),
    cookieSecure: env.COOKIE_SECURE !== 'false',
    corsOrigins: String(env.CORS_ORIGINS || '*').split(',').map((s) => s.trim()).filter(Boolean),
    rateLimitScale: Number(env.RATE_LIMIT_SCALE || 1),
    // Cloudflare's Free and Pro plans accept request bodies up to 100 MB.
    maxApkBytes: Number(env.MAX_APK_MB || 100) * 1048576,
    sessionHours: Number(env.SESSION_HOURS || 12),
    totpIssuer: env.TOTP_ISSUER || 'Multi Apps Admin',
    setupToken: env.SETUP_TOKEN || '',
    pbkdf2Iterations: Number(env.PBKDF2_ITERATIONS || 30000),
  };
}

function withHeaders(response, pathname, cfg, request, setCookies) {
  const r = new Response(response.body, response); // make headers mutable
  const h = r.headers;
  h.set('X-Content-Type-Options', 'nosniff');
  h.set('Referrer-Policy', 'strict-origin-when-cross-origin');
  h.set('X-Frame-Options', 'DENY');
  h.set('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  if (cfg.cookieSecure) h.set('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  h.set('Content-Security-Policy', pathname.startsWith('/admin') ? ADMIN_CSP : API_CSP);
  if (pathname.startsWith('/admin')) h.set('X-Robots-Tag', 'noindex, nofollow');
  for (const c of setCookies) h.append('Set-Cookie', c);
  const origin = request.headers.get('origin');
  const isPublic = pathname.startsWith('/api/v1/public/') || pathname.startsWith('/download/') || pathname.startsWith('/media/');
  if (isPublic && origin && (cfg.corsOrigins.includes('*') || cfg.corsOrigins.includes(origin))) {
    h.set('Access-Control-Allow-Origin', cfg.corsOrigins.includes('*') ? '*' : origin);
    h.append('Vary', 'Origin');
    h.set('Access-Control-Expose-Headers', 'Content-Length, Content-Disposition, X-Checksum-SHA256');
  }
  return r;
}

export default {
  async fetch(request, env, execCtx) {
    const url = new URL(request.url);
    const pathname = url.pathname;
    const cfg = readConfig(env, url);
    const ctx = {
      request, env, cfg, db: makeDb(env.DB), ip: clientIp(request), query: url.searchParams, params: {}, setCookies: [],
      waitUntil: (p) => (execCtx?.waitUntil ? execCtx.waitUntil(p) : p),
    };
    let response;
    try {
      if (request.method === 'OPTIONS') {
        response = new Response(null, { status: 204, headers: { 'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS', 'Access-Control-Max-Age': '86400' } });
      } else {
        const m = router.match(request.method, pathname);
        if (m?.handler) { ctx.params = m.params; response = await m.handler(ctx); }
        else if (m?.methodNotAllowed) throw new HttpError(405, 'Method not allowed');
        else if (pathname.startsWith('/api/') || pathname.startsWith('/admin/api/')) throw new HttpError(404, 'Not found');
        else if (pathname === '/admin') response = Response.redirect(`${url.origin}/admin/`, 301);
        else if (pathname.startsWith('/admin/')) {
          if (!['GET', 'HEAD'].includes(request.method)) throw new HttpError(405, 'Method not allowed');
          response = await env.ASSETS.fetch(request);
        } else if (pathname === '/' && cfg.publicSiteUrl) response = Response.redirect(cfg.publicSiteUrl, 302);
        else if (pathname === '/') response = json(200, { name: 'Multi Apps API', admin: '/admin/' });
        else throw new HttpError(404, 'Not found');
      }
    } catch (err) {
      const status = err instanceof HttpError ? err.status : 500;
      if (status === 500) console.error(request.method, pathname, err && err.stack || err);
      const headers = err?.extra?.retryAfter ? { 'Retry-After': String(err.extra.retryAfter) } : {};
      response = json(status, { error: status === 500 ? 'Internal server error' : err.message }, headers);
    }
    return withHeaders(response, pathname, cfg, request, ctx.setCookies);
  },
};
