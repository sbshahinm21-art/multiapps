-- Multi Apps database schema (Cloudflare D1)
CREATE TABLE IF NOT EXISTS admins (
  id INTEGER PRIMARY KEY,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  totp_secret TEXT,
  totp_enabled INTEGER NOT NULL DEFAULT 0,
  totp_last_step INTEGER NOT NULL DEFAULT 0,
  recovery_codes TEXT NOT NULL DEFAULT '[]',
  failed_attempts INTEGER NOT NULL DEFAULT 0,
  locked_until INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  last_login_at INTEGER
);
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  admin_id INTEGER NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
  stage TEXT NOT NULL,
  csrf TEXT NOT NULL,
  pending_totp_secret TEXT,
  ip TEXT, ua TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS releases (
  id INTEGER PRIMARY KEY,
  version_code INTEGER NOT NULL UNIQUE,
  version_name TEXT NOT NULL,
  channel TEXT NOT NULL CHECK (channel IN ('stable','beta')),
  status TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','live','halted','rolled_back')),
  update_type TEXT NOT NULL DEFAULT 'optional' CHECK (update_type IN ('optional','mandatory')),
  rollout_percent REAL NOT NULL DEFAULT 100 CHECK (rollout_percent >= 0 AND rollout_percent <= 100),
  min_supported_version_code INTEGER NOT NULL DEFAULT 0,
  changelog TEXT NOT NULL DEFAULT '',
  file_key TEXT, file_size INTEGER, sha256 TEXT,
  download_count INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  published_at INTEGER,
  status_changed_at INTEGER,
  created_by INTEGER REFERENCES admins(id)
);
CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  version_code INTEGER,
  channel TEXT,
  model TEXT,
  sdk INTEGER,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS beta_testers (
  device_id TEXT PRIMARY KEY,
  label TEXT NOT NULL DEFAULT '',
  source TEXT NOT NULL DEFAULT 'admin',
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS update_events (
  id INTEGER PRIMARY KEY,
  device_id TEXT NOT NULL,
  release_id INTEGER,
  version_code INTEGER,
  event TEXT NOT NULL,
  detail TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_release ON update_events(release_id, event);
CREATE INDEX IF NOT EXISTS idx_events_time ON update_events(created_at);
CREATE INDEX IF NOT EXISTS idx_devices_seen ON devices(last_seen);
CREATE TABLE IF NOT EXISTS config_versions (
  id INTEGER PRIMARY KEY,
  scope TEXT NOT NULL CHECK (scope IN ('all','stable','beta')),
  json TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  created_by INTEGER REFERENCES admins(id)
);
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS screenshots (
  id INTEGER PRIMARY KEY,
  file_name TEXT NOT NULL,
  mime TEXT NOT NULL,
  caption TEXT NOT NULL DEFAULT '',
  sort INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS support_tickets (
  id INTEGER PRIMARY KEY,
  device_id TEXT NOT NULL,
  access_token TEXT NOT NULL,
  email TEXT NOT NULL DEFAULT '',
  category TEXT NOT NULL,
  subject TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','answered','closed')),
  app_version TEXT, device_info TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS support_messages (
  id INTEGER PRIMARY KEY,
  ticket_id INTEGER NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
  sender TEXT NOT NULL CHECK (sender IN ('user','admin')),
  body TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY,
  admin_id INTEGER,
  username TEXT,
  action TEXT NOT NULL,
  detail TEXT,
  ip TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS rate_limits (
  key TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  reset_at INTEGER NOT NULL
);

INSERT OR IGNORE INTO settings (key, value) VALUES
  ('site.app_name', 'Multi Apps'),
  ('site.tagline', 'Run multiple accounts of your favorite apps on one phone.'),
  ('site.description', 'Multi Apps lets you create separate clones of the apps you use, each with its own data, notifications and optional proxy.'),
  ('site.features', '[{"title":"Unlimited clones","text":"Run several copies of the same app, each fully separated."},{"title":"Per-app proxy","text":"Route each clone through its own proxy, or none at all."},{"title":"Smart resources","text":"Idle clones are paused to save battery and memory."},{"title":"Encrypted backup","text":"Back up your clone setup with a password-protected file."}]'),
  ('site.support_email', ''),
  ('beta.join_code', '');
