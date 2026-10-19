// Homework tab, overview panel, session badges and calendar-integration settings.
import { state, esc, api, toast, render, reload, courseById, fmtTime, groupLabel, courseGroups } from './app.js';

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
export function dueLabel(hw) {
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
  if (hw.sync_error) return `<span class="sync bad" title="Google Calendar: ${esc(hw.sync_error)}">not synced</span>`;
  if (hw.gcal_event_id) return '<span class="sync ok" title="In Google Calendar">synced</span>';
  return '<span class="sync" title="Not synced yet">syncing</span>';
}

export function hwItem(hw, now = Date.now()) {
  const course = courseById()[hw.course_id];
  const remind = REMINDERS.find(([m]) => m === hw.remind_minutes)?.[1];
  return `
    <div class="hw-item ${hw.done ? 'done' : ''}" data-hw="${hw.id}">
      <label class="checkbox round hw-check" title="${hw.done ? 'Mark not done' : 'Mark done'}"><input type="checkbox" data-hw-done ${hw.done ? 'checked' : ''} aria-label="Done: ${esc(hw.title)}"><span></span></label>
      <div class="hw-main" data-hw-edit>
        <div class="hw-title">${esc(hw.title)}</div>
        ${hw.notes ? `<div class="hw-notes">${esc(hw.notes)}</div>` : ''}
        <div class="hw-meta">
          <span class="due ${hw.done ? '' : bucket(hw, now)}"><svg class="ic" aria-hidden="true"><use href="#i-calendar"/></svg>${esc(dueLabel(hw))}</span>
          ${hw.remind_minutes != null && !hw.done ? `<span title="Reminder">reminder ${esc(remind)}</span>` : ''}
          ${syncIcon(hw)}
          ${course ? `<span class="hw-course"><svg class="ic" aria-hidden="true"><use href="#i-hash"/></svg>${esc([course.name, groupLabel(courseGroups(course.id))].filter((x) => x && x !== 'All groups').join(' · '))}</span>` : ''}
        </div>
      </div>
      <div class="hw-actions">
        <button class="btn btn-borderless btn-small" data-hw-edit title="Edit" aria-label="Edit"><svg class="ic" aria-hidden="true"><use href="#i-edit"/></svg></button>
        <button class="btn btn-borderless btn-small" data-hw-delete title="Delete" aria-label="Delete"><svg class="ic" aria-hidden="true"><use href="#i-trash"/></svg></button>
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

// The quick-add box, used both to add homework (top of the list) and to edit an item in place.
function quickAdd(draft, editing) {
  const tracked = state.data.courses.filter((c) => c.tracked || c.id === draft.course_id);
  return `
    <form id="hw-form" class="qa" autocomplete="off">
      <div class="qa-body">
        <input class="qa-title" name="title" required maxlength="200" placeholder="e.g. TP 3 – SQL joins" value="${esc(draft.title)}" aria-label="What's due">
        <textarea class="qa-notes" name="notes" rows="1" maxlength="4000" placeholder="Notes" aria-label="Notes">${esc(draft.notes)}</textarea>
      </div>
      <div class="qa-opts">
        <input class="field" type="date" name="due_date" required value="${esc(draft.due_date)}" aria-label="Due date">
        <input class="field" type="time" name="due_time" value="${esc(draft.due_time || '')}" aria-label="Due time" title="Leave empty for end of day">
        <div class="select"><select name="course_id" aria-label="Course">
          <option value="">No course</option>
          ${tracked.map((c) => `<option value="${c.id}" ${c.id === draft.course_id ? 'selected' : ''}>${esc(c.name)}</option>`).join('')}
        </select></div>
        <div class="select"><select name="due_session" aria-label="Due at a session">${sessionOptions(draft.course_id)}</select></div>
        <div class="select"><select name="remind_minutes" aria-label="Reminder">
          ${REMINDERS.map(([m, l]) => `<option value="${m ?? ''}" ${m === draft.remind_minutes ? 'selected' : ''}>${l}</option>`).join('')}
        </select></div>
      </div>
      <div class="qa-foot">
        <span class="hint">No time means end of day.</span>
        <button class="btn" type="button" data-hw-cancel>Cancel</button>
        <button class="btn btn-primary" type="submit">${editing ? 'Save' : 'Add homework'}</button>
      </div>
    </form>`;
}

export function renderHomework() {
  const { homework } = state.data;
  const editing = state.hwEditing ? homework.find((h) => h.id === state.hwEditing) : null;
  const now = Date.now();
  const groups = Object.fromEntries([...BUCKETS, ['done']].map(([k]) => [k, []]));
  for (const hw of homework) groups[bucket(hw, now)].push(hw);
  groups.done.sort((a, b) => b.due_at - a.due_at);
  const integ = state.data.integrations;
  const item = (hw) => (hw.id === editing?.id ? quickAdd(editing, true) : hwItem(hw, now));
  const draft = {
    title: '', notes: '', course_id: state.hwCourse ?? null, due_date: localDate(Date.now() + DAY), due_time: '',
    remind_minutes: DEFAULT_REMINDER,
  };

  $app.innerHTML = `
    <section class="hw-lists">
      ${state.hwFormOpen && !editing ? quickAdd(draft, false)
        : '<button class="add-row" data-hw-add><span class="plus" aria-hidden="true"><svg class="ic"><use href="#i-plus"/></svg></span>Add homework</button>'}
      ${homework.length || state.hwFormOpen ? '' : '<p class="empty">Nothing due. Press <kbd>Q</kbd> to add homework.</p>'}
      ${BUCKETS.filter(([k]) => groups[k].length).map(([k, label]) => `
        <div class="hw-group ${k}">
          <h4>${label} <span class="muted">${groups[k].length}</span></h4>
          <div class="list">${groups[k].map(item).join('')}</div>
        </div>`).join('')}
      ${groups.done.length ? `
        <details class="hw-group done" ${editing?.done ? 'open' : ''}>
          <summary><h4>Done <span class="muted">${groups.done.length}</span></h4></summary>
          <div class="list">${groups.done.map(item).join('')}</div>
        </details>` : ''}
    </section>
    <p class="hint hw-tip">${integ?.gcal
      ? `Synced to Google Calendar (${esc(integ.gcal.email || '')}). Reminders arrive as Google Calendar notifications.`
      : 'Tip: connect Google Calendar in <a href="#settings" data-goto="settings">Settings</a> to get reminders on your phone.'}</p>`;
}

// Opens the quick-add box on the Homework page (sidebar button, Q, the palette).
export function openForm() {
  state.hwEditing = null;
  state.hwFormOpen = true;
  state.tab = 'homework';
  history.replaceState(null, '', '#homework');
  render();
  focusForm();
}

function focusForm() {
  const title = document.querySelector('#hw-form .qa-title');
  if (!title) return;
  title.focus();
  title.setSelectionRange(title.value.length, title.value.length);
  autosize(document.querySelector('#hw-form .qa-notes'));
  title.closest('form').scrollIntoView({ block: 'nearest' });
}

export function editHomework(id) {
  state.hwEditing = id;
  state.hwFormOpen = false;
  state.tab = 'homework';
  history.replaceState(null, '', '#homework');
  render();
  focusForm();
}

function closeForm() {
  state.hwEditing = null;
  state.hwFormOpen = false;
  render();
}

function autosize(ta) { if (ta) { ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 240) + 'px'; } }

// Overview panel: overdue + due within a week.
export function homeworkPanel() {
  const now = Date.now();
  const soon = state.data.homework.filter((h) => !h.done && h.due_at < now + 7 * DAY).slice(0, 6);
  const open = state.data.homework.filter((h) => !h.done).length;
  if (!soon.length) return '';
  return `
    <section class="panel">
      <h3><span>Homework</span><a href="#homework" data-goto="homework">all ${open} →</a></h3>
      <div class="list">${soon.map((hw) => hwItem(hw, now)).join('')}</div>
    </section>`;
}

// Homework due at a given session, shown as a badge on the session row.
export function homeworkForSession(e) {
  return state.data.homework.filter((h) => !h.done && h.course_id === e.course_id && h.due_at === e.start_at);
}

// One link per calendar, each subscribable in any calendar app. Both share one private token, so
// "Reset links" resets both. Google Calendar sync is an extra option for homework (instant, with reminders).
function feedRow(id, title, hint, url, extra = '') {
  const webcal = url.replace(/^https?:/, 'webcal:');
  return `
      <div class="feed" id="${id}-row">
        <h4>${title}</h4>
        <p class="muted small">${hint}</p>${extra}
        <div class="copy-row">
          <input class="field" id="${id}" readonly value="${esc(url)}" aria-label="${title} feed URL">
          <button class="btn btn-default" data-action="copy-feed" data-target="${id}">Copy</button>
        </div>
        <div class="form-row">
          <a class="btn btn-default btn-small" target="_blank" rel="noopener" href="https://calendar.google.com/calendar/r?cid=${encodeURIComponent(webcal)}">Add to Google Calendar</a>
          <a class="btn btn-default btn-small" href="${esc(webcal)}">Apple Calendar / Outlook</a>
        </div>
      </div>`;
}

// Which groups (calendars) the timetable link includes. It starts as the Count selection in the top bar; an
// empty list means every group and gives the plain link, so links shared before groups existed keep working.
let feedGroups = null;

export function timetableFeedUrl(ids = feedGroups ?? state.scope) {
  const base = state.data.integrations.feedUrl.replace(/\.ics$/, '/timetable.ics');
  return ids.length ? `${base}?groups=${ids.join(',')}` : base;
}

function timetableRow() {
  const cals = state.data.calendars;
  const valid = new Set(cals.map((c) => c.id));
  feedGroups = (feedGroups ?? state.scope).filter((id) => valid.has(id));
  const on = new Set(feedGroups);
  const chip = (id, label, pressed) => `<button class="scope-chip" data-feed-group="${id}" aria-pressed="${pressed}">${esc(label)}</button>`;
  const picker = cals.length < 2 ? '' : `
        <div class="scope feed-groups" role="group" aria-label="Groups in this calendar"><span class="scope-label">Groups</span>${chip('all', 'All', !on.size)}${cals.map((c) => chip(c.id, c.name, on.has(c.id))).join('')}</div>`;
  const hint = cals.length < 2 ? 'Every session of the groups you picked, as on Zeus.'
    : on.size ? `Only the sessions of ${esc(cals.filter((c) => on.has(c.id)).map((c) => c.name).join(' + '))}, as on Zeus. Each choice is its own link.`
      : 'Every session of all your groups, as on Zeus. Pick groups to get a link with only theirs.';
  return feedRow('timetable-url', 'Timetable', hint, timetableFeedUrl(feedGroups), picker);
}

// Same as the Count toggle: from All, a group alone; otherwise toggle it. None or every group means All.
function toggleFeedGroup(value) {
  const cals = state.data.calendars;
  const on = new Set(value === 'all' ? [] : feedGroups);
  if (value !== 'all') { const id = Number(value); if (on.has(id)) on.delete(id); else on.add(id); }
  feedGroups = on.size >= cals.length ? [] : cals.map((c) => c.id).filter((id) => on.has(id));
  const focused = document.activeElement?.dataset.feedGroup;
  document.getElementById('timetable-url-row').outerHTML = timetableRow();
  if (focused) document.querySelector(`[data-feed-group="${focused}"]`)?.focus();
}

export function integrationsPanel() {
  const integ = state.data.integrations;
  const feed = integ.feedUrl;
  const g = integ.gcal;
  return `
    <section class="panel narrow">
      <h3>Subscribe in your calendar app</h3>
      <p class="muted small">Private links that Google Calendar, Apple Calendar and Outlook keep up to date, including Zeus
        updates. Apple Calendar and Outlook check them often; Google Calendar every few hours.</p>
      ${timetableRow()}
      ${feedRow('feed-url', 'Homework <span class="chip">optional</span>', 'Your homework, on its due date.', feed)}
      <button class="btn btn-borderless btn-small" data-action="rotate-feed" title="Old links stop working">Reset links</button>
    </section>
    <section class="panel narrow">
      <h3>Google Calendar sync <span class="chip">homework</span></h3>
      ${!integ.gcalAvailable ? '<p class="muted">Not configured on this server.</p>' : g ? `
        <p>Connected as <b>${esc(g.email || 'your Google account')}</b>. Homework is kept in a calendar called
          <b>Homework (Attendance80)</b>. Changes appear immediately, and each reminder is a Google Calendar notification.
          Use this instead of the homework link above in Google Calendar.</p>
        ${g.error ? `<p class="note bad">${esc(g.error)}</p>` : ''}
        <div class="form-row">
          ${g.error ? '<a class="btn btn-primary" href="/auth/google/calendar">Reconnect</a>' : ''}
          <button class="btn btn-default" data-action="gcal-resync">Sync now</button>
          <button class="btn btn-borderless" data-action="gcal-disconnect">Disconnect</button>
        </div>` : `
        <p>For Google users, an alternative to the homework link: homework is pushed into its own calendar in your Google
          account, changes appear immediately, and reminders arrive as Google Calendar notifications. The app can only see
          the calendar it creates.</p>
        <a class="btn btn-default" href="/auth/google/calendar">Connect Google Calendar</a>`}
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
    if (!hw.sync_error) toast(editing ? 'Homework updated.' : `Added “${hw.title}”.`);
    render();
    if (!editing) focusForm(); // the quick-add box stays open for the next one
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

  if (t.closest('[data-hw-edit]') && id) { editHomework(id); return true; }
  if (t.closest('[data-hw-add]')) { openForm(); return true; }
  if (t.closest('[data-hw-delete]')) { remove(id); return true; }
  if (t.closest('[data-hw-cancel]')) { closeForm(); return true; }

  const group = t.closest('[data-feed-group]');
  if (group) { toggleFeedGroup(group.dataset.feedGroup); return true; }
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
    const input = document.getElementById(e.target.closest('[data-target]')?.dataset.target || 'feed-url');
    try { await navigator.clipboard.writeText(input.value); toast('Link copied.'); }
    catch { input.select(); toast('Press Cmd/Ctrl+C to copy.'); }
    return true;
  }
  if (action === 'rotate-feed') {
    if (!confirm('Create new feed links? Calendars subscribed to the old homework and timetable links will stop updating.')) return true;
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

export function onInput(e) {
  if (!e.target.matches('#hw-form .qa-notes')) return false;
  autosize(e.target);
  return true;
}

// Esc cancels the quick-add box; Cmd/Ctrl+Enter saves it from the notes.
export function onKeydown(e) {
  const form = e.target.closest?.('#hw-form');
  if (!form) return false;
  if (e.key === 'Escape') { e.preventDefault(); closeForm(); return true; }
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); form.requestSubmit(); return true; }
  return false;
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

