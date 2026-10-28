// Habits: a Todoist-style list to tick off what's due today, a HabitKit-style grid of every habit's recent days,
// and a page per habit with its streaks, a year heatmap and charts. Check-ins are saved per local day (see
// public/habits.js); the page updates at once and puts things back if the save fails.
import { state, esc, api, toast, render } from './app.js';
import * as H from './habits.js';

const $app = document.getElementById('app');
const VIEW_KEY = 'a80-habit-view';
const GRID_WEEKS = 26, HEAT_WEEKS = 53;
const MILESTONES = [7, 14, 30, 50, 100, 200, 365];

// ---------------------------------------------------------------- data

const data = () => state.data.habits;
const habits = () => data().list;
const active = () => habits().filter((h) => !h.archived);
const byId = (id) => habits().find((h) => h.id === id);
const today = () => H.localKey();
const startOf = (h) => H.localKey(h.created_at);

// Check-ins per habit: Map(habit id → Map(day → count)), built once per load of /api/data and kept up to date.
const index = new WeakMap();
function counts(id) {
  const d = data();
  let m = index.get(d);
  if (!m) {
    m = new Map();
    for (const l of d.logs) { if (!m.has(l.habit_id)) m.set(l.habit_id, new Map()); m.get(l.habit_id).set(l.day, l.count); }
    index.set(d, m);
  }
  if (!m.has(id)) m.set(id, new Map());
  return m.get(id);
}
const statsOf = (h) => H.habitStats(h, counts(h.id), { today: today(), start: startOf(h) });

let view = 'list';
try { view = localStorage.getItem(VIEW_KEY) === 'grid' ? 'grid' : 'list'; } catch { /* storage unavailable */ }
const setView = (v) => { view = v; try { localStorage.setItem(VIEW_KEY, v); } catch { /* storage unavailable */ } render(); };

// Due today, in order, for the Today page, the sidebar count and the 1–9 keys.
export function dueToday() {
  return active().map((h) => ({ h, s: statsOf(h) })).filter((x) => x.s.due);
}
export const leftToday = () => dueToday().filter((x) => !x.s.todayDone).length;

// ---------------------------------------------------------------- check-ins

const saving = new Map(); // habit id + day → latest count asked for, so quick taps are sent in order

async function setCount(id, day, count) {
  const h = byId(id);
  if (!h) return;
  const c = counts(id);
  const before = c.get(day) || 0;
  const was = statsOf(h);
  if (count) c.set(day, count); else c.delete(day);
  const now = statsOf(h);
  render();
  if (day === today() && now.todayDone && !was.todayDone) {
    document.querySelector(`[data-habit="${id}"] .hb-check`)?.classList.add('pop');
    if (now.streak > was.streak && MILESTONES.includes(now.streak)) toast(`${H.streakLabel(now.streak, now.unit)} in a row: ${h.name}. Keep going.`);
  }
  const key = `${id}|${day}`;
  saving.set(key, count);
  try {
    await api(`/api/habits/${id}/log`, { method: 'PUT', body: { day, count } });
  } catch (err) {
    if (saving.get(key) === count) {
      if (before) c.set(day, before); else c.delete(day);
      render();
    }
    toast(err.message, true);
  } finally {
    if (saving.get(key) === count) saving.delete(key);
  }
}

// One tap: one more check-in, or back to none once the day is complete. `back` takes one off.
export function check(id, { back = false, day = today() } = {}) {
  const h = byId(id);
  if (!h) return;
  const n = counts(id).get(day) || 0;
  const target = h.target || 1;
  setCount(id, day, back ? Math.max(0, n - 1) : n >= target ? 0 : n + 1);
}
// A past day in the strip or the grid: done or not, in one tap.
const toggleDay = (id, day) => {
  const h = byId(id);
  if (!h || day > today()) return;
  setCount(id, day, (counts(id).get(day) || 0) >= (h.target || 1) ? 0 : h.target || 1);
};

// ---------------------------------------------------------------- saving habits

async function create(body) {
  try {
    const row = await api('/api/habits', { method: 'POST', body });
    habits().push(row);
    state.hbAdding = false;
    state.hbEditing = null;
    render();
    toast(`Added “${row.name}” · ${H.scheduleLabel(row.schedule)}.`);
    return row;
  } catch (err) { toast(err.message, true); return null; }
}

async function update(id, body, msg) {
  try {
    const row = await api(`/api/habits/${id}`, { method: 'PATCH', body });
    const list = habits();
    list[list.findIndex((h) => h.id === id)] = row;
    state.hbEditing = null;
    render();
    if (msg) toast(msg);
  } catch (err) { toast(err.message, true); }
}

async function remove(id) {
  const h = byId(id);
  if (!h || !confirm(`Delete “${h.name}” and all its check-ins? Archiving keeps the history instead.`)) return;
  try {
    await api(`/api/habits/${id}`, { method: 'DELETE' });
    data().list = habits().filter((x) => x.id !== id);
    state.hbOpen = null;
    state.hbEditing = null;
    render();
    toast(`Deleted “${h.name}”.`);
  } catch (err) { toast(err.message, true); }
}

async function move(id, delta) {
  const list = active();
  const i = list.findIndex((h) => h.id === id), j = i + delta;
  if (i < 0 || j < 0 || j >= list.length) return;
  [list[i], list[j]] = [list[j], list[i]];
  list.forEach((h, k) => { h.position = k; });
  data().list = [...list, ...habits().filter((h) => h.archived)];
  render();
  api('/api/habits/order', { method: 'POST', body: { ids: list.map((h) => h.id) } }).catch((err) => toast(err.message, true));
}

// ---------------------------------------------------------------- pieces

const color = (h) => `--hc:var(--c-${H.COLORS.includes(h.color) ? h.color : 'blue'})`;
const nameOf = (h) => `${h.icon ? `<span class="hb-icon" aria-hidden="true">${esc(h.icon)}</span>` : ''}${esc(h.name)}`;
const pct = (r) => (r == null ? '–' : `${Math.round(r * 100)}%`);
const fmtDay = (key, opts = { weekday: 'short', day: 'numeric', month: 'short' }) => new Date(H.keyMs(key)).toLocaleDateString([], { ...opts, timeZone: 'UTC' });
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;

// The round button: a ring that fills as check-ins add up to the target, then a filled circle with a tick.
function checkButton(h, s) {
  const target = h.target || 1;
  const frac = Math.min(1, s.today / target);
  const C = 2 * Math.PI * 10;
  const label = target > 1 ? `${h.name}: ${s.today} of ${target}${h.unit ? ` ${h.unit}` : ''} today` : `${h.name}: ${s.todayDone ? 'done' : 'not done'} today`;
  return `<button class="hb-check${s.todayDone ? ' done' : frac ? ' partial' : ''}" data-hb-check title="${target > 1 ? 'Add one (right-click: take one off)' : s.todayDone ? 'Mark not done' : 'Mark done'}"
      aria-pressed="${s.todayDone}" aria-label="${esc(label)}">
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle class="ring" cx="12" cy="12" r="10"/><circle class="arc" cx="12" cy="12" r="10" stroke-dasharray="${C}" stroke-dashoffset="${C * (1 - frac)}"/>
        <path class="tick" d="m7.5 12.3 3 3 6-6.3"/></svg>
      ${target > 1 && !s.todayDone && s.today ? `<span class="hb-n">${s.today}</span>` : ''}
    </button>`;
}

function meta(h, s) {
  const bits = [`<span>${esc(H.scheduleLabel(h.schedule))}${h.target > 1 ? ` · ${h.target}${h.unit ? ` ${esc(h.unit)}` : '×'} a day` : ''}</span>`];
  if (s.streak) bits.push(`<span class="hb-streak" title="Current streak (best ${H.streakLabel(s.best, s.unit)})"><svg class="ic" aria-hidden="true"><use href="#i-flame"/></svg>${H.streakLabel(s.streak, s.unit)}</span>`);
  if (s.progress?.of) bits.push(`<span class="${s.progress.done >= s.progress.of ? 'hb-met' : ''}">${s.progress.done}/${s.progress.of} this ${h.schedule.type === 'weekly' ? 'week' : 'month'}</span>`);
  if (s.progress?.since != null) bits.push(`<span>last ${s.progress.since === 1 ? 'yesterday' : `${s.progress.since} days ago`}</span>`);
  if (!s.due) bits.push('<span class="muted">not due today</span>');
  return bits.join('');
}

// The last seven days, tappable to fill in a day you forgot.
function weekStrip(h) {
  const t = today(), c = counts(h.id), target = h.target || 1;
  const s = h.schedule;
  return `<div class="hb-week" role="group" aria-label="Last 7 days">${Array.from({ length: 7 }, (_, i) => {
    const d = H.addDays(t, i - 6), n = c.get(d) || 0;
    const off = s.type === 'days' && !s.days.includes(H.dow(d));
    const cls = n >= target ? 'done' : n ? 'partial' : off ? 'off' : '';
    return `<button class="hb-day ${cls}${d === t ? ' today' : ''}" data-hb-day="${d}" aria-pressed="${n >= target}"
      data-tip="${esc(`${fmtDay(d)}: ${n >= target ? 'done' : n ? `${n} of ${target}` : off ? 'day off' : 'not done'}`)}"
      aria-label="${esc(`${fmtDay(d)}, ${n >= target ? 'done' : 'not done'}`)}"><span>${H.DOW[H.dow(d) - 1][0]}</span></button>`;
  }).join('')}</div>`;
}

function row(h) {
  const s = statsOf(h);
  return `
    <div class="hb-row${s.todayDone ? ' is-done' : ''}${s.due ? '' : ' not-due'}" data-habit="${h.id}" style="${color(h)}">
      ${checkButton(h, s)}
      <div class="hb-main" data-hb-open tabindex="0" role="link" aria-label="${esc(h.name)}: stats and settings">
        <div class="hb-name">${nameOf(h)}</div>
        <div class="hb-meta">${meta(h, s)}</div>
      </div>
      ${weekStrip(h)}
      <div class="hb-actions">
        <button class="btn btn-borderless btn-small" data-hb-edit title="Edit" aria-label="Edit"><svg class="ic" aria-hidden="true"><use href="#i-edit"/></svg></button>
      </div>
    </div>`;
}

// HabitKit-style: one square per day, a column per week, the habit's colour where it was done.
function cells(h, weeks, { tip = true } = {}) {
  const t = today(), c = counts(h.id), target = h.target || 1;
  const start = statsOf(h).start;
  const first = H.addDays(H.weekOf(t), -(weeks - 1) * 7);
  const out = [];
  for (let i = 0; i < weeks * 7; i++) {
    const d = H.addDays(first, i);
    if (d > t) { out.push('<i class="future"></i>'); continue; }
    const n = c.get(d) || 0;
    const lvl = n >= target ? 4 : n ? Math.max(1, Math.round((n / target) * 3)) : 0;
    out.push(`<i class="l${lvl}${d === t ? ' today' : ''}${d < start && !n ? ' pre' : ''}" data-hb-cell="${d}"${tip ? ` data-tip="${esc(`${fmtDay(d, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}: ${n >= target ? 'done' : n ? `${n} of ${target}` : 'not done'}`)}"` : ''}></i>`);
  }
  return out.join('');
}

function card(h) {
  const s = statsOf(h);
  return `
    <article class="hb-card${s.todayDone ? ' is-done' : ''}" data-habit="${h.id}" style="${color(h)}">
      <header>
        <div class="hb-main" data-hb-open tabindex="0" role="link" aria-label="${esc(h.name)}: stats and settings">
          <div class="hb-name">${nameOf(h)}</div>
          <div class="hb-meta">${meta(h, s)}</div>
        </div>
        ${checkButton(h, s)}
      </header>
      <div class="hb-grid-wrap"><div class="hb-grid" role="img" aria-label="${esc(`${h.name}: ${s.total} days done in the last ${GRID_WEEKS} weeks or so`)}">${cells(h, GRID_WEEKS)}</div></div>
    </article>`;
}

// ---------------------------------------------------------------- forms

const SCHEDULES = [['daily', 'Every day'], ['days', 'Some days'], ['weekly', 'Times a week'], ['monthly', 'Times a month'], ['interval', 'Every few days']];

function form(h = null, preset = null) {
  const v = h || preset || { name: '', icon: '', color: 'blue', schedule: { type: 'daily' }, target: 1, unit: '' };
  const s = v.schedule;
  const days = s.type === 'days' ? s.days : [1, 2, 3, 4, 5];
  const n = s.n || (s.type === 'interval' ? 2 : 3);
  const id = h ? h.id : 'new';
  return `
    <form class="hb-form" id="hb-form" data-id="${id}" autocomplete="off" style="${color(v)}">
      <div class="hb-form-top">
        <input class="field hb-emoji" name="icon" value="${esc(v.icon || '')}" maxlength="8" placeholder="🙂" aria-label="Emoji (optional)">
        <input class="field hb-title" name="name" value="${esc(v.name)}" maxlength="60" required placeholder="Habit name, e.g. Read" aria-label="Name">
      </div>
      <div class="hb-field"><span>Repeat</span>
        <div class="seg" role="radiogroup" aria-label="Repeat">${SCHEDULES.map(([k, l]) => `<label class="segment"><input type="radio" name="type" value="${k}" ${s.type === k ? 'checked' : ''}><span>${l}</span></label>`).join('')}</div>
      </div>
      <div class="hb-field hb-when-days"><span>On</span>
        <div class="hb-dows">${H.DOW.map((d, i) => `<label class="hb-dow"><input type="checkbox" name="days" value="${i + 1}" ${days.includes(i + 1) ? 'checked' : ''}><span>${d}</span></label>`).join('')}</div>
      </div>
      <div class="hb-field hb-when-n"><span>How often</span>
        <span class="input-suffix"><input class="field" type="number" name="n" min="1" max="365" value="${n}" aria-label="How often">
          <span class="hb-n-suffix"><span data-for="weekly">times a week</span><span data-for="monthly">times a month</span><span data-for="interval">days apart</span></span></span>
      </div>
      <div class="hb-field"><span>Each day</span>
        <span class="input-suffix"><input class="field" type="number" name="target" min="1" max="99" value="${v.target || 1}" aria-label="Check-ins that complete a day">
          <input class="field hb-unit" name="unit" value="${esc(v.unit || '')}" maxlength="20" placeholder="times" aria-label="Unit, e.g. glasses"></span>
      </div>
      <div class="hb-field"><span>Colour</span>
        <div class="swatches">${H.COLORS.map((c) => `<label class="swatch" style="--hc:var(--c-${c})" title="${c}"><input type="radio" name="color" value="${c}" ${v.color === c ? 'checked' : ''} aria-label="${c}"><span></span></label>`).join('')}</div>
      </div>
      <div class="hb-form-actions">
        <button class="btn btn-primary btn-small" type="submit">${h ? 'Save' : 'Add habit'}</button>
        <button class="btn btn-small" type="button" data-hb-cancel>Cancel</button>
        ${h ? `<span class="spacer"></span>
          <button class="btn btn-borderless btn-small" type="button" data-hb-archive>${h.archived ? 'Restore' : 'Archive'}</button>
          <button class="btn btn-borderless btn-small bad" type="button" data-hb-delete>Delete</button>` : ''}
      </div>
    </form>`;
}

function readForm(f) {
  const fd = new FormData(f);
  const type = fd.get('type');
  const n = Number(fd.get('n'));
  const schedule = type === 'days' ? { type, days: fd.getAll('days').map(Number) } : type === 'daily' ? { type } : { type, n };
  return { name: fd.get('name'), icon: fd.get('icon'), color: fd.get('color'), schedule, target: Number(fd.get('target')), unit: fd.get('unit') };
}

// The Todoist-style add row: type a sentence, see what was understood, Enter adds it.
function quickAdd() {
  if (state.hbEditing === 'new') return `<div class="hb-adding">${form(null, state.hbPreset)}</div>`;
  if (!state.hbAdding) {
    return `<button class="add-row" data-hb-add><span class="plus" aria-hidden="true"><svg class="ic sm"><use href="#i-plus"/></svg></span>Add habit<kbd>N</kbd></button>`;
  }
  return `
    <form class="quick-add" id="hb-quick" autocomplete="off">
      <input class="field" name="q" maxlength="120" placeholder="e.g. Gym mon wed fri · Read 3x a week · 💧 Water 8 glasses a day" aria-label="New habit" required>
      <div class="quick-foot">
        <span class="quick-chips" id="hb-quick-chips"><span class="muted">Every day</span></span>
        <span class="spacer"></span>
        <button class="btn btn-borderless btn-small" type="button" data-hb-more>More options</button>
        <button class="btn btn-small" type="button" data-hb-cancel>Cancel</button>
        <button class="btn btn-primary btn-small" type="submit">Add</button>
      </div>
    </form>`;
}

function quickChips(q) {
  const p = H.parseHabit(q);
  return [p.icon ? `<span class="chip">${esc(p.icon)}</span>` : '', ...(p.chips.length ? p.chips : ['Every day']).map((c, i) => `<span class="chip${p.chips.length ? ' on' : ''}">${i === 0 ? '<svg class="ic" aria-hidden="true"><use href="#i-refresh"/></svg>' : ''}${esc(c)}</span>`)].join('');
}

// ---------------------------------------------------------------- pages

export function subtitle() {
  if (state.hbOpen && byId(state.hbOpen)) return esc(H.scheduleLabel(byId(state.hbOpen).schedule));
  const due = dueToday();
  return due.length ? `${due.filter((x) => x.s.todayDone).length} of ${due.length} done today` : '';
}

export function renderHabits() {
  if (state.hbOpen && byId(state.hbOpen)) return renderDetail(byId(state.hbOpen));
  state.hbOpen = null;
  const list = active();
  const archived = habits().filter((h) => h.archived);
  const rows = list.map((h) => ({ h, s: statsOf(h) }));
  const due = rows.filter((x) => x.s.due), rest = rows.filter((x) => !x.s.due);
  const editing = (h) => state.hbEditing === h.id;
  const item = (h) => (editing(h) ? form(h) : view === 'grid' ? card(h) : row(h));

  $app.innerHTML = `
    <div class="hb-toolbar">
      <span class="scope" role="group" aria-label="View">
        <button class="scope-chip" data-hb-view="list" aria-pressed="${view === 'list'}" title="List (V)">List</button>
        <button class="scope-chip" data-hb-view="grid" aria-pressed="${view === 'grid'}" title="Grid (V)">Grid</button>
      </span>
      ${list.length ? `<span class="muted small">${streakSummary(rows)}</span>` : ''}
    </div>
    ${!list.length && !state.hbAdding && state.hbEditing !== 'new' ? `
      <div class="hb-empty">
        <p><b>Build habits that stick.</b> Add one, tick it off each day, and watch the grid fill in.</p>
        <p class="muted small">Try “Gym mon wed fri”, “Read 20 pages 3x a week” or “💧 Water 8 glasses a day”.</p>
      </div>` : ''}
    <section class="panel hb-panel">
      ${list.length ? `<h3><span>Today</span><span class="muted small">${fmtDay(today(), { weekday: 'long', day: 'numeric', month: 'long' })}</span></h3>` : ''}
      <div class="${view === 'grid' ? 'hb-cards' : 'list'}">${due.map(({ h }) => item(h)).join('')}</div>
      ${list.length && !due.length ? '<p class="empty">Nothing due today.</p>' : ''}
      ${quickAdd()}
    </section>
    ${rest.length ? `
    <section class="panel hb-panel">
      <h3><span>Not due today</span><span class="muted small">${rest.length}</span></h3>
      <div class="${view === 'grid' ? 'hb-cards' : 'list'}">${rest.map(({ h }) => item(h)).join('')}</div>
    </section>` : ''}
    ${archived.length ? `
    <details class="panel hb-archived" ${state.hbShowArchived ? 'open' : ''}>
      <summary><h3>Archived <span class="muted small">${archived.length}</span></h3></summary>
      <div class="list">${archived.map((h) => (editing(h) ? form(h) : `
        <div class="hb-row archived" data-habit="${h.id}" style="${color(h)}">
          <span class="hb-dot" aria-hidden="true"></span>
          <div class="hb-main" data-hb-open tabindex="0" role="link"><div class="hb-name">${nameOf(h)}</div><div class="hb-meta"><span>${esc(H.scheduleLabel(h.schedule))}</span><span>${plural(statsOf(h).total, 'day')} done</span></div></div>
          <button class="btn btn-borderless btn-small" data-hb-archive>Restore</button>
        </div>`)).join('')}</div>
    </details>` : ''}`;
  if (state.hbAdding) { const q = document.querySelector('#hb-quick [name=q]'); if (q) { q.value = state.hbDraft || ''; q.focus(); refreshChips(); } }
  if (state.hbEditing) document.querySelector('#hb-form [name=name]')?.focus();
  scrollGrids();
}

function streakSummary(rows) {
  const best = rows.reduce((a, x) => (x.s.streak > (a?.s.streak || 0) ? x : a), null);
  return best ? `Longest streak: ${esc(best.h.name)}, ${H.streakLabel(best.s.streak, best.s.unit)}` : '';
}

const scrollGrids = () => { for (const el of $app.querySelectorAll('.hb-grid-wrap, .fx-heat-scroll')) el.scrollLeft = el.scrollWidth; };

function renderDetail(h) {
  const s = statsOf(h);
  const t = today();
  const months = H.monthTotals(h, counts(h.id), t);
  const topM = Math.max(1, ...months.map((m) => m.days));
  const topW = Math.max(1, ...s.weekdays);
  const first = H.addDays(H.weekOf(t), -(HEAT_WEEKS - 1) * 7);
  const monthLabels = [];
  let last = '';
  for (let w = 0; w < HEAT_WEEKS; w++) {
    const sun = H.addDays(first, w * 7 + 6);
    if (H.monthOf(sun) !== last) { monthLabels.push(`<span style="grid-column:${w + 1}">${fmtDay(sun, { month: 'short' })}</span>`); last = H.monthOf(sun); }
  }
  const target = h.target || 1;
  const tile = (n, l, extra = '') => `<div class="stat"><span class="n">${n}</span><span class="l">${l}</span>${extra}</div>`;
  const sinceLoaded = data().since > startOf(h);

  $app.innerHTML = `
    <div class="hb-detail" data-habit="${h.id}" style="${color(h)}">
      <button class="btn btn-borderless btn-small hb-back" data-hb-back><svg class="ic sm" aria-hidden="true" style="transform:rotate(90deg)"><use href="#i-chevron"/></svg>All habits<kbd>Esc</kbd></button>
      <div class="hb-hero">
        <div class="hb-hero-name">${nameOf(h)}</div>
        <div class="hb-today">
          ${target > 1 ? `<button class="btn btn-small" data-hb-minus aria-label="One less" ${s.today ? '' : 'disabled'}>−</button>
            <span class="hb-count"><b>${s.today}</b>/${target}${h.unit ? ` ${esc(h.unit)}` : ''}</span>` : ''}
          ${checkButton(h, s)}
          <span class="muted small">${s.todayDone ? 'Done today' : s.due ? 'Due today' : 'Not due today'}</span>
        </div>
      </div>
      ${state.hbEditing === h.id ? form(h) : ''}
      <section class="panel">
        <div class="ov-stats fx-stats">
          ${tile(`${s.streak}<small> ${s.unit}${s.streak === 1 ? '' : 's'}</small>`, 'current streak')}
          ${tile(`${s.best}<small> ${s.unit}${s.best === 1 ? '' : 's'}</small>`, 'best streak')}
          ${tile(pct(s.rate), 'last 30 days', s.rate != null ? `<span class="fx-goalbar" aria-hidden="true"><i style="transform:scaleX(${s.rate})"></i></span>` : '')}
          ${tile(s.total, target > 1 ? `days done · ${s.checkins} ${esc(h.unit || 'check-ins')}` : 'days done')}
        </div>
      </section>
      <section class="panel">
        <h3><span>Last 12 months</span><span class="muted small">tap a day to change it</span></h3>
        <div class="fx-heat-scroll">
          <div class="fx-heat hb-heat" role="img" aria-label="${esc(h.name)} per day over the last year">
            <div class="fx-months">${monthLabels.join('')}</div>
            <div class="fx-dows"><span></span><span>Mon</span><span></span><span>Wed</span><span></span><span>Fri</span><span></span></div>
            <div class="fx-cells">${cells(h, HEAT_WEEKS)}</div>
          </div>
        </div>
        ${sinceLoaded ? `<p class="muted small">Stats cover the last ${Math.round(400 / 30.4)} months.</p>` : ''}
      </section>
      <section class="panel">
        <h3>Days done per month</h3>
        <div class="fx-week hb-months">
          ${months.map((m) => `<div class="fx-col${m.month === H.monthOf(t) ? ' today' : ''}" data-tip="${esc(`${fmtDay(`${m.month}-01`, { month: 'long', year: 'numeric' })}: ${plural(m.days, 'day')}`)}">
            <div class="fx-plot">${m.days ? `<div class="fx-bar-v" style="height:${Math.max(3, (m.days / topM) * 100)}%"></div>` : ''}</div>
            <span class="fx-day">${fmtDay(`${m.month}-01`, { month: 'narrow' })}</span></div>`).join('')}
        </div>
      </section>
      <section class="panel">
        <h3>By weekday</h3>
        <div class="fx-rows">${s.weekdays.map((n, i) => `
          <div class="fx-row hb-wd"><span class="fx-name">${H.DOW[i]}</span><span class="fx-meter" aria-hidden="true"><i style="transform:scaleX(${n / topW})"></i></span><span class="fx-val">${plural(n, 'day')}</span><span></span></div>`).join('')}</div>
      </section>
      ${state.hbEditing === h.id ? '' : `<div class="hb-detail-actions">
        <button class="btn btn-small" data-hb-edit><svg class="ic sm" aria-hidden="true"><use href="#i-edit"/></svg>Edit</button>
        <button class="btn btn-borderless btn-small" data-hb-archive>${h.archived ? 'Restore' : 'Archive'}</button>
        <button class="btn btn-borderless btn-small bad" data-hb-delete>Delete</button>
      </div>`}
    </div>`;
  scrollGrids();
}

// The Habits block on Today: what's due, tickable in place.
export function todayPanel() {
  if (!active().length) return '';
  const due = dueToday();
  const done = due.filter((x) => x.s.todayDone).length;
  return `
    <section class="panel">
      <h3><span>Habits${due.length ? ` <span class="muted small">${done}/${due.length}</span>` : ''}</span><a href="#habits" data-goto="habits">all habits →</a></h3>
      ${due.length ? `<div class="list hb-compact">${due.map(({ h, s }) => `
        <div class="hb-row${s.todayDone ? ' is-done' : ''}" data-habit="${h.id}" style="${color(h)}">
          ${checkButton(h, s)}
          <div class="hb-main" data-hb-open tabindex="0" role="link"><div class="hb-name">${nameOf(h)}</div><div class="hb-meta">${meta(h, s)}</div></div>
        </div>`).join('')}</div>` : '<p class="empty">No habits due today.</p>'}
    </section>`;
}

// ---------------------------------------------------------------- palette and keys

export function paletteItems(go) {
  if (!state.data?.habits) return [];
  const items = [];
  const open = (id) => () => { state.hbOpen = id; state.hbEditing = null; go('habits'); };
  items.push({ id: 'hb:new', section: 'Actions', label: 'New habit', icon: 'i-plus', home: true, keywords: 'habit routine streak tracker add',
    run: () => { go('habits'); openAdd(); } });
  for (const { h, s } of dueToday().filter((x) => !x.s.todayDone)) {
    items.push({ id: `hb:check:${h.id}`, recentId: '', section: 'Habits', label: `Check off: ${h.name}`, icon: 'i-check', home: true,
      sub: s.streak ? `${H.streakLabel(s.streak, s.unit)} streak` : H.scheduleLabel(h.schedule), keywords: 'habit done tick', run: () => check(h.id) });
  }
  for (const h of active()) {
    const s = statsOf(h);
    items.push({ id: `hb:${h.id}`, section: 'Habits', label: h.name, icon: 'i-habits', keywords: 'habit',
      sub: [H.scheduleLabel(h.schedule), s.streak ? `${H.streakLabel(s.streak, s.unit)} streak` : '', s.todayDone ? 'done today' : ''].filter(Boolean).join(' · '), run: open(h.id) });
  }
  return items;
}

export function fallback(q, go) {
  // Only when it reads like a habit ("… 3x a week"), so a plain search never turns into one.
  const p = H.parseHabit(q);
  if (!p.name || !p.chips.length) return [];
  return [{ id: 'hb:add:q', recentId: '', section: 'Add', label: `Add habit: ${p.name}`, icon: 'i-plus',
    sub: [p.icon, ...p.chips].filter(Boolean).join(' · ') || 'every day', run: () => { go('habits'); createFromText(q); } }];
}

function openAdd(preset = null) {
  if (preset) { state.hbPreset = preset; state.hbEditing = 'new'; state.hbAdding = false; }
  else { state.hbAdding = true; state.hbEditing = null; }
  state.hbOpen = null;
  render();
}

function createFromText(q) {
  const p = H.parseHabit(q);
  if (!p.name) return toast('Give the habit a name.', true);
  state.hbDraft = '';
  return create({ name: p.name, icon: p.icon, schedule: p.schedule, target: p.target, unit: p.unit, color: H.COLORS[habits().length % (H.COLORS.length - 1)] });
}

function refreshChips() {
  const q = document.querySelector('#hb-quick [name=q]');
  const el = document.getElementById('hb-quick-chips');
  if (q && el) el.innerHTML = quickChips(q.value);
}

export const SHORTCUTS = [[['N'], 'Habits / Countdowns: add one'], [['1', '–', '9'], 'Habits: tick off the first nine due today'], [['V'], 'Habits: list or grid']];

export function onPageKey(e) {
  const k = e.key.toLowerCase();
  if (state.hbOpen && k === 'escape') { state.hbOpen = null; render(); return true; }
  if (k === 'n') { e.preventDefault(); openAdd(); return true; }
  if (k === 'v' && !state.hbOpen) { e.preventDefault(); setView(view === 'grid' ? 'list' : 'grid'); return true; }
  if (/^[1-9]$/.test(k) && !state.hbOpen) {
    const x = dueToday()[Number(k) - 1];
    if (x) { e.preventDefault(); check(x.h.id); }
    return Boolean(x);
  }
  if (state.hbOpen && (k === ' ' || k === 'enter') && !e.target.closest('button, a, input')) { e.preventDefault(); check(state.hbOpen); return true; }
  return false;
}

// Esc closes the add row or a form (even while typing in it).
export function onKeydown(e) {
  if (e.key !== 'Escape' || !e.target.closest?.('#hb-quick, #hb-form')) return false;
  e.preventDefault();
  state.hbAdding = false; state.hbEditing = null; state.hbDraft = '';
  render();
  return true;
}

// ---------------------------------------------------------------- events

export function onClick(e) {
  const t = e.target;
  const v = t.closest('[data-hb-view]');
  if (v) { setView(v.dataset.hbView); return true; }
  if (t.closest('[data-hb-add]')) { openAdd(); return true; }
  if (t.closest('[data-hb-cancel]')) { state.hbAdding = false; state.hbEditing = null; state.hbDraft = ''; render(); return true; }
  if (t.closest('[data-hb-more]')) {
    const p = H.parseHabit(document.querySelector('#hb-quick [name=q]')?.value || '');
    openAdd({ name: p.name, icon: p.icon, schedule: p.schedule, target: p.target, unit: p.unit, color: 'blue' });
    return true;
  }
  if (t.closest('[data-hb-back]')) { state.hbOpen = null; state.hbEditing = null; render(); return true; }
  const el = t.closest('[data-habit]');
  if (!el) return false;
  const id = Number(el.dataset.habit);
  if (t.closest('[data-hb-check]')) { check(id, { back: e.shiftKey }); return true; }
  if (t.closest('[data-hb-minus]')) { check(id, { back: true }); return true; }
  const day = t.closest('[data-hb-day], [data-hb-cell]');
  if (day) { toggleDay(id, day.dataset.hbDay || day.dataset.hbCell); return true; }
  if (t.closest('[data-hb-edit]')) { state.hbEditing = id; render(); return true; }
  if (t.closest('[data-hb-delete]')) { remove(id); return true; }
  if (t.closest('[data-hb-archive]')) {
    const h = byId(id);
    update(id, { archived: !h.archived }, h.archived ? `Restored “${h.name}”.` : `Archived “${h.name}”. Its history is kept.`);
    return true;
  }
  if (t.closest('[data-hb-open]') && !t.closest('form')) { state.hbOpen = id; state.hbEditing = null; state.tab === 'habits' ? render() : document.querySelector('[data-tab="habits"]')?.click(); return true; }
  return false;
}

// Right-click on the button takes a check-in off.
document.addEventListener('contextmenu', (e) => {
  const b = e.target.closest?.('[data-hb-check]');
  if (!b) return;
  e.preventDefault();
  check(Number(b.closest('[data-habit]').dataset.habit), { back: true });
});
document.addEventListener('keydown', (e) => {
  if ((e.key === 'Enter' || e.key === ' ') && e.target.matches?.('[data-hb-open]')) { e.preventDefault(); e.target.click(); }
});

export function onInput(e) {
  if (!e.target.closest('#hb-quick')) return false;
  state.hbDraft = e.target.value;
  refreshChips();
  return true;
}

export function onSubmit(e) {
  if (e.target.id === 'hb-quick') {
    e.preventDefault();
    createFromText(new FormData(e.target).get('q'));
    return true;
  }
  if (e.target.id !== 'hb-form') return false;
  e.preventDefault();
  const body = readForm(e.target);
  if (body.schedule.type === 'days' && !body.schedule.days.length) { toast('Pick at least one day.', true); return true; }
  const id = e.target.dataset.id;
  if (id === 'new') create(body); else update(Number(id), body, 'Habit saved.');
  return true;
}

export function onChange(e) {
  // The colour swatches tint the form as you pick.
  if (e.target.name === 'color' && e.target.closest('#hb-form')) { e.target.closest('#hb-form').style.cssText = `--hc:var(--c-${e.target.value})`; return true; }
  return Boolean(e.target.closest('#hb-form'));
}
