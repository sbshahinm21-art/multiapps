'use strict';
// Multi Apps Admin Dashboard — vanilla JS, no build step. All server data is escaped before rendering.

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtDate = (ms) => (ms ? new Date(ms).toLocaleString() : '—');
const fmtSize = (b) => (b == null ? '—' : b < 1048576 ? `${(b / 1024).toFixed(1)} KB` : `${(b / 1048576).toFixed(1)} MB`);
const STATUS_LABEL = { draft: 'Draft', live: 'Live', halted: 'Halted', rolled_back: 'Rolled back' };

const state = { csrf: '', username: '', view: 'overview' };

// ---------------- API ----------------
async function api(method, path, body, { raw = false } = {}) {
  const headers = {};
  if (state.csrf) headers['X-CSRF-Token'] = state.csrf;
  let payload;
  if (body instanceof Blob) { payload = body; headers['Content-Type'] = 'application/octet-stream'; }
  else if (body !== undefined) { payload = JSON.stringify(body); headers['Content-Type'] = 'application/json'; }
  const res = await fetch(path, { method, headers, body: payload, credentials: 'same-origin' });
  const data = res.headers.get('content-type')?.includes('json') ? await res.json() : null;
  if (res.status === 401 && path.startsWith('/admin/api/') && !path.includes('/login') && !path.includes('/2fa/')) {
    showAuth(); throw new Error(data?.error || 'Session ended. Please sign in again.');
  }
  if (!res.ok) throw new Error(data?.error || `Request failed (${res.status})`);
  if (data?.csrf) state.csrf = data.csrf;
  return raw ? res : data;
}

/** Upload with progress (fetch has no upload progress). */
function upload(method, path, file, onProgress, extraHeaders = {}) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(method, path);
    xhr.setRequestHeader('X-CSRF-Token', state.csrf);
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    for (const [k, v] of Object.entries(extraHeaders)) xhr.setRequestHeader(k, v);
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress?.(e.loaded / e.total);
    xhr.onload = () => {
      let data = null; try { data = JSON.parse(xhr.responseText); } catch { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data); else reject(new Error(data?.error || `Upload failed (${xhr.status})`));
    };
    xhr.onerror = () => reject(new Error('Network error during upload'));
    xhr.send(file);
  });
}

/** SHA-256 of a file, computed in the browser. The server's storage rejects the upload if the bytes change on the way. */
async function sha256File(file) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
async function uploadApk(releaseId, file, onProgress) {
  onProgress?.(0, 'Checking file…');
  const sha = await sha256File(file);
  return upload('PUT', `/admin/api/releases/${releaseId}/file`, file, (p) => onProgress?.(p, `Uploading… ${Math.round(p * 100)}%`), { 'X-Content-SHA256': sha });
}

let toastTimer;
function toast(msg, kind = 'good') {
  const t = $('#toast');
  t.textContent = msg; t.className = `toast ${kind}`;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.add('hidden'), 4000);
}
const fail = (e) => toast(e.message || String(e), 'bad');

function modal(html, bind) {
  $('#modal-card').innerHTML = html;
  $('#modal').classList.remove('hidden');
  bind?.($('#modal-card'));
}
const closeModal = () => $('#modal').classList.add('hidden');
$('#modal').addEventListener('click', (e) => { if (e.target.id === 'modal' || e.target.dataset.close !== undefined) closeModal(); });

// ---------------- Authentication ----------------
function showAuth() {
  $('#shell').classList.add('hidden');
  $('#auth').classList.remove('hidden');
  renderLogin();
}

function renderSetup() {
  $('#auth-body').innerHTML = `
    <form id="setup-admin">
      <h2>Create the first admin</h2>
      <p class="muted">Enter the setup token you saved as the <code>SETUP_TOKEN</code> secret, then choose your username and password.</p>
      <div class="field"><label for="st">Setup token</label><input id="st" type="password" autocomplete="off" required></div>
      <div class="field"><label for="su">Username</label><input id="su" autocomplete="username" required></div>
      <div class="field"><label for="sp">Password</label><input id="sp" type="password" autocomplete="new-password" minlength="12" required>
        <div class="hint">At least 12 characters, with three of: lowercase, uppercase, numbers, symbols.</div></div>
      <div class="field"><label for="sp2">Repeat password</label><input id="sp2" type="password" autocomplete="new-password" required></div>
      <button class="btn block" type="submit">Create admin</button>
      <div class="error-text" id="auth-err"></div>
    </form>`;
  $('#setup-admin').onsubmit = async (e) => {
    e.preventDefault();
    if ($('#sp').value !== $('#sp2').value) { $('#auth-err').textContent = 'Passwords do not match'; return; }
    const username = $('#su').value;
    try {
      await api('POST', '/admin/api/setup', { setupToken: $('#st').value, username, password: $('#sp').value });
      toast('Admin created. Now sign in and set up two-factor authentication.');
      renderLogin();
      $('#u').value = username; $('#p').focus();
    } catch (err) { $('#auth-err').textContent = err.message; }
  };
}

function renderLogin() {
  $('#auth-body').innerHTML = `
    <form id="login-form">
      <div class="field"><label for="u">Username</label><input id="u" autocomplete="username" required></div>
      <div class="field"><label for="p">Password</label><input id="p" type="password" autocomplete="current-password" required></div>
      <button class="btn block" type="submit">Sign in</button>
      <div class="error-text" id="auth-err"></div>
    </form>`;
  $('#u').focus();
  $('#login-form').onsubmit = async (e) => {
    e.preventDefault();
    try {
      const r = await api('POST', '/admin/api/login', { username: $('#u').value, password: $('#p').value });
      state.username = $('#u').value;
      if (r.next === 'totp_setup') renderTotpSetup(); else renderTotp();
    } catch (err) { $('#auth-err').textContent = err.message; }
  };
}

function renderTotp() {
  $('#auth-body').innerHTML = `
    <form id="totp-form">
      <h2>Two-factor authentication</h2>
      <p class="muted">Enter the 6-digit code from your authenticator app.</p>
      <div class="field"><input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" placeholder="123456" required></div>
      <button class="btn block" type="submit">Verify</button>
      <div class="error-text" id="auth-err"></div>
      <p><a id="use-recovery">Lost your phone? Use a recovery code</a></p>
    </form>`;
  $('#code').focus();
  $('#totp-form').onsubmit = async (e) => {
    e.preventDefault();
    try { await api('POST', '/admin/api/2fa/verify', { code: $('#code').value }); enterApp(); }
    catch (err) { $('#auth-err').textContent = err.message; }
  };
  $('#use-recovery').onclick = renderRecovery;
}

function renderRecovery() {
  $('#auth-body').innerHTML = `
    <form id="rec-form">
      <h2>Use a recovery code</h2>
      <p class="muted">Each recovery code works only once.</p>
      <div class="field"><input id="rcode" placeholder="XXXXX-XXXXX" required></div>
      <button class="btn block" type="submit">Sign in</button>
      <div class="error-text" id="auth-err"></div>
    </form>`;
  $('#rec-form').onsubmit = async (e) => {
    e.preventDefault();
    try {
      const r = await api('POST', '/admin/api/2fa/recovery', { code: $('#rcode').value });
      await enterApp();
      toast(`Signed in. ${r.remainingRecoveryCodes} recovery codes left — create new ones in Account & Security.`, r.remainingRecoveryCodes < 3 ? 'bad' : 'good');
    } catch (err) { $('#auth-err').textContent = err.message; }
  };
}

async function renderTotpSetup() {
  let setup;
  try { setup = await api('POST', '/admin/api/2fa/setup'); } catch (e) { $('#auth-body').innerHTML = `<p class="error-text">${esc(e.message)}</p>`; return; }
  const grouped = setup.secret.match(/.{1,4}/g).join(' ');
  $('#auth-body').innerHTML = `
    <h2>Set up two-factor authentication</h2>
    <p class="muted">Two-factor authentication is required for every admin.</p>
    <ol class="muted" style="padding-left:18px">
      <li>Open Google Authenticator, Authy or 1Password.</li>
      <li>Choose <b>Enter a setup key</b> and paste this key (type: time based):</li>
    </ol>
    <div class="secret" id="secret">${esc(grouped)}</div>
    <div class="row"><button class="btn ghost small" id="copy-secret" type="button">Copy key</button>
      <a class="btn ghost small" href="${esc(setup.otpauthUri)}">Open in authenticator app</a></div>
    <form id="setup-form" style="margin-top:16px">
      <div class="field"><label for="code">3. Enter the 6-digit code shown in the app</label>
        <input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" required></div>
      <button class="btn block" type="submit">Turn on two-factor authentication</button>
      <div class="error-text" id="auth-err"></div>
    </form>`;
  $('#copy-secret').onclick = () => navigator.clipboard.writeText(setup.secret).then(() => toast('Key copied'));
  $('#setup-form').onsubmit = async (e) => {
    e.preventDefault();
    try {
      const r = await api('POST', '/admin/api/2fa/verify', { code: $('#code').value });
      showRecoveryCodes(r.recoveryCodes, true);
    } catch (err) { $('#auth-err').textContent = err.message; }
  };
}

function showRecoveryCodes(codes, thenEnter) {
  const html = `
    <h2>Save your recovery codes</h2>
    <p class="muted">If you lose your phone, each of these codes lets you sign in once. Store them somewhere safe. They will not be shown again.</p>
    <div class="codes">${codes.map((c) => `<span>${esc(c)}</span>`).join('')}</div>
    <div class="row"><button class="btn ghost small" id="copy-codes" type="button">Copy all</button>
      <button class="btn ghost small" id="dl-codes" type="button">Download .txt</button></div>
    <label class="check" style="margin-top:14px"><input type="checkbox" id="saved"> I saved these codes</label>
    <button class="btn block" id="codes-done" disabled>Continue</button>`;
  const bind = (root) => {
    $('#copy-codes', root).onclick = () => navigator.clipboard.writeText(codes.join('\n')).then(() => toast('Codes copied'));
    $('#dl-codes', root).onclick = () => {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([`Multi Apps admin recovery codes\n\n${codes.join('\n')}\n`], { type: 'text/plain' }));
      a.download = 'multi-apps-recovery-codes.txt'; a.click();
    };
    $('#saved', root).onchange = (e) => { $('#codes-done', root).disabled = !e.target.checked; };
    $('#codes-done', root).onclick = () => { if (thenEnter) enterApp(); else closeModal(); };
  };
  if (thenEnter) { $('#auth-body').innerHTML = html; bind($('#auth-body')); } else modal(html, bind);
}

async function enterApp() {
  const s = await api('GET', '/admin/api/session');
  if (s.stage !== 'full') return showAuth();
  state.csrf = s.csrf; state.username = s.username;
  $('#auth').classList.add('hidden');
  $('#shell').classList.remove('hidden');
  $('#whoami').textContent = s.username;
  navigate(location.hash.slice(1) || 'overview');
}

$('#logout').onclick = async () => { try { await api('POST', '/admin/api/logout'); } catch { /* ignore */ } state.csrf = ''; showAuth(); };
$('#menu-btn').onclick = () => $('#sidebar').classList.toggle('open');
$('#nav').addEventListener('click', (e) => { const a = e.target.closest('a[data-view]'); if (a) { location.hash = a.dataset.view; $('#sidebar').classList.remove('open'); } });
window.addEventListener('hashchange', () => !$('#shell').classList.contains('hidden') && navigate(location.hash.slice(1)));

// ---------------- Router ----------------
const VIEWS = {};
const TITLES = { overview: 'Overview', releases: 'Release Center', testers: 'Beta Testers', stats: 'Update Statistics',
  config: 'Remote Settings & Ads', site: 'Website Content', support: 'Support Tickets', audit: 'Audit Log', account: 'Account & Security' };

async function navigate(view) {
  if (!VIEWS[view]) view = 'overview';
  state.view = view;
  $$('#nav a').forEach((a) => a.classList.toggle('active', a.dataset.view === view));
  $('#view-title').textContent = TITLES[view];
  // Replace the container so listeners from the previous view are dropped.
  const fresh = $('#view').cloneNode(false); $('#view').replaceWith(fresh);
  $('#view').innerHTML = '<div class="empty">Loading…</div>';
  try { await VIEWS[view]($('#view')); } catch (e) { $('#view').innerHTML = `<div class="card error-text">${esc(e.message)}</div>`; }
}
const refresh = () => navigate(state.view);

// ---------------- Overview ----------------
VIEWS.overview = async (root) => {
  const [s, r] = await Promise.all([api('GET', '/admin/api/stats'), api('GET', '/admin/api/releases')]);
  const t = s.totals;
  const latestLive = r.releases.find((x) => x.status === 'live' && x.channel === 'stable');
  const latestBeta = r.releases.find((x) => x.status === 'live' && x.channel === 'beta');
  root.innerHTML = `
    <div class="grid cols-4">
      ${stat('Active today', t.active1d)} ${stat('Active 7 days', t.active7d)} ${stat('Active 30 days', t.active30d)}
      ${stat('All devices', t.devices)} ${stat('Beta testers', t.testers)} ${stat('Open tickets', t.openTickets)}
    </div>
    <div class="grid cols-2" style="margin-top:16px">
      <div class="card"><h2>Current releases</h2>
        ${relLine('Stable', latestLive)} ${relLine('Beta', latestBeta)}
        <div class="row" style="margin-top:12px"><a class="btn small" href="#releases">Open Release Center</a></div>
      </div>
      <div class="card"><h2>Versions in use (30 days)</h2>${versionBars(s.versionDistribution)}</div>
    </div>
    <div class="card"><h2>Update checks per day (14 days)</h2>${dailyBars(s.daily)}</div>`;
};
const stat = (l, v) => `<div class="stat"><div class="l">${esc(l)}</div><div class="v">${esc(v)}</div></div>`;
const relLine = (label, r) => `<div class="row" style="margin-bottom:8px"><b style="width:60px">${label}</b>${r
  ? `<span>${esc(r.versionName)} <small>(${r.versionCode})</small></span><span class="badge live">${r.rolloutPercent}% rollout</span>${r.updateType === 'mandatory' ? '<span class="badge mandatory">Mandatory</span>' : ''}`
  : '<span class="muted">No live release</span>'}</div>`;
function versionBars(list) {
  if (!list.length) return '<div class="empty">No devices have checked in yet.</div>';
  const max = Math.max(...list.map((v) => v.devices));
  return list.map((v) => `<div class="hbar"><span class="name">${esc(v.versionName || 'Unknown')} <small>(${esc(v.versionCode)})</small></span>
    <span class="track"><i style="width:${(v.devices / max) * 100}%"></i></span><b>${v.devices}</b></div>`).join('');
}
function dailyBars(days) {
  const max = Math.max(1, ...days.map((d) => d.checks));
  return `<div class="bars">${days.map((d) => `<div class="b" title="${esc(d.date)}: ${d.checks} checks, ${d.installs} installs">
    <small>${d.checks}</small><i style="height:${(d.checks / max) * 100}%"></i><small>${esc(d.date.slice(5))}</small></div>`).join('')}</div>`;
}

// ---------------- Release Center ----------------
VIEWS.releases = async (root) => {
  const { releases } = await api('GET', '/admin/api/releases');
  const nextCode = (releases[0]?.versionCode || 0) + 1;
  root.innerHTML = `
    <div class="card">
      <div class="row between"><h2>New release</h2><button class="btn ghost small" id="toggle-new">Show form</button></div>
      <form id="new-rel" class="hidden">
        <div class="fields">
          <div class="field"><label>Version code</label><input name="versionCode" type="number" min="1" value="${nextCode}" required>
            <div class="hint">Must match <code>versionCode</code> in the APK and be higher than ${nextCode - 1}.</div></div>
          <div class="field"><label>Version name</label><input name="versionName" placeholder="1.2.0" required></div>
          <div class="field"><label>Channel</label><select name="channel"><option value="stable">Stable (everyone)</option><option value="beta">Beta (testers only)</option></select></div>
          <div class="field"><label>Update type</label><select name="updateType"><option value="optional">Optional</option><option value="mandatory">Mandatory (blocks the app)</option></select></div>
          <div class="field"><label>Rollout %</label><input name="rolloutPercent" type="number" min="0" max="100" step="0.1" value="100"></div>
          <div class="field"><label>Min supported version code</label><input name="minSupportedVersionCode" type="number" min="0" value="0">
            <div class="hint">Devices below this are forced to update. 0 = off.</div></div>
        </div>
        <div class="field"><label>Changelog (shown in the app and on the website)</label><textarea name="changelog" placeholder="• New: …&#10;• Fixed: …"></textarea></div>
        <div class="field"><label>APK file</label><input name="file" type="file" accept=".apk,application/vnd.android.package-archive" required></div>
        <div class="progress hidden" id="up-prog"><div></div></div>
        <div class="row"><button class="btn" type="submit" id="new-submit">Upload as draft</button><span class="muted" id="up-status"></span></div>
        <div class="notice info">The release is saved as a <b>draft</b>. Nothing is sent to users until you press <b>Publish</b>.</div>
      </form>
    </div>
    <div id="rel-list">${releases.length ? releases.map(releaseCard).join('') : '<div class="card empty">No releases yet. Upload your first APK above.</div>'}</div>`;

  $('#toggle-new').onclick = (e) => { $('#new-rel').classList.toggle('hidden'); e.target.textContent = $('#new-rel').classList.contains('hidden') ? 'Show form' : 'Hide form'; };
  $('#new-rel').onsubmit = async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    const file = f.get('file');
    if (!file || !file.size) return toast('Choose an APK file', 'bad');
    if (!file.name.toLowerCase().endsWith('.apk')) return toast('The file must end with .apk', 'bad');
    const btn = $('#new-submit'); btn.disabled = true;
    let created;
    try {
      created = (await api('POST', '/admin/api/releases', {
        versionCode: Number(f.get('versionCode')), versionName: f.get('versionName'), channel: f.get('channel'), updateType: f.get('updateType'),
        rolloutPercent: Number(f.get('rolloutPercent')), minSupportedVersionCode: Number(f.get('minSupportedVersionCode')), changelog: f.get('changelog'),
      })).release;
      $('#up-prog').classList.remove('hidden');
      await uploadApk(created.id, file, (p, label) => {
        $('#up-prog > div').style.width = `${Math.round(p * 100)}%`; $('#up-status').textContent = label;
      });
      toast(`Draft ${created.versionName} uploaded. Review it, then press Publish.`);
      refresh();
    } catch (err) {
      fail(err);
      if (created) { $('#up-status').textContent = 'The draft was created but the upload failed. Use "Replace APK" on the draft below.'; refresh(); }
    } finally { btn.disabled = false; }
  };

  $('#rel-list').addEventListener('click', onReleaseAction);
  $('#rel-list').addEventListener('input', (e) => {
    if (e.target.matches('input[type=range][data-rollout]')) $(`input.pct[data-rollout="${e.target.dataset.rollout}"]`).value = e.target.value;
    if (e.target.matches('input.pct[data-rollout]')) $(`input[type=range][data-rollout="${e.target.dataset.rollout}"]`).value = e.target.value;
  });
  $('#rel-list').addEventListener('change', async (e) => {
    const inp = e.target.closest('input[data-replace]');
    if (!inp || !inp.files[0]) return;
    try { await uploadApk(inp.dataset.replace, inp.files[0], (p, label) => { inp.previousElementSibling.textContent = label; }); toast('APK replaced'); refresh(); }
    catch (err) { fail(err); }
  });
};

function releaseCard(r) {
  const canRollout = r.status === 'live' || r.status === 'halted' || r.status === 'draft';
  const actions = [];
  if (r.status === 'draft') {
    actions.push(`<button class="btn small" data-act="publish" data-id="${r.id}" ${r.hasFile ? '' : 'disabled title="Upload the APK first"'}>Publish</button>`);
    actions.push(`<label class="btn ghost small" style="margin:0"><span>Replace APK</span><input type="file" accept=".apk" data-replace="${r.id}" class="hidden"></label>`);
    actions.push(`<button class="btn danger small" data-act="delete" data-id="${r.id}">Delete draft</button>`);
  }
  if (r.status === 'live') actions.push(`<button class="btn warn small" data-act="halt" data-id="${r.id}">Halt rollout</button>`);
  if (r.status === 'halted') actions.push(`<button class="btn small" data-act="resume" data-id="${r.id}">Resume rollout</button>`);
  if (r.status === 'live' || r.status === 'halted') actions.push(`<button class="btn danger small" data-act="rollback" data-id="${r.id}" data-code="${r.versionCode}">Roll back</button>`);
  if (r.channel === 'beta' && r.status !== 'rolled_back') actions.push(`<button class="btn ghost small" data-act="promote" data-id="${r.id}">Promote to stable</button>`);
  if (r.status !== 'rolled_back') actions.push(`<button class="btn ghost small" data-act="edit" data-id="${r.id}">Edit details</button>`);
  return `<div class="release" data-release='${esc(JSON.stringify(r))}'>
    <div class="release-head">
      <span class="ver">${esc(r.versionName)}</span><small>code ${r.versionCode}</small>
      <span class="badge ${r.status}">${STATUS_LABEL[r.status]}</span><span class="badge ${r.channel}">${r.channel === 'beta' ? 'Beta' : 'Stable'}</span>
      ${r.updateType === 'mandatory' ? '<span class="badge mandatory">Mandatory</span>' : '<span class="badge draft">Optional</span>'}
    </div>
    <div class="release-meta">
      <div><span>APK</span>${r.hasFile ? fmtSize(r.fileSize) : '<b style="color:var(--bad)">Not uploaded</b>'}</div>
      <div><span>Downloads</span>${r.downloadCount}</div>
      <div><span>Min supported</span>${r.minSupportedVersionCode || 'Off'}</div>
      <div><span>Created</span>${fmtDate(r.createdAt)}</div>
      <div><span>Published</span>${fmtDate(r.publishedAt)}</div>
    </div>
    ${r.sha256 ? `<div class="hint">SHA-256: <span class="mono">${esc(r.sha256)}</span></div>` : ''}
    ${r.changelog ? `<div class="changelog">${esc(r.changelog)}</div>` : ''}
    ${canRollout ? `<div class="rollout" style="margin:10px 0">
      <label style="margin:0;width:70px">Rollout</label>
      <input type="range" min="0" max="100" step="1" value="${r.rolloutPercent}" data-rollout="${r.id}">
      <input class="pct" type="number" min="0" max="100" step="0.1" value="${r.rolloutPercent}" data-rollout="${r.id}">
      <button class="btn ghost small" data-act="rollout" data-id="${r.id}">Save</button></div>` : ''}
    ${r.status === 'rolled_back' ? '<div class="notice">Withdrawn. No device can receive this build. Devices that already installed it are told a fix is coming — publish a new build with a higher version code to move them off it.</div>' : ''}
    <div class="row">${actions.join('')}</div>
  </div>`;
}

async function onReleaseAction(e) {
  const b = e.target.closest('button[data-act]');
  if (!b) return;
  const id = b.dataset.id;
  const r = JSON.parse(b.closest('.release').dataset.release);
  try {
    switch (b.dataset.act) {
      case 'publish':
        confirmModal(`Publish ${r.versionName}?`, `${r.channel === 'beta' ? 'Beta testers' : 'Users'} inside the ${r.rolloutPercent}% rollout will be offered this update${r.updateType === 'mandatory' ? ' and will be <b>forced</b> to install it' : ''}.`, 'Publish', async () => {
          await api('POST', `/admin/api/releases/${id}/publish`); toast(`${r.versionName} is live`); refresh();
        });
        break;
      case 'halt':
        confirmModal(`Halt ${r.versionName}?`, 'No new devices will be offered this update until you resume. Devices that already updated are not affected.', 'Halt rollout', async () => {
          await api('POST', `/admin/api/releases/${id}/halt`); toast('Rollout halted'); refresh();
        }, 'warn');
        break;
      case 'resume':
        await api('POST', `/admin/api/releases/${id}/resume`); toast('Rollout resumed'); refresh();
        break;
      case 'rollout': {
        const pct = Number($(`input.pct[data-rollout="${id}"]`).value);
        await api('PATCH', `/admin/api/releases/${id}`, { rolloutPercent: pct });
        toast(`Rollout set to ${pct}%${pct < r.rolloutPercent ? '. Devices that already updated keep this version.' : ''}`); refresh();
        break;
      }
      case 'promote':
        confirmModal(`Promote ${r.versionName} to stable?`, 'Everyone inside the rollout percentage will be offered this build. This cannot be moved back to beta.', 'Promote', async () => {
          await api('PATCH', `/admin/api/releases/${id}`, { channel: 'stable' }); toast('Promoted to stable'); refresh();
        });
        break;
      case 'delete':
        confirmModal(`Delete draft ${r.versionName}?`, 'The draft and its APK file will be removed.', 'Delete', async () => {
          await api('DELETE', `/admin/api/releases/${id}`); toast('Draft deleted'); refresh();
        }, 'danger');
        break;
      case 'rollback':
        modal(`<h2>Roll back ${esc(r.versionName)}?</h2>
          <p>This permanently withdraws the release. It stops being offered and downloaded immediately, and devices fall back to the previous live release as their update target.</p>
          <div class="notice">Android cannot install a lower version code over a higher one. Devices that already installed ${esc(r.versionName)} stay on it and are told a fix is coming. To move them off it, rebuild the previous good code with a <b>higher</b> version code and publish that.</div>
          <div class="field"><label>Type the version code <b>${r.versionCode}</b> to confirm</label><input id="rb-code" inputmode="numeric"></div>
          <div class="row"><button class="btn danger" id="rb-go">Roll back</button><button class="btn ghost" data-close>Cancel</button></div>`, (root) => {
          $('#rb-go', root).onclick = async () => {
            try {
              const res = await api('POST', `/admin/api/releases/${id}/rollback`, { confirmVersionCode: Number($('#rb-code', root).value) });
              closeModal();
              toast(`Rolled back. ${res.fallback ? `Devices now get ${res.fallback.versionName}.` : 'No older live release to fall back to.'} ${res.devicesAlreadyOnThisVersion} active devices already had it.`);
              refresh();
            } catch (err) { fail(err); }
          };
        });
        break;
      case 'edit':
        modal(`<h2>Edit ${esc(r.versionName)}</h2>
          <form id="edit-form">
            ${r.status === 'draft' ? `<div class="field"><label>Version name</label><input name="versionName" value="${esc(r.versionName)}"></div>` : ''}
            <div class="fields">
              <div class="field"><label>Update type</label><select name="updateType"><option value="optional" ${r.updateType === 'optional' ? 'selected' : ''}>Optional</option><option value="mandatory" ${r.updateType === 'mandatory' ? 'selected' : ''}>Mandatory</option></select></div>
              <div class="field"><label>Min supported version code</label><input name="minSupportedVersionCode" type="number" min="0" max="${r.versionCode}" value="${r.minSupportedVersionCode}"></div>
            </div>
            ${r.status === 'draft' ? `<div class="field"><label>Channel</label><select name="channel"><option value="stable" ${r.channel === 'stable' ? 'selected' : ''}>Stable</option><option value="beta" ${r.channel === 'beta' ? 'selected' : ''}>Beta</option></select></div>` : ''}
            <div class="field"><label>Changelog</label><textarea name="changelog">${esc(r.changelog)}</textarea></div>
            <div class="row"><button class="btn" type="submit">Save</button><button class="btn ghost" type="button" data-close>Cancel</button></div>
          </form>`, (root) => {
          $('#edit-form', root).onsubmit = async (ev) => {
            ev.preventDefault();
            const f = Object.fromEntries(new FormData(ev.target));
            f.minSupportedVersionCode = Number(f.minSupportedVersionCode);
            try { await api('PATCH', `/admin/api/releases/${id}`, f); closeModal(); toast('Saved'); refresh(); } catch (err) { fail(err); }
          };
        });
        break;
      default:
    }
  } catch (err) { fail(err); }
}

function confirmModal(title, text, label, fn, kind = '') {
  modal(`<h2>${esc(title)}</h2><p>${text}</p><div class="row"><button class="btn ${kind}" id="cf-go">${esc(label)}</button><button class="btn ghost" data-close>Cancel</button></div>`, (root) => {
    $('#cf-go', root).onclick = async (e) => { e.target.disabled = true; try { await fn(); closeModal(); } catch (err) { fail(err); e.target.disabled = false; } };
  });
}

// ---------------- Beta testers ----------------
VIEWS.testers = async (root) => {
  const { testers, joinCode } = await api('GET', '/admin/api/testers');
  root.innerHTML = `
    <div class="grid cols-2">
      <div class="card"><h2>Add a tester</h2>
        <p class="muted">In the app: <b>Settings → About → Device ID</b> (tap to copy).</p>
        <form id="add-tester"><div class="field"><label>Device ID</label><input name="deviceId" required minlength="16" maxlength="64"></div>
          <div class="field"><label>Label (optional)</label><input name="label" placeholder="e.g. Rahim — Samsung A54"></div>
          <button class="btn">Add tester</button></form></div>
      <div class="card"><h2>Beta join code</h2>
        <p class="muted">Anyone who enters this code in <b>Settings → Beta program</b> becomes a tester. Leave empty to close the program.</p>
        <form id="join-code"><div class="field"><input name="code" value="${esc(joinCode)}" placeholder="e.g. BETA-NOV-2026" pattern="[A-Za-z0-9-]{6,40}"></div>
          <div class="row"><button class="btn">Save code</button><button class="btn ghost" type="button" id="gen-code">Generate</button></div></form></div>
    </div>
    <div class="card"><h2>Testers (${testers.length})</h2><div class="table-wrap"><table>
      <tr><th>Device ID</th><th>Label</th><th>Joined via</th><th>App version</th><th>Last seen</th><th></th></tr>
      ${testers.map((t) => `<tr><td class="mono">${esc(t.deviceId)}</td><td>${esc(t.label)}</td><td>${t.source === 'join_code' ? 'Join code' : 'Admin'}</td>
        <td>${esc(t.versionCode ?? '—')}</td><td>${fmtDate(t.lastSeen)}</td><td><button class="btn danger small" data-remove="${esc(t.deviceId)}">Remove</button></td></tr>`).join('') || '<tr><td colspan="6" class="empty">No testers yet</td></tr>'}
    </table></div></div>`;
  $('#add-tester').onsubmit = async (e) => {
    e.preventDefault();
    try { await api('POST', '/admin/api/testers', Object.fromEntries(new FormData(e.target))); toast('Tester added'); refresh(); } catch (err) { fail(err); }
  };
  $('#join-code').onsubmit = async (e) => {
    e.preventDefault();
    try { const r = await api('PUT', '/admin/api/testers/join-code', Object.fromEntries(new FormData(e.target))); toast(r.joinCode ? 'Join code saved' : 'Beta program closed'); } catch (err) { fail(err); }
  };
  $('#gen-code').onclick = () => {
    const a = new Uint8Array(4); crypto.getRandomValues(a);
    $('#join-code input').value = `BETA-${[...a].map((x) => x.toString(16).padStart(2, '0')).join('').toUpperCase()}`;
  };
  root.addEventListener('click', async (e) => {
    const b = e.target.closest('[data-remove]'); if (!b) return;
    try { await api('DELETE', `/admin/api/testers/${encodeURIComponent(b.dataset.remove)}`); toast('Tester removed'); refresh(); } catch (err) { fail(err); }
  });
};

// ---------------- Statistics ----------------
VIEWS.stats = async (root) => {
  const s = await api('GET', '/admin/api/stats');
  const pct = (a, b) => (b ? `${Math.round((a / b) * 100)}%` : '—');
  root.innerHTML = `
    <div class="card"><h2>Update funnel per release</h2>
      <p class="muted">Counts are unique devices. "Offered" = devices whose update check returned this release.</p>
      <div class="table-wrap"><table>
        <tr><th>Release</th><th>Status</th><th>Rollout</th><th>Offered</th><th>Download started</th><th>Downloaded</th><th>Download failed</th><th>Installed</th><th>Install failed</th><th>Dismissed</th><th>Install rate</th><th>Active on it (30d)</th></tr>
        ${s.releases.map((r) => `<tr><td><b>${esc(r.versionName)}</b> <small>${r.versionCode}</small> <span class="badge ${r.channel}">${r.channel}</span></td>
          <td><span class="badge ${r.status}">${STATUS_LABEL[r.status]}</span></td><td>${r.rolloutPercent}%</td>
          <td>${r.offeredDevices}</td><td>${r.downloadStarted}</td><td>${r.downloadCompleted}</td><td>${r.downloadFailed}</td>
          <td>${r.installSuccess}</td><td>${r.installFailed}</td><td>${r.dismissed}</td><td>${pct(r.installSuccess, r.offeredDevices)}</td><td>${r.activeDevicesOnVersion}</td></tr>`).join('') || '<tr><td colspan="12" class="empty">No published releases yet</td></tr>'}
      </table></div></div>
    <div class="grid cols-2">
      <div class="card"><h2>Versions in use (30 days)</h2>${versionBars(s.versionDistribution)}</div>
      <div class="card"><h2>Installs per day</h2>${dailyBars(s.daily.map((d) => ({ ...d, checks: d.installs })))}</div>
    </div>`;
};

// ---------------- Remote settings & ads ----------------
VIEWS.config = async (root) => {
  const data = await api('GET', '/admin/api/config');
  let scope = state.configScope || 'all';
  const render = () => {
    state.configScope = scope;
    const sc = data.scopes[scope];
    const base = scope === 'all' ? data.defaults : data.effective[scope];
    const ads = { ...base.ads, ...(sc.config.ads || {}) };
    const u = ads.units || {}; const pl = ads.placements || {}; const fr = ads.frequency || {};
    root.innerHTML = `
      <div class="tabs">${['all', 'stable', 'beta'].map((s) => `<button data-scope="${s}" class="${s === scope ? 'active' : ''}">${s === 'all' ? 'All users' : s === 'stable' ? 'Stable overrides' : 'Beta overrides'}</button>`).join('')}</div>
      <div class="notice info">Apps read: <b>defaults</b> ← <b>All users</b> ← <b>Stable</b> or <b>Beta</b> overrides. Changes reach devices at their next settings refresh (on app start and every few hours).</div>
      <div class="grid cols-2">
        <div class="card"><h2>Ads</h2>
          <form id="ads-form">
            <label class="check"><input type="checkbox" name="enabled" ${ads.enabled ? 'checked' : ''}> Show ads</label>
            <div class="fields">
              <div class="field"><label>Provider</label><select name="provider">${['admob', 'applovin', 'unity', 'none'].map((p) => `<option ${ads.provider === p ? 'selected' : ''}>${p}</option>`).join('')}</select></div>
              <div class="field"><label>App ID</label><input name="appId" value="${esc(ads.appId)}" placeholder="ca-app-pub-…~…"></div>
            </div>
            <h3>Ad unit IDs</h3>
            <div class="fields">${['banner', 'interstitial', 'rewarded', 'native'].map((k) => `<div class="field"><label>${k}</label><input name="unit_${k}" value="${esc(u[k])}"></div>`).join('')}</div>
            <h3>Placements</h3>
            <label class="check"><input type="checkbox" name="pl_homeBanner" ${pl.homeBanner ? 'checked' : ''}> Banner on home screen</label>
            <label class="check"><input type="checkbox" name="pl_interstitialOnCloneLaunch" ${pl.interstitialOnCloneLaunch ? 'checked' : ''}> Interstitial when launching a clone</label>
            <label class="check"><input type="checkbox" name="pl_nativeInCloneList" ${pl.nativeInCloneList ? 'checked' : ''}> Native ad in clone list</label>
            <h3>Frequency limits</h3>
            <div class="fields">
              <div class="field"><label>Min seconds between interstitials</label><input type="number" min="0" name="fr_interstitialMinIntervalSec" value="${fr.interstitialMinIntervalSec ?? 180}"></div>
              <div class="field"><label>Max interstitials per day</label><input type="number" min="0" name="fr_maxInterstitialsPerDay" value="${fr.maxInterstitialsPerDay ?? 10}"></div>
              <div class="field"><label>App launches before first ad</label><input type="number" min="0" name="fr_launchesBeforeFirstAd" value="${fr.launchesBeforeFirstAd ?? 3}"></div>
            </div>
            <div class="field"><label>Change note</label><input name="note" placeholder="e.g. Enable interstitials"></div>
            <button class="btn">Save ads for “${scope}”</button>
          </form></div>
        <div class="card"><h2>All settings (JSON) — ${scope}</h2>
          <p class="muted">Only keys you set here override the level below. Saved as a new version.</p>
          <textarea class="code" id="raw">${esc(JSON.stringify(sc.config, null, 2))}</textarea>
          <div class="field" style="margin-top:10px"><input id="raw-note" placeholder="Change note"></div>
          <div class="row"><button class="btn" id="save-raw">Save JSON</button><button class="btn ghost" id="show-eff">View effective settings</button></div>
          <h3 style="margin-top:18px">History</h3><div id="hist" class="muted">Loading…</div></div>
      </div>`;
    $$('[data-scope]', root).forEach((b) => { b.onclick = () => { scope = b.dataset.scope; render(); }; });
    $('#ads-form').onsubmit = async (e) => {
      e.preventDefault();
      const f = new FormData(e.target);
      const nextAds = {
        enabled: f.get('enabled') === 'on', provider: f.get('provider'), appId: f.get('appId').trim(),
        units: Object.fromEntries(['banner', 'interstitial', 'rewarded', 'native'].map((k) => [k, f.get(`unit_${k}`).trim()])),
        placements: Object.fromEntries(['homeBanner', 'interstitialOnCloneLaunch', 'nativeInCloneList'].map((k) => [k, f.get(`pl_${k}`) === 'on'])),
        frequency: Object.fromEntries(['interstitialMinIntervalSec', 'maxInterstitialsPerDay', 'launchesBeforeFirstAd'].map((k) => [k, Number(f.get(`fr_${k}`))])),
      };
      await saveConfig({ ...sc.config, ads: nextAds }, f.get('note'));
    };
    $('#save-raw').onclick = async () => {
      let obj; try { obj = JSON.parse($('#raw').value); } catch (err) { return toast(`JSON error: ${err.message}`, 'bad'); }
      await saveConfig(obj, $('#raw-note').value);
    };
    $('#show-eff').onclick = () => modal(`<h2>Effective settings</h2><h3>Stable users</h3><pre class="changelog mono">${esc(JSON.stringify(data.effective.stable, null, 2))}</pre>
      <h3>Beta testers</h3><pre class="changelog mono">${esc(JSON.stringify(data.effective.beta, null, 2))}</pre><button class="btn ghost" data-close>Close</button>`);
    api('GET', `/admin/api/config/${scope}/history`).then(({ history }) => {
      $('#hist').innerHTML = history.length ? `<table>${history.map((h, i) => `<tr><td>#${h.id}</td><td>${fmtDate(h.createdAt)}<br><small>${esc(h.username || '')} ${esc(h.note)}</small></td>
        <td>${i === 0 ? '<span class="badge live">Current</span>' : `<button class="btn ghost small" data-revert="${h.id}">Revert to this</button>`}</td></tr>`).join('')}</table>` : 'No changes saved yet.';
      $$('[data-revert]', root).forEach((b) => { b.onclick = async () => { try { await api('POST', `/admin/api/config/${scope}/revert/${b.dataset.revert}`); toast('Reverted'); refresh(); } catch (err) { fail(err); } }; });
    }).catch(fail);
  };
  const saveConfig = async (obj, note) => {
    try { await api('PUT', `/admin/api/config/${scope}`, { config: obj, note }); toast('Settings saved'); refresh(); } catch (err) { fail(err); }
  };
  render();
};

// ---------------- Website content ----------------
VIEWS.site = async (root) => {
  const s = await api('GET', '/admin/api/site');
  root.innerHTML = `
    <div class="card"><h2>Texts</h2><form id="site-form">
      <div class="fields"><div class="field"><label>App name</label><input name="appName" value="${esc(s.appName)}" required></div>
        <div class="field"><label>Support email</label><input name="supportEmail" type="email" value="${esc(s.supportEmail)}"></div></div>
      <div class="field"><label>Tagline</label><input name="tagline" value="${esc(s.tagline)}"></div>
      <div class="field"><label>Description</label><textarea name="description">${esc(s.description)}</textarea></div>
      <h3>Features</h3><div id="feat">${s.features.map(featRow).join('')}</div>
      <div class="row"><button class="btn ghost small" type="button" id="add-feat">Add feature</button></div>
      <div class="row" style="margin-top:14px"><button class="btn">Save website texts</button><a class="btn ghost" href="/" target="_blank" rel="noopener">Open website</a></div>
    </form></div>
    <div class="card"><h2>Screenshots (${s.screenshots.length}/20)</h2>
      <form id="shot-form" class="row" style="margin-bottom:14px"><input type="file" name="file" accept="image/png,image/jpeg,image/webp" required style="max-width:280px">
        <input name="caption" placeholder="Caption (optional)" style="max-width:260px"><button class="btn">Upload</button></form>
      <div class="shots">${s.screenshots.map((x) => `<div class="shot"><img src="${esc(x.url)}" alt=""><input value="${esc(x.caption)}" data-cap="${x.id}" placeholder="Caption" style="margin:6px 0">
        <div class="row"><input type="number" value="${x.sort}" data-sort="${x.id}" style="width:60px" title="Order"><button class="btn ghost small" data-save-shot="${x.id}">Save</button><button class="btn danger small" data-del-shot="${x.id}">Delete</button></div></div>`).join('') || '<div class="muted">No screenshots yet.</div>'}</div>
    </div>`;
  $('#add-feat').onclick = () => $('#feat').insertAdjacentHTML('beforeend', featRow({ title: '', text: '' }));
  $('#feat').addEventListener('click', (e) => { if (e.target.matches('[data-del-feat]')) e.target.closest('.fields').remove(); });
  $('#site-form').onsubmit = async (e) => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(e.target));
    const features = $$('#feat .fields').map((row) => ({ title: $('[data-ft]', row).value.trim(), text: $('[data-fx]', row).value.trim() })).filter((x) => x.title);
    try { await api('PUT', '/admin/api/site', { appName: f.appName, tagline: f.tagline, description: f.description, supportEmail: f.supportEmail, features }); toast('Website updated'); } catch (err) { fail(err); }
  };
  $('#shot-form').onsubmit = async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    try { await upload('POST', `/admin/api/screenshots?caption=${encodeURIComponent(f.get('caption'))}`, f.get('file')); toast('Screenshot uploaded'); refresh(); } catch (err) { fail(err); }
  };
  root.addEventListener('click', async (e) => {
    const sv = e.target.closest('[data-save-shot]'); const dl = e.target.closest('[data-del-shot]');
    try {
      if (sv) { const id = sv.dataset.saveShot; await api('PATCH', `/admin/api/screenshots/${id}`, { caption: $(`[data-cap="${id}"]`).value, sort: Number($(`[data-sort="${id}"]`).value) }); toast('Saved'); refresh(); }
      if (dl) { await api('DELETE', `/admin/api/screenshots/${dl.dataset.delShot}`); toast('Deleted'); refresh(); }
    } catch (err) { fail(err); }
  });
};
const featRow = (f) => `<div class="fields"><div class="field"><input data-ft value="${esc(f.title)}" placeholder="Title"></div>
  <div class="field"><input data-fx value="${esc(f.text)}" placeholder="Short text"></div><div class="field"><button class="btn danger small" type="button" data-del-feat>Remove</button></div></div>`;

// ---------------- Support ----------------
VIEWS.support = async (root) => {
  const filter = state.ticketFilter || 'open';
  const { tickets } = await api('GET', `/admin/api/tickets${filter === 'all' ? '' : `?status=${filter}`}`);
  root.innerHTML = `<div class="tabs">${['open', 'answered', 'closed', 'all'].map((s) => `<button data-filter="${s}" class="${s === filter ? 'active' : ''}">${s[0].toUpperCase() + s.slice(1)}</button>`).join('')}</div>
    <div class="card"><div class="table-wrap"><table><tr><th>#</th><th>Subject</th><th>Category</th><th>Status</th><th>App</th><th>Updated</th></tr>
    ${tickets.map((t) => `<tr class="click" data-ticket="${t.id}"><td>${t.id}</td><td>${esc(t.subject)}<br><small>${esc(t.email || 'no email')}</small></td><td>${esc(t.category.replace('_', ' '))}</td>
      <td><span class="badge ${t.status}">${t.status}</span></td><td>${esc(t.appVersion || '')}</td><td>${fmtDate(t.updatedAt)}</td></tr>`).join('') || '<tr><td colspan="6" class="empty">No tickets</td></tr>'}
    </table></div></div>`;
  $$('[data-filter]', root).forEach((b) => { b.onclick = () => { state.ticketFilter = b.dataset.filter; refresh(); }; });
  $$('[data-ticket]', root).forEach((tr) => { tr.onclick = () => openTicket(tr.dataset.ticket); });
};
async function openTicket(id) {
  try {
    const { ticket: t, messages } = await api('GET', `/admin/api/tickets/${id}`);
    modal(`<h2>#${t.id} ${esc(t.subject)}</h2>
      <p class="muted">${esc(t.category.replace('_', ' '))} · ${esc(t.email || 'no email')} · app ${esc(t.appVersion || '?')}<br><span class="mono">${esc(t.deviceId)}</span></p>
      ${t.deviceInfo ? `<details><summary class="muted">Device info</summary><pre class="changelog mono">${esc(t.deviceInfo)}</pre></details>` : ''}
      <div style="margin:12px 0">${messages.map((m) => `<div class="msg ${m.sender}"><small>${m.sender === 'admin' ? 'Support' : 'User'} · ${fmtDate(m.createdAt)}</small><br>${esc(m.body)}</div>`).join('')}</div>
      <textarea id="reply" placeholder="Write a reply. The user sees it in the app under Support."></textarea>
      <div class="row" style="margin-top:10px"><button class="btn" id="send-reply">Send reply</button>
        <select id="t-status" style="width:auto">${['open', 'answered', 'closed'].map((s) => `<option ${s === t.status ? 'selected' : ''}>${s}</option>`).join('')}</select>
        <button class="btn ghost" id="set-status">Set status</button><span class="spacer"></span><button class="btn ghost" data-close>Close</button></div>`, (root) => {
      $('#send-reply', root).onclick = async () => { try { await api('POST', `/admin/api/tickets/${id}/reply`, { message: $('#reply', root).value }); toast('Reply sent'); openTicket(id); refresh(); } catch (err) { fail(err); } };
      $('#set-status', root).onclick = async () => { try { await api('POST', `/admin/api/tickets/${id}/status`, { status: $('#t-status', root).value }); toast('Status updated'); closeModal(); refresh(); } catch (err) { fail(err); } };
    });
  } catch (err) { fail(err); }
}

// ---------------- Audit ----------------
VIEWS.audit = async (root) => {
  const { entries } = await api('GET', '/admin/api/audit?limit=300');
  root.innerHTML = `<div class="card"><div class="table-wrap"><table><tr><th>Time</th><th>Admin</th><th>Action</th><th>Details</th><th>IP</th></tr>
    ${entries.map((e) => `<tr><td>${fmtDate(e.createdAt)}</td><td>${esc(e.username || 'system')}</td><td><code>${esc(e.action)}</code></td><td class="mono">${esc(e.detail || '')}</td><td>${esc(e.ip || '')}</td></tr>`).join('')}
  </table></div></div>`;
};

// ---------------- Account ----------------
VIEWS.account = async (root) => {
  const a = await api('GET', '/admin/api/account');
  root.innerHTML = `<div class="grid cols-2">
    <div class="card"><h2>Change password</h2><form id="pw-form">
      <div class="field"><label>Current password</label><input type="password" name="currentPassword" autocomplete="current-password" required></div>
      <div class="field"><label>New password</label><input type="password" name="newPassword" autocomplete="new-password" minlength="12" required>
        <div class="hint">At least 12 characters, with three of: lowercase, uppercase, numbers, symbols.</div></div>
      <div class="field"><label>Authenticator code</label><input name="code" inputmode="numeric" maxlength="6" required></div>
      <button class="btn">Change password</button><p class="hint">Other sessions are signed out.</p></form></div>
    <div class="card"><h2>Two-factor authentication</h2>
      <p><span class="badge live">On</span> Recovery codes left: <b>${a.recoveryCodesLeft}</b></p>
      <form id="rc-form"><div class="field"><label>Authenticator code</label><input name="code" inputmode="numeric" maxlength="6" required></div>
        <button class="btn ghost">Create new recovery codes</button><p class="hint">Old codes stop working.</p></form>
      <p class="hint">Lost your authenticator and all codes? See “Reset two-factor” in the setup guide.</p></div>
  </div>
  <div class="card"><h2>Active sessions</h2><table><tr><th>Started</th><th>IP</th><th>Browser</th><th>Expires</th></tr>
    ${a.sessions.map((s) => `<tr><td>${fmtDate(s.created_at)} ${s.current ? '<span class="badge live">This one</span>' : ''}</td><td>${esc(s.ip)}</td><td><small>${esc(s.ua)}</small></td><td>${fmtDate(s.expires_at)}</td></tr>`).join('')}</table></div>`;
  $('#pw-form').onsubmit = async (e) => {
    e.preventDefault();
    try { await api('POST', '/admin/api/account/password', Object.fromEntries(new FormData(e.target))); toast('Password changed'); e.target.reset(); refresh(); } catch (err) { fail(err); }
  };
  $('#rc-form').onsubmit = async (e) => {
    e.preventDefault();
    try { const r = await api('POST', '/admin/api/account/recovery-codes', Object.fromEntries(new FormData(e.target))); showRecoveryCodes(r.recoveryCodes, false); e.target.reset(); } catch (err) { fail(err); }
  };
};

// ---------------- Boot ----------------
(async () => {
  try {
    const s = await api('GET', '/admin/api/session');
    if (s.stage === 'full') return enterApp();
    $('#auth').classList.remove('hidden');
    if (s.stage === 'password') { state.csrf = s.csrf; return s.totpEnabled ? renderTotp() : renderTotpSetup(); }
    if (s.needsSetup) return renderSetup();
    renderLogin();
  } catch { showAuth(); }
})();
