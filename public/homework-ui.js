// Homework tab, overview panel, session badges and calendar-integration settings.
import { state, esc, api, toast, render, reload, courseById, fmtTime } from './app.js';

const $app = document.getElementById('app');
const tz = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
const DAY = 86400000;

const REMINDERS = [
  [null, 'No reminder'], [0, 'At the deadline'], [30, '30 min before'], [60, '1 hour before'],
  [180, '3 hours before'], [1440, '1 day before'], [2880, '2 days before'], [10080, '1 week before'],
];
const DEFAULT_REMINDER = 1440;

const pad = (n) => String(n).padStart(2, '0');
const localDate = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; };
const localTime = (ms) => { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
const startOfToday = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d.getTime(); };

// Due-date label like "Today 09:00", "Tomorrow", "Mon 20 Oct 14:00".
function dueLabel(hw) {
  const day = new Date(hw.due_date + 'T00:00:00').getTime(); // local midnight of the due date
  const diff = Math.round((day - startOfToday()) / DAY);
  const dayText = diff === 0 ? 'Today' : diff === 1 ? 'Tomorrow' : diff === -1 ? 'Yesterday'
    : new Date(day).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
  return hw.due_time ? `${dayText} ${fmtTime(hw.due_at)}` : dayText;
}

function bucket(hw, now) {
  if (hw.done) return 'done';
  if (hw.due_at < now) return 'overdue';
  const diff = Math.round((new Date(hw.due_date + 'T00:00:00').getTime() - startOfToday()) / DAY);
  if (diff <= 0) return 'today';
  if (diff === 1) return 'tomorrow';
  if (diff < 7) return 'week';
  return 'later';
}

const BUCKETS = [['overdue', 'Overdue'], ['today', 'Today'], ['tomorrow', 'Tomorrow'], ['week', 'Next 7 days'], ['later', 'Later']];

function syncIcon(hw) {
  const integ = state.data.integrations;
  if (!integ?.gcal) return '';
  if (hw.sync_error) return `<span class="sync bad" title="Google Calendar: ${esc(hw.sync_error)}">⚠︎</span>`;
  if (hw.gcal_event_id) return '<span class="sync ok" title="In Google Calendar">●</span>';
  return '<span class="sync" title="Not synced yet">○</span>';
}

function hwItem(hw, now = Date.now()) {
  const course = courseById()[hw.course_id];
  const overdue = !hw.done && hw.due_at < now;
  const remind = REMINDERS.find(([m]) => m === hw.remind_minutes)?.[1];
  return `
    <div class="hw-item ${hw.done ? 'done' : ''} ${overdue ? 'overdue' : ''}" data-hw="${hw.id}">
      <input type="checkbox" class="hw-check" data-hw-done ${hw.done ? 'checked' : ''} aria-label="Done">
      <div class="hw-main">
        <div class="hw-title">${esc(hw.title)}</div>
        <div class="hw-meta small">
          <span class="${overdue ? 'bad' : ''}">${esc(dueLabel(hw))}</span>
          ${course ? `<span class="chip">${esc(course.name)}</span>` : ''}
          ${hw.remind_minutes != null && !hw.done ? `<span class="muted" title="Reminder">🔔 ${esc(remind)}</span>` : ''}
          ${syncIcon(hw)}
        </div>
        ${hw.notes ? `<div class="hw-notes muted small">${esc(hw.notes)}</div>` : ''}
      </div>
      <div class="hw-actions">
        <button class="btn small ghost" data-hw-edit title="Edit">✎</button>
        <button class="btn small ghost" data-hw-delete title="Delete">🗑</button>
      </div>
    </div>`;
}

// Upcoming sessions of a course, used for the "due at a session" picker.
function upcomingSessions(courseId, limit = 10) {
  const now = Date.now();
  return state.data.events.filter((e) => e.course_id === courseId && e.start_at > now).slice(0, limit);
}

function sessionOptions(courseId) {
  if (!courseId) return '<option value="">Pick a course first</option>';
  const list = upcomingSessions(courseId);
  if (!list.length) return '<option value="">No upcoming sessions</option>';
  return '<option value="">— or pick a session —</option>' + list.map((e) =>
    `<option value="${e.start_at}">${esc(new Date(e.start_at).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' }))} ${fmtTime(e.start_at)}</option>`).join('');
}

// ---------------------------------------------------------------- views

export function renderHomework() {
  const { homework, courses } = state.data;
  const editing = state.hwEditing ? homework.find((h) => h.id === state.hwEditing) : null;
  const draft = editing || {
    title: '', notes: '', course_id: state.hwCourse ?? null, due_date: localDate(Date.now() + DAY), due_time: '',
    remind_minutes: DEFAULT_REMINDER,
  };
  const now = Date.now();
  const groups = Object.fromEntries([...BUCKETS, ['done']].map(([k]) => [k, []]));
  for (const hw of homework) groups[bucket(hw, now)].push(hw);
  groups.done.sort((a, b) => b.due_at - a.due_at);
  const tracked = courses.filter((c) => c.tracked || c.id === draft.course_id);
  const integ = state.data.integrations;

  $app.innerHTML = `
    <div class="hw-layout">
      <section class="panel hw-form-panel">
        <h3>${editing ? 'Edit homework' : 'Add homework'}</h3>
        <form id="hw-form" class="form hw-form" autocomplete="off">
          <label>What's due
            <input name="title" required maxlength="200" placeholder="e.g. TP 3 – SQL joins" value="${esc(draft.title)}">
          </label>
          <label>Course
            <select name="course_id">
              <option value="">No course</option>
              ${tracked.map((c) => `<option value="${c.id}" ${c.id === draft.course_id ? 'selected' : ''}>${esc(c.name)}</option>`).join('')}
            </select>
          </label>
          <label>Due at a session
            <select name="due_session">${sessionOptions(draft.course_id)}</select>
          </label>
          <div class="form-row">
            <label>Date <input type="date" name="due_date" required value="${esc(draft.due_date)}"></label>
            <label>Time <input type="time" name="due_time" value="${esc(draft.due_time || '')}"></label>
          </div>
          <p class="muted small hint">Leave the time empty for "due by end of day".</p>
          <label>Reminder
            <select name="remind_minutes">
              ${REMINDERS.map(([m, l]) => `<option value="${m ?? ''}" ${m === draft.remind_minutes ? 'selected' : ''}>${l}</option>`).join('')}
            </select>
          </label>
          <label>Notes <textarea name="notes" rows="3" maxlength="4000" placeholder="Instructions, links…">${esc(draft.notes)}</textarea></label>
          <div class="form-row">
            <button class="btn primary" type="submit">${editing ? 'Save' : 'Add homework'}</button>
            ${editing ? '<button class="btn" type="button" data-hw-cancel>Cancel</button>' : ''}
          </div>
        </form>
        <p class="muted small">${integ?.gcal
          ? `Synced to Google Calendar (${esc(integ.gcal.email || '')}). Reminders arrive as Google Calendar notifications.`
          : 'Tip: connect Google Calendar in <a href="#settings" data-goto="settings">Settings</a> to get reminders on your phone.'}</p>
      </section>
      <section class="hw-lists">
        ${homework.length ? '' : '<p class="muted empty">No homework yet. Add your first one.</p>'}
        ${BUCKETS.filter(([k]) => groups[k].length).map(([k, label]) => `
          <div class="hw-group ${k}">
            <h4>${label} <span class="muted">${groups[k].length}</span></h4>
            <div class="list">${groups[k].map((hw) => hwItem(hw, now)).join('')}</div>
          </div>`).join('')}
        ${groups.done.length ? `
          <details class="hw-group done">
            <summary><h4>Done <span class="muted">${groups.done.length}</span></h4></summary>
            <div class="list">${groups.done.map((hw) => hwItem(hw, now)).join('')}</div>
          </details>` : ''}
      </section>
    </div>`;
}

// Overview panel: overdue + due within a week.
export function homeworkPanel() {
  const now = Date.now();
  const soon = state.data.homework.filter((h) => !h.done && h.due_at < now + 7 * DAY).slice(0, 6);
  const open = state.data.homework.filter((h) => !h.done).length;
  if (!soon.length) return '';
  return `
    <section class="panel">
      <h3>Homework due soon <a class="small" href="#homework" data-goto="homework">All homework (${open}) →</a></h3>
      <div class="list">${soon.map((hw) => hwItem(hw, now)).join('')}</div>
    </section>`;
}

// Homework due at a given session, shown as a badge on the session row.
export function homeworkForSession(e) {
  return state.data.homework.filter((h) => !h.done && h.course_id === e.course_id && h.due_at === e.start_at);
}

export function integrationsPanel() {
  const integ = state.data.integrations;
  const feed = integ.feedUrl;
  const webcal = feed.replace(/^https?:/, 'webcal:');
  const g = integ.gcal;
  return `
    <section class="panel narrow">
      <h3>Google Calendar sync</h3>
      ${!integ.gcalAvailable ? '<p class="muted">Not configured on this server.</p>' : g ? `
        <p>Connected as <b>${esc(g.email || 'your Google account')}</b>. Homework is kept in a calendar called
          <b>Homework (Attendance80)</b>. Changes appear immediately, and each reminder is a Google Calendar notification.</p>
        ${g.error ? `<p class="note bad">${esc(g.error)}</p>` : ''}
        <div class="form-row">
          ${g.error ? '<a class="btn primary" href="/auth/google/calendar">Reconnect</a>' : ''}
          <button class="btn" data-action="gcal-resync">Sync now</button>
          <button class="btn ghost" data-action="gcal-disconnect">Disconnect</button>
        </div>` : `
        <p>Push homework into a dedicated calendar in your Google account, like Todoist does. Reminders then show up as
          Google Calendar notifications on your phone and computer. The app can only see the calendar it creates.</p>
        <a class="btn primary" href="/auth/google/calendar">Connect Google Calendar</a>`}
    </section>
    <section class="panel narrow">
      <h3>Calendar feed (any calendar app)</h3>
      <p class="muted small">A private link to your homework in iCalendar format. Apple Calendar and Outlook refresh it often;
        Google Calendar only every few hours, so use the sync above for Google.</p>
      <div class="copy-row">
        <input id="feed-url" readonly value="${esc(feed)}" aria-label="Feed URL">
        <button class="btn" data-action="copy-feed">Copy</button>
      </div>
      <div class="form-row">
        <a class="btn small" href="${esc(webcal)}">Open in Apple Calendar / Outlook</a>
        <a class="btn small" target="_blank" rel="noopener" href="https://calendar.google.com/calendar/r?cid=${encodeURIComponent(webcal)}">Add to Google Calendar</a>
        <button class="btn small ghost" data-action="rotate-feed" title="Old link stops working">Reset link</button>
      </div>
    </section>`;
}

// ---------------------------------------------------------------- mutations

function upsertLocal(hw) {
  const list = state.data.homework;
  const i = list.findIndex((h) => h.id === hw.id);
  if (i >= 0) list[i] = hw; else list.push(hw);
  list.sort((a, b) => a.due_at - b.due_at);
  if (hw.sync_error) toast(`Saved, but Google Calendar sync failed: ${hw.sync_error}`, true);
}

async function saveForm(form) {
  const fd = new FormData(form);
  const remind = fd.get('remind_minutes');
  const body = {
    title: fd.get('title'), notes: fd.get('notes'),
    course_id: fd.get('course_id') ? Number(fd.get('course_id')) : null,
    due_date: fd.get('due_date'), due_time: fd.get('due_time') || null, tz: tz(),
    remind_minutes: remind === '' ? null : Number(remind),
  };
  const editing = state.hwEditing;
  const btn = form.querySelector('[type=submit]');
  btn.disabled = true;
  try {
    const hw = await api(editing ? `/api/homework/${editing}` : '/api/homework', { method: editing ? 'PATCH' : 'POST', body });
    upsertLocal(hw);
    state.hwEditing = null;
    state.hwCourse = body.course_id; // keep the course selected for the next entry
    if (!hw.sync_error) toast(editing ? 'Homework updated.' : 'Homework added.');
    render();
  } catch (err) {
    btn.disabled = false;
    toast(err.message, true);
  }
}

async function toggleDone(id, done) {
  const hw = state.data.homework.find((h) => h.id === id);
  const before = hw.done;
  hw.done = done ? 1 : 0;
  render();
  try {
    upsertLocal(await api(`/api/homework/${id}`, { method: 'PATCH', body: { done: Boolean(done) } }));
    render();
  } catch (err) {
    hw.done = before;
    render();
    toast(err.message, true);
  }
}

async function remove(id) {
  const hw = state.data.homework.find((h) => h.id === id);
  if (!confirm(`Delete "${hw.title}"?`)) return;
  try {
    await api(`/api/homework/${id}`, { method: 'DELETE' });
    state.data.homework = state.data.homework.filter((h) => h.id !== id);
    if (state.hwEditing === id) state.hwEditing = null;
    render();
  } catch (err) { toast(err.message, true); }
}

export async function gcalResync({ quiet = false } = {}) {
  let synced = 0, failed = 0, r;
  try {
    do {
      r = await api('/api/gcal/resync', { method: 'POST' });
      synced += r.synced; failed += r.failed;
    } while (r.remaining > 0 && r.synced > 0);
    await reload();
    render();
    if (failed) toast(`${failed} homework item(s) could not be synced. Check Settings.`, true);
    else if (!quiet || synced) toast(synced ? `Synced ${synced} homework item(s) to Google Calendar.` : 'Google Calendar is up to date.');
  } catch (err) { toast(err.message, true); }
}

// ---------------------------------------------------------------- event handlers (return true if handled)

export async function onClick(e) {
  const t = e.target;
  const item = t.closest('[data-hw]');
  const id = item && Number(item.dataset.hw);
  const action = t.closest('[data-action]')?.dataset.action;

  if (t.closest('[data-hw-edit]')) {
    state.hwEditing = id;
    state.tab = 'homework';
    history.replaceState(null, '', '#homework');
    render();
    document.querySelector('#hw-form input[name=title]')?.focus();
    return true;
  }
  if (t.closest('[data-hw-delete]')) { remove(id); return true; }
  if (t.closest('[data-hw-cancel]')) { state.hwEditing = null; render(); return true; }

  if (action === 'gcal-resync') { gcalResync(); return true; }
  if (action === 'gcal-disconnect') {
    if (!confirm('Disconnect Google Calendar? Existing events stay in your Google calendar "Homework (Attendance80)", and you can delete that calendar in Google Calendar.')) return true;
    try {
      await api('/api/gcal/disconnect', { method: 'POST' });
      await reload(); render(); toast('Google Calendar disconnected.');
    } catch (err) { toast(err.message, true); }
    return true;
  }
  if (action === 'copy-feed') {
    const input = document.getElementById('feed-url');
    try { await navigator.clipboard.writeText(input.value); toast('Link copied.'); }
    catch { input.select(); toast('Press Cmd/Ctrl+C to copy.'); }
    return true;
  }
  if (action === 'rotate-feed') {
    if (!confirm('Create a new feed link? Calendars subscribed to the old link will stop updating.')) return true;
    try {
      await api('/api/feed/rotate', { method: 'POST' });
      await reload(); render(); toast('New feed link created.');
    } catch (err) { toast(err.message, true); }
    return true;
  }
  return false;
}

export function onChange(e) {
  const t = e.target;
  if (t.matches('[data-hw-done]')) { toggleDone(Number(t.closest('[data-hw]').dataset.hw), t.checked); return true; }
  const form = t.closest('#hw-form');
  if (!form) return false;
  if (t.name === 'course_id') {
    form.elements.due_session.innerHTML = sessionOptions(t.value ? Number(t.value) : null);
  }
  if (t.name === 'due_session' && t.value) {
    const ms = Number(t.value);
    form.elements.due_date.value = localDate(ms);
    form.elements.due_time.value = localTime(ms);
  }
  return true;
}

export function onSubmit(e) {
  if (e.target.id !== 'hw-form') return false;
  e.preventDefault();
  saveForm(e.target);
  return true;
}

// Handles ?gcal=connected / ?gcal_error=… after the Google Calendar OAuth redirect.
export function handleRedirectParams(params) {
  const err = params.get('gcal_error');
  if (params.get('gcal') === 'connected') {
    toast('Google Calendar connected. Syncing…');
    gcalResync({ quiet: true });
  } else if (err) {
    const msg = {
      scope: 'Calendar access was not granted. Tick the calendar permission on the Google screen.',
      refresh: 'Google did not grant offline access. Please try again.',
      config: 'Google Calendar sync is not configured on this server.',
      access_denied: 'Google Calendar connection was cancelled.',
      state: 'The connection expired. Please try again.',
    }[err] || 'Could not connect Google Calendar.';
    toast(msg, true);
  } else return;
  history.replaceState(null, '', location.pathname + location.hash);
}

