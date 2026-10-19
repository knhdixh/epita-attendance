// Calendars: the list in Settings (rename, update, replace the file, remove), the add box (a link or an .ics file,
// also the first-run screen), the sync log, and file uploads from the picker or drag and drop.
import { state, esc, api, toast, render, reload } from './app.js';

const $file = document.getElementById('file-input');
const fmtShort = (ms) => new Date(ms).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;
const TRIGGERS = { cron: 'Automatic', manual: 'Update now', connect: 'Added', upload: 'Uploaded' };
// What the next file chosen in the picker is for: a new calendar, or a newer version of calendar `id`.
let filePurpose = { mode: 'add' };

const calendars = () => state.data.calendars || [];

function ago(ms) {
  const min = Math.round((Date.now() - ms) / 60000);
  return min < 1 ? 'just now' : min < 60 ? `${min} min ago` : min < 1440 ? `${Math.round(min / 60)} h ago` : fmtShort(ms);
}

export function importSummary(r) {
  const parts = [`${r.added} new`, `${r.updated} updated`];
  if (r.removed) parts.push(`${r.removed} removed`);
  if (r.recurringIgnored) parts.push(`${r.recurringIgnored} recurring only imported once`);
  return `${plural(r.total, 'session')} (${parts.join(', ')})`;
}

// ---------------------------------------------------------------- views

// Two ways in, side by side: a link the app keeps updating, or a file.
export function addBox({ cancel = true } = {}) {
  return `
    <div class="cal-add">
      <form id="cal-link-form" class="cal-add-opt" autocomplete="off">
        <div class="cal-add-head"><svg class="ic" aria-hidden="true"><use href="#i-link"/></svg><b>From a link</b><span>updates every 3 days</span></div>
        <input class="field" name="url" type="text" inputmode="url" required spellcheck="false"
          placeholder="https://zeus.ionis-it.com/api/group/… or webcal://…" aria-label="Calendar link">
        <div class="cal-add-row">
          <input class="field" name="name" maxlength="60" placeholder="Name (optional)" aria-label="Name">
          <label title="Zeus links: the first day to import, so past classes stay">From <input class="field" type="date" name="from" aria-label="Import classes from"></label>
        </div>
        <div class="cal-add-foot">
          <a href="/ics-guide#link" target="_blank" rel="noopener">How to get your Zeus link</a>
          <button class="btn btn-primary" type="submit">Add link</button>
        </div>
      </form>
      <div class="cal-add-opt cal-drop" data-cal-pick role="button" tabindex="0">
        <div class="cal-add-head"><svg class="ic" aria-hidden="true"><use href="#i-upload"/></svg><b>From a file</b><span>.ics</span></div>
        <p>Drop it here or <span class="link">choose a file</span>.</p>
        <p class="hint">Upload it again when the school publishes changes: your marks are kept. <a href="/ics-guide" target="_blank" rel="noopener">Export it from Zeus</a></p>
      </div>
      ${cancel ? '<button class="btn btn-borderless btn-small cal-add-cancel" data-cal-cancel>Cancel</button>' : ''}
    </div>`;
}

function row(c) {
  if (state.calEditing === c.id) {
    return `
      <form class="cal-src editing" data-cal="${c.id}" id="cal-edit-form" autocomplete="off">
        <span class="cal-src-icon"><svg class="ic" aria-hidden="true"><use href="#${c.kind === 'link' ? 'i-link' : 'i-calendar'}"/></svg></span>
        <div class="cal-edit-fields">
          <input class="field" name="name" maxlength="60" required value="${esc(c.name)}" aria-label="Name">
          ${c.kind === 'link' ? `<label>From <input class="field" type="date" name="from" value="${esc(c.from || '')}" aria-label="Import classes from"></label>` : ''}
        </div>
        <div class="cal-src-actions">
          <button class="btn btn-small" type="button" data-cal-cancel>Cancel</button>
          <button class="btn btn-small btn-primary" type="submit">Save</button>
        </div>
      </form>`;
  }
  const status = c.error ? `<span class="bad">${esc(c.error)}</span>`
    : c.kind === 'link' ? `updated ${c.okAt ? ago(c.okAt) : 'never'}` : `uploaded ${c.okAt ? ago(c.okAt) : ''}`;
  const where = c.kind === 'link' ? c.site : c.fileName;
  return `
    <div class="cal-src${c.error ? ' failing' : ''}" data-cal="${c.id}">
      <span class="cal-src-icon"><svg class="ic" aria-hidden="true"><use href="#${c.kind === 'link' ? 'i-link' : 'i-calendar'}"/></svg></span>
      <div class="cal-src-main">
        <div class="cal-src-name">${esc(c.name)}</div>
        <div class="cal-src-meta">${[where && esc(where), plural(c.sessions, 'session'), status].filter(Boolean).join(' · ')}</div>
      </div>
      <div class="cal-src-actions">
        ${c.kind === 'link'
          ? '<button class="btn btn-borderless btn-small" data-cal-sync title="Update now" aria-label="Update now"><svg class="ic" aria-hidden="true"><use href="#i-refresh"/></svg></button>'
          : '<button class="btn btn-borderless btn-small" data-cal-replace title="Upload a newer version" aria-label="Upload a newer version"><svg class="ic" aria-hidden="true"><use href="#i-upload"/></svg></button>'}
        <button class="btn btn-borderless btn-small" data-cal-edit title="Rename${c.kind === 'link' ? ' or change the first day' : ''}" aria-label="Edit"><svg class="ic" aria-hidden="true"><use href="#i-edit"/></svg></button>
        <button class="btn btn-borderless btn-small" data-cal-remove title="Remove" aria-label="Remove"><svg class="ic" aria-hidden="true"><use href="#i-trash"/></svg></button>
      </div>
    </div>`;
}

// The last 20 imports, newest first: when, which calendar, what started it, and what came back.
function syncLog() {
  const rows = state.data.calendarSyncs || [];
  if (!rows.length) return '';
  const names = new Map(calendars().map((c) => [c.id, c.name]));
  const when = (ms) => new Date(ms).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  return `<details class="sync-log">
      <summary>Recent updates</summary>
      <ul>${rows.map((r) => `
        <li class="${r.ok ? 'ok' : 'bad'}">
          <span class="sync-when">${esc(when(r.at))}</span>
          <span class="sync-what">${esc(names.get(r.source_id) || 'Calendar')} · ${esc(TRIGGERS[r.source] || r.source)} · ${r.ok
            ? `${plural(r.sessions, 'session')}${r.added ? `, ${r.added} new` : ''}${r.removed ? `, ${r.removed} removed` : ''}`
            : esc(r.error || 'Failed')}</span>
          <span class="sync-meta">${r.http_status ? `HTTP ${r.http_status} · ` : ''}${(r.duration_ms / 1000).toFixed(1)} s</span>
        </li>`).join('')}
      </ul>
    </details>`;
}

export function settingsPanel() {
  const list = calendars();
  return `
    <section class="panel narrow" id="calendars">
      <h3><span>Calendars</span><span class="muted">${list.length || ''}</span></h3>
      <div class="list">${list.map(row).join('')}</div>
      ${state.calAdding || !list.length ? addBox({ cancel: list.length > 0 })
        : '<button class="add-row" data-cal-add><span class="plus" aria-hidden="true"><svg class="ic"><use href="#i-plus"/></svg></span>Add calendar</button>'}
      <p class="hint">A session in several calendars (like a lecture for every group) is stored and counted once, and labelled with each calendar's name. Use <b>Count</b> at the top to count only some of them.</p>
      ${syncLog()}
    </section>`;
}

// ---------------------------------------------------------------- actions

async function refresh(message) {
  await reload();
  render();
  if (message) toast(message);
}

// A dropped or chosen file: a newer version of the calendar with the same file name, else a new calendar.
export async function uploadFile(file, purpose = { mode: 'add' }) {
  if (!file) return;
  try {
    const ics = await file.text();
    if (!ics.includes('BEGIN:VCALENDAR')) throw new Error('That does not look like an .ics calendar file.');
    const same = purpose.mode === 'add' && calendars().find((c) => c.kind === 'file' && c.fileName === file.name);
    const id = purpose.mode === 'replace' ? purpose.id : same?.id;
    const r = id
      ? await api(`/api/calendars/${id}/file`, { method: 'POST', body: { ics, fileName: file.name } })
      : await api('/api/calendars', { method: 'POST', body: { kind: 'file', ics, fileName: file.name } });
    state.calAdding = false;
    const name = calendars().find((c) => c.id === id)?.name;
    await refresh(`${id ? `Updated ${name}` : 'Added calendar'}: ${importSummary(r)}.`);
  } catch (err) { toast(err.message, true); }
}

export function pickFile(purpose = { mode: 'add' }) {
  filePurpose = purpose;
  $file.click();
}

export function openAdd() {
  state.calAdding = true;
  state.tab = 'settings';
  history.replaceState(null, '', '#settings');
  render();
  const box = document.querySelector('.cal-add');
  box?.scrollIntoView({ block: 'center' });
  box?.querySelector('[name=url]')?.focus({ preventScroll: true });
}

export async function syncCalendar(id) {
  const c = calendars().find((x) => x.id === id);
  try {
    const r = await api(`/api/calendars/${id}/sync`, { method: 'POST' });
    await refresh(`Updated ${c.name}: ${importSummary(r)}.`);
  } catch (err) {
    toast(err.message, true);
    await reload(); render();
  }
}

// Palette entries: add, upload, and update each link.
export function paletteItems(go) {
  const items = [
    { id: 'act:add-calendar', section: 'Actions', label: 'Add calendar', icon: 'i-link', keywords: 'zeus link ics webcal subscribe', run: openAdd },
    { id: 'act:upload', section: 'Actions', label: 'Upload .ics file', icon: 'i-upload', keywords: 'import calendar timetable file', home: true, run: () => pickFile() },
  ];
  for (const c of calendars().filter((x) => x.kind === 'link')) {
    items.push({ id: `cal-sync:${c.id}`, section: 'Actions', label: `Update ${c.name}`, sub: c.site, icon: 'i-refresh',
      keywords: 'sync refresh calendar zeus', run: () => { go('settings'); syncCalendar(c.id); } });
  }
  return items;
}

// ---------------------------------------------------------------- handlers (return true if handled)

export function onClick(e) {
  const t = e.target;
  const id = Number(t.closest('[data-cal]')?.dataset.cal);
  if (t.closest('[data-cal-add]')) { openAdd(); return true; }
  if (t.closest('[data-cal-pick]')) { pickFile(); return true; }
  if (t.closest('[data-cal-cancel]')) { state.calAdding = false; state.calEditing = null; render(); return true; }
  if (t.closest('[data-cal-replace]')) { pickFile({ mode: 'replace', id }); return true; }
  if (t.closest('[data-cal-edit]')) {
    state.calEditing = id;
    render();
    document.querySelector('#cal-edit-form [name=name]')?.select();
    return true;
  }
  const sync = t.closest('[data-cal-sync]');
  if (sync) {
    sync.disabled = true;
    sync.classList.add('spinning');
    syncCalendar(id);
    return true;
  }
  if (t.closest('[data-cal-remove]')) {
    const c = calendars().find((x) => x.id === id);
    // Only sessions no other calendar has are deleted; shared ones (e.g. lectures for all groups) stay.
    const only = state.data.allEvents.filter((e) => e.cals.length === 1 && e.cals[0] === id).length;
    const shared = c.sessions - only;
    if (!confirm(`Remove "${c.name}"? ${only ? `${plural(only, 'session')} only in it, and their attendance marks, are deleted.` : 'No sessions are deleted.'}${shared ? ` ${plural(shared, 'shared session')} stay in your other calendars.` : ''}${c.kind === 'link' ? ' It stops updating.' : ''}`)) return true;
    api(`/api/calendars/${id}`, { method: 'DELETE' })
      .then((r) => refresh(`Removed ${c.name} (${plural(r.removed, 'session')}).`))
      .catch((err) => toast(err.message, true));
    return true;
  }
  return false;
}

export function onKeydown(e) {
  if (e.key === 'Escape' && e.target.closest?.('#cal-edit-form, .cal-add')) {
    state.calAdding = false; state.calEditing = null; render();
    return true;
  }
  if ((e.key === 'Enter' || e.key === ' ') && e.target.matches?.('[data-cal-pick]')) { e.preventDefault(); pickFile(); return true; }
  return false;
}

export function onSubmit(e) {
  const form = e.target;
  if (form.id === 'cal-link-form') {
    e.preventDefault();
    const fd = new FormData(form);
    const btn = form.querySelector('[type=submit]');
    btn.disabled = true; btn.textContent = 'Adding…';
    api('/api/calendars', { method: 'POST', body: { kind: 'link', url: fd.get('url'), name: fd.get('name'), from: fd.get('from') || null } })
      .then((r) => { state.calAdding = false; return refresh(`Added calendar: ${importSummary(r)}.`); })
      .catch((err) => { toast(err.message, true); btn.disabled = false; btn.textContent = 'Add link'; });
    return true;
  }
  if (form.id === 'cal-edit-form') {
    e.preventDefault();
    const fd = new FormData(form);
    const id = Number(form.dataset.cal);
    const body = { name: fd.get('name') };
    if (fd.has('from')) body.from = fd.get('from') || null;
    api(`/api/calendars/${id}`, { method: 'PATCH', body })
      .then((r) => { state.calEditing = null; return refresh(r.total != null ? `Saved and updated: ${importSummary(r)}.` : 'Saved.'); })
      .catch((err) => toast(err.message, true));
    return true;
  }
  return false;
}

$file.addEventListener('change', () => {
  uploadFile($file.files[0], filePurpose);
  $file.value = '';
  filePurpose = { mode: 'add' };
});
