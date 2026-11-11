'use strict';
(function () {
  const API = (window.MULTI_APPS_API_BASE || '').replace(/\/$/, '');
  const $ = (s) => document.querySelector(s);
  const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const size = (b) => (b ? `${(b / 1048576).toFixed(1)} MB` : '');
  const date = (ms) => (ms ? new Date(ms).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' }) : '');
  // Absolute URLs from the server may point at another host; only allow http(s).
  const safeUrl = (u) => { try { const x = new URL(u, location.href); return /^https?:$/.test(x.protocol) ? x.href : '#'; } catch { return '#'; } };

  $('#year').textContent = new Date().getFullYear();

  async function get(path) {
    const r = await fetch(API + path, { headers: { Accept: 'application/json' } });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
  }

  get('/api/v1/public/site').then((s) => {
    document.querySelectorAll('[data-bind]').forEach((el) => { const v = s[el.dataset.bind]; if (v) el.textContent = v; });
    if (s.appName) document.title = `${s.appName} — ${s.tagline || 'Download for Android'}`;

    const btn = $('#download-btn');
    if (s.latest) {
      btn.href = safeUrl(s.latest.downloadUrl);
      btn.removeAttribute('aria-disabled');
      btn.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M12 3v12m0 0-5-5m5 5 5-5M4 20h16"/></svg> Download APK v${esc(s.latest.versionName)}`;
      $('#download-meta').innerHTML = `${esc(size(s.latest.fileSize))} · Released ${esc(date(s.latest.publishedAt))}<code>SHA-256: ${esc(s.latest.sha256)}</code>`;
    } else {
      btn.textContent = 'Coming soon';
      $('#download-meta').textContent = 'The first version is not published yet.';
    }

    if (Array.isArray(s.features) && s.features.length) {
      $('#features-list').innerHTML = s.features.map((f) => `<div class="feature"><b>${esc(f.title)}</b><p>${esc(f.text)}</p></div>`).join('');
    } else { $('#features').classList.add('hidden'); }

    if (Array.isArray(s.screenshots) && s.screenshots.length) {
      $('#shots').innerHTML = s.screenshots.map((x) => `<figure data-src="${esc(safeUrl(x.url))}" data-cap="${esc(x.caption)}"><img loading="lazy" src="${esc(safeUrl(x.url))}" alt="${esc(x.caption || 'Screenshot')}">${x.caption ? `<figcaption>${esc(x.caption)}</figcaption>` : ''}</figure>`).join('');
    }
    if (s.supportEmail) $('#support-line').innerHTML = `Support: <a href="mailto:${esc(s.supportEmail)}">${esc(s.supportEmail)}</a>`;
  }).catch(() => {
    $('#download-btn').textContent = 'Download unavailable';
    $('#download-meta').textContent = 'Could not reach the server. Please try again later.';
  });

  get('/api/v1/public/releases').then(({ releases }) => {
    if (!releases.length) { $('#changes').innerHTML = '<p class="muted">No releases yet.</p>'; return; }
    $('#changes').innerHTML = releases.map((r, i) => `<article class="change"><header><b>v${esc(r.versionName)}</b><span>${esc(date(r.publishedAt))}</span>
      ${i === 0 ? '<span class="latest">Latest</span>' : ''}</header>${r.changelog ? `<pre>${esc(r.changelog)}</pre>` : ''}</article>`).join('');
  }).catch(() => { $('#changes').innerHTML = '<p class="muted">Changelog is unavailable right now.</p>'; });

  // Screenshot lightbox
  $('#shots').addEventListener('click', (e) => {
    const f = e.target.closest('figure'); if (!f) return;
    const lb = $('#lightbox'); lb.querySelector('img').src = f.dataset.src; lb.querySelector('p').textContent = f.dataset.cap || '';
    lb.classList.remove('hidden');
  });
  $('#lightbox').addEventListener('click', () => $('#lightbox').classList.add('hidden'));
}());
