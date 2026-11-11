import { rolloutBucket } from './security.js';

export async function getSetting(ctx, key) {
  const row = await ctx.db.first('SELECT value FROM settings WHERE key = ?', key);
  return row ? row.value : null;
}
export const setSettingStmt = (key, value) => [
  'INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value', key, String(value)];

export async function audit(ctx, admin, action, detail) {
  await ctx.db.run('INSERT INTO audit_log (admin_id, username, action, detail, ip, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    admin?.id ?? null, admin?.username ?? null, action, detail ? JSON.stringify(detail) : null, ctx.ip, Date.now());
}

export const DEFAULT_REMOTE_CONFIG = {
  ads: {
    enabled: false,
    provider: 'admob',
    appId: '',
    units: { banner: '', interstitial: '', rewarded: '', native: '' },
    placements: { homeBanner: false, interstitialOnCloneLaunch: false, nativeInCloneList: false },
    frequency: { interstitialMinIntervalSec: 180, maxInterstitialsPerDay: 10, launchesBeforeFirstAd: 3 },
  },
  features: { maxClonesFree: 0, proxyEnabled: true, backupEnabled: true },
  update: { checkIntervalHours: 6 },
  support: { faqUrl: '', contactEmail: '' },
  messages: { announcement: '' },
};

export async function isTester(ctx, deviceId) {
  return !!(await ctx.db.first('SELECT 1 AS x FROM beta_testers WHERE device_id = ?', deviceId));
}

export function publicRelease(ctx, r) {
  return {
    id: r.id, versionCode: r.version_code, versionName: r.version_name, channel: r.channel,
    updateType: r.update_type, changelog: r.changelog, fileSize: r.file_size, sha256: r.sha256,
    publishedAt: r.published_at, downloadUrl: `${ctx.cfg.publicUrl}/download/release/${r.id}`,
  };
}

/**
 * Which update should this device get?
 *  - only 'live' releases with an uploaded APK and a higher versionCode
 *  - beta releases only for testers
 *  - the device must be inside the rollout % (stable hash bucket per device+release)
 *  - target = highest eligible; mandatory if any eligible release is mandatory
 *    or the device is below the target's min supported version code
 */
export async function decideUpdate(ctx, { deviceId, versionCode }) {
  const tester = await isTester(ctx, deviceId);
  const candidates = await ctx.db.all(`SELECT * FROM releases WHERE status = 'live' AND sha256 IS NOT NULL AND version_code > ?
    ORDER BY version_code DESC`, versionCode);
  const eligible = [];
  for (const r of candidates) {
    if ((r.channel === 'stable' || tester) && (await rolloutBucket(deviceId, r.id)) < r.rollout_percent) eligible.push(r);
  }
  const current = await ctx.db.first('SELECT status FROM releases WHERE version_code = ?', versionCode);
  const currentWithdrawn = !!current && current.status === 'rolled_back';
  if (!eligible.length) return { updateAvailable: false, tester, currentWithdrawn };
  const target = eligible[0];
  const mandatory = eligible.some((r) => r.update_type === 'mandatory') || versionCode < target.min_supported_version_code;
  return { updateAvailable: true, mandatory, tester, currentWithdrawn, release: publicRelease(ctx, target) };
}

const isObj = (v) => v && typeof v === 'object' && !Array.isArray(v);
export function deepMerge(base, over) {
  const out = { ...base };
  for (const [k, v] of Object.entries(over || {})) out[k] = isObj(v) && isObj(base?.[k]) ? deepMerge(base[k], v) : v;
  return out;
}
export const latestConfig = (ctx, scope) => ctx.db.first('SELECT * FROM config_versions WHERE scope = ? ORDER BY id DESC LIMIT 1', scope);

/** Effective settings: defaults ← 'all' ← channel scope. */
export async function effectiveConfig(ctx, channel) {
  const all = await latestConfig(ctx, 'all');
  const ch = await latestConfig(ctx, channel);
  let cfg = deepMerge(DEFAULT_REMOTE_CONFIG, all ? JSON.parse(all.json) : {});
  cfg = deepMerge(cfg, ch ? JSON.parse(ch.json) : {});
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(cfg))));
  const etag = `"${[...digest.slice(0, 8)].map((b) => b.toString(16).padStart(2, '0')).join('')}"`;
  return { config: cfg, version: `${all?.id || 0}.${ch?.id || 0}`, etag };
}
