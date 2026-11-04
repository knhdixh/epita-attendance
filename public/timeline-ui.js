// Timeline: your weeks side by side, Gantt style. On top, each week's load: what classes, commitments and to-dos due
// ask of you against the hours you're available. Below, a row per commitment (drag it to move, drag an end to change
// its dates, drag across the empty row to add one), time away and breaks, each course with its deadlines and exams,
// projects with their tasks' dates, and countdowns. One sentence adds a commitment ("Thesis 10h/week until 15 dec",
// "Away 20-27 dec"); Working hours sets when you're available, which Today and the Calendar's "Plan my week" use too.
// A week's head opens it in the Calendar; a deadline opens the to-do; an exam opens its day.
import { state, esc, api, toast, render, courseById } from './app.js';
import * as T from './timeline.js';
import { COLORS, addDays, diffDays, localKey, weekOf } from './habits.js';
import { effectiveTag } from './stats.js';
import * as C from './countdowns.js';
import { effortOf } from './plan.js';
import { showDate } from './calendar-ui.js';
import { courseColors } from './calendar.js';

const $app = document.getElementById('app');
const SPANS = [[6, '6 weeks'], [12, '12 weeks'], [26, '6 months']];
const SPAN_KEY = 'a80-tl-weeks';

const isPhone = () => matchMedia('(max-width: 760px)').matches;
const list = () => state.data.commitments || (state.data.commitments = []);
const byId = (id) => list().find((c) => c.id === id);
const av = () => T.availabilityOf(state.data.user);
const hrs = (min) => { const h = Math.round(min / 6) / 10; return `${h % 1 ? h : Math.round(h)} h`; };
const hue = (c) => `--hc:var(--c-${COLORS.includes(c) ? c : 'blue'})`;
const fmt = (key, o = { day: 'numeric', month: 'short' }) => new Date(`${key}T12:00:00`).toLocaleDateString([], o);

function tl() {
  if (!state.tl) {
    let weeks = null;
    try { weeks = Number(localStorage.getItem(SPAN_KEY)); } catch { /* storage unavailable */ }
    state.tl = {
      from: addDays(weekOf(localKey()), -7),   // last week, so "this week" isn't on the edge
      weeks: SPANS.some(([n]) => n === weeks) ? weeks : isPhone() ? 6 : 12,
      adding: false, draft: '', editing: null, preset: null, hours: false,
    };
  }
  return state.tl;
}

export function enter() { const t = tl(); t.from = addDays(weekOf(localKey()), -7); }

// ---------------------------------------------------------------- saving

async function create(body) {
  try {
    const row = await api('/api/commitments', { method: 'POST', body });
    list().push(row);
    Object.assign(tl(), { adding: false, editing: null, draft: '', preset: null });
    render();
    flash(row.id);
    toast(`Added “${row.title}”: ${T.commitmentLabel(row)}.`);
  } catch (err) { toast(err.message, true); }
}

async function update(id, body, msg) {
  const i = list().findIndex((c) => c.id === id);
  const before = list()[i];
  list()[i] = { ...before, ...body };
  tl().editing = null;
  render();
  try {
    list()[list().findIndex((c) => c.id === id)] = await api(`/api/commitments/${id}`, { method: 'PATCH', body });
    if (msg) toast(msg);
    render();
  } catch (err) {
    list()[list().findIndex((c) => c.id === id)] = before;
    render();
    toast(err.message, true);
  }
}

async function remove(id) {
  const c = byId(id);
  if (!c || !confirm(`Delete “${c.title}”?`)) return;
  try {
    await api(`/api/commitments/${id}`, { method: 'DELETE' });
    state.data.commitments = list().filter((x) => x.id !== id);
    tl().editing = null;
    render();
    toast(`Deleted “${c.title}”.`);
  } catch (err) { toast(err.message, true); }
}

async function saveHours(days) {
  try {
    const r = await api('/api/availability', { method: 'PUT', body: { days } });
    state.data.user.availability = r.availability;
    tl().hours = false;
    render();
    toast(days ? `Working hours: ${T.availabilityLabel(T.availabilityOf(state.data.user))}.` : 'Working hours back to 8:00–22:00 every day.');
  } catch (err) { toast(err.message, true); }
}

const flash = (id) => requestAnimationFrame(() => document.querySelector(`[data-tl-c="${id}"]`)?.classList.add('flash'));

// ---------------------------------------------------------------- what's shown

function range() {
  const t = tl();
  return { from: t.from, to: addDays(t.from, t.weeks * 7 - 1), days: t.weeks * 7, weeks: t.weeks };
}
const pos = (r, key) => Math.min(Math.max(diffDays(key, r.from), 0), r.days) / r.days * 100;
const spanStyle = (r, a, b) => `left:${pos(r, a)}%;width:${Math.max(pos(r, addDays(b, 1)) - pos(r, a), 100 / r.days)}%`;
const inRange = (r, a, b = a) => b >= r.from && a <= r.to;
// A bar too short for its name shows the name after it.
const narrow = (r, a, b) => ((Math.min(diffDays(b, r.from), r.days - 1) - Math.max(diffDays(a, r.from), 0) + 1) / r.days < 0.07 ? ' narrow' : '');

function loads(r) {
  const { courses, events, homework } = state.data;
  return T.weekLoads({ from: r.from, count: r.weeks, availability: av(), commitments: list(), courses, events, homework });
}

export function subtitle() {
  const wk = T.weekLoads({ from: localKey(), count: 1, availability: av(), commitments: list(), courses: state.data.courses, events: state.data.events, homework: state.data.homework })[0];
  const active = list().filter((c) => c.kind === 'work' && c.end_date >= localKey()).length;
  return [`This week ${hrs(wk.total)} of ${hrs(wk.available)}`, active ? `${active} commitment${active === 1 ? '' : 's'}` : ''].filter(Boolean).join(' · ');
}

// ---------------------------------------------------------------- page

export function renderTimeline() {
  const t = tl();
  const r = range();
  const today = localKey();
  const todayAt = diffDays(today, r.from) >= 0 && diffDays(today, r.from) < r.days ? (diffDays(today, r.from) + 0.5) / r.days : null;
  const wk = loads(r);
  const work = list().filter((c) => c.kind === 'work' && inRange(r, c.start_date, c.end_date)).sort((a, b) => a.start_date.localeCompare(b.start_date) || a.id - b.id);
  const awayRows = list().filter((c) => c.kind === 'away' && inRange(r, c.start_date, c.end_date));
  const breaks = (state.data.breaks || []).filter((b) => inRange(r, b.start_date, b.end_date));

  $app.innerHTML = `
    <div class="cal-bar tl-bar">
      <div class="cal-nav">
        <button class="icon-btn cal-prev" data-tl-nav="-1" title="Earlier (←)" aria-label="Earlier"><svg class="ic" aria-hidden="true"><use href="#i-chevron"/></svg></button>
        <button class="btn btn-small" data-tl-nav="0" title="This week (T)">Today</button>
        <button class="icon-btn cal-next" data-tl-nav="1" title="Later (→)" aria-label="Later"><svg class="ic" aria-hidden="true"><use href="#i-chevron"/></svg></button>
        <span class="tl-range">${esc(T.rangeLabel(r.from, r.to, today))}</span>
      </div>
      <div class="seg" role="group" aria-label="Span">
        ${SPANS.map(([n, l]) => `<label class="segment"><input type="radio" name="tl-weeks" value="${n}" ${t.weeks === n ? 'checked' : ''}><span>${l}</span></label>`).join('')}
      </div>
      <button class="btn btn-small" data-tl-hours title="When you're available (H)"><svg class="ic sm" aria-hidden="true"><use href="#i-today"/></svg>Working hours</button>
    </div>
    ${t.hours ? hoursEditor() : ''}
    ${t.editing ? editor(t.editing === 'new' ? { kind: 'work', color: 'blue', ...t.preset } : byId(t.editing)) : quickAdd()}
    ${!list().length && !t.adding && !t.editing ? `<p class="muted small tl-hint">Add what takes a share of your weeks besides classes: a thesis, a job, a project, revision, time away.
      Each week below then shows what's asked of you against the time you have (${esc(T.isDefaultAvailability(av()) ? '8:00–22:00 every day until you set your working hours' : T.availabilityLabel(av()))}).</p>` : ''}
    <div class="tl-scroll">
      <div class="tl${todayAt != null ? ' has-today' : ''}" style="--weeks:${r.weeks};--days:${r.days}${todayAt != null ? `;--today:${todayAt}` : ''}">
        <div class="tl-label tl-corner"></div>
        <div class="tl-weeks">${wk.map((w) => weekHead(w, today)).join('')}</div>
        <div class="tl-label"><b>Load</b><span class="muted small">asked of you / free</span></div>
        <div class="tl-loads">${loadBars(wk)}</div>
        ${section('Commitments')}
        ${work.map((c) => commitmentRow(r, c)).join('')}
        <div class="tl-label tl-new-label"><button class="tl-add" data-tl-add><svg class="ic sm" aria-hidden="true"><use href="#i-plus"/></svg>New commitment</button></div>
        <div class="tl-lane tl-new" data-tl-create title="Drag across the days to add a commitment"><span class="muted small">Drag here to add one</span></div>
        ${awayRows.length || breaks.length ? section('Away and breaks') + awayRow(r, awayRows, breaks) : ''}
        ${courseRows(r)}
        ${taskRows(r)}
        ${dateRow(r)}
      </div>
    </div>
    <p class="muted small tl-legend"><i class="lg cls"></i>classes <i class="lg wrk"></i>commitments <i class="lg due"></i>to-dos due <i class="lg cap"></i>hours available
      · ${esc(T.isDefaultAvailability(av()) ? 'Working hours: 8:00–22:00 every day' : `Working hours: ${T.availabilityLabel(av())}`)}</p>`;

  if (t.adding) { const q = document.querySelector('#tl-quick [name=q]'); if (q) { q.value = t.draft || ''; q.focus(); chips(); } }
  if (t.editing) { document.querySelector('#tl-form [name=title]')?.focus(); summary(); }
  if (todayAt != null && t.scroll !== false) {
    t.scroll = false;
    requestAnimationFrame(() => { const s = document.querySelector('.tl-scroll'); if (s && s.scrollWidth > s.clientWidth) s.scrollLeft = Math.max(0, (s.scrollWidth - 200) * todayAt - s.clientWidth / 3); });
  }
}

const section = (title) => `<div class="tl-section">${esc(title)}</div>`;

function weekHead(w, today) {
  const now = today >= w.start && today <= w.end;
  return `<button class="tl-wk${now ? ' now' : ''}${w.end < today ? ' past' : ''}" data-tl-week="${w.start}" title="Open this week in the Calendar">
    <span class="n">W${isoWeek(w.start)}</span><span class="d">${esc(fmt(w.start))}</span></button>`;
}
function isoWeek(key) {
  const d = new Date(`${key}T12:00:00Z`);
  const th = new Date(d); th.setUTCDate(d.getUTCDate() + 3 - ((d.getUTCDay() + 6) % 7));
  const jan4 = new Date(Date.UTC(th.getUTCFullYear(), 0, 4));
  return 1 + Math.round(((th - jan4) / 86400000 - 3 + ((jan4.getUTCDay() + 6) % 7)) / 7);
}

// A stacked column per week: classes, commitments, to-dos due; the line is the hours you're available.
function loadBars(wk) {
  const top = Math.max(60, ...wk.map((w) => Math.max(w.available, w.total)));
  const h = (m) => `${(m / top) * 100}%`;
  return wk.map((w) => {
    const names = w.commitments.map(({ id, minutes }) => `${byId(id)?.title ?? 'Commitment'} ${hrs(minutes)}`);
    const tip = [`Week of ${fmt(w.start, { weekday: 'short', day: 'numeric', month: 'short' })}`,
      `Available: ${hrs(w.available)}${w.away ? ` (away ${w.away} day${w.away === 1 ? '' : 's'})` : ''}`,
      `Classes: ${hrs(w.classes)}`, `Commitments: ${hrs(w.work)}${names.length ? ` (${names.join(', ')})` : ''}`,
      `To-dos due: ${hrs(w.due)}${w.dueItems.length ? ` (${w.dueItems.length})` : ''}`,
      w.free >= 0 ? `Left: ${hrs(w.free)}` : `Over by ${hrs(-w.free)}`].join('\n');
    return `<button class="tl-load lv-${w.level}" data-tl-week="${w.start}" title="${esc(tip)}" aria-label="${esc(tip.replace(/\n/g, '. '))}">
      <span class="tl-col">
        <i class="cap" style="bottom:${h(w.available)}"></i>
        <i class="tl-s cls" style="height:${h(w.classes)}"></i><i class="tl-s wrk" style="height:${h(w.work)}"></i><i class="tl-s due" style="height:${h(w.due)}"></i>
      </span>
      <span class="tl-num">${hrs(w.total).replace(' h', '')}<span class="muted">/${hrs(w.available)}</span></span>
    </button>`;
  }).join('');
}

function commitmentRow(r, c) {
  const minutes = T.weekLoads({ from: localKey(), count: 1, availability: av(), commitments: [c] })[0].work;
  const clipL = c.start_date < r.from, clipR = c.end_date > r.to;
  return `<div class="tl-label" data-tl-open="${c.id}" role="button" tabindex="0" title="Edit">
      <i class="dot" style="${hue(c.color)}"></i><span class="t">${esc(c.title)}</span><span class="muted small">${esc(T.hoursLabel(c.hours))}/w</span></div>
    <div class="tl-lane">
      <div class="tl-item${narrow(r, c.start_date, c.end_date)}${clipL ? ' clip-l' : ''}${clipR ? ' clip-r' : ''}" style="${spanStyle(r, c.start_date, c.end_date)};${hue(c.color)}"
        data-tl-c="${c.id}" data-tl-drag="move" title="${esc(`${c.title}: ${T.commitmentLabel(c)}${minutes ? ` · ${hrs(minutes)} this week` : ''}\nDrag to move, drag an end to change the dates, click to edit.`)}">
        <i class="tl-h l" data-tl-drag="l" aria-hidden="true"></i>
        <span class="tl-t">${esc(c.title)} <span class="tl-m">${esc(T.hoursLabel(c.hours))}/w${c.days ? ` · ${esc(T.daysLabel(c.days))}` : ''}</span></span>
        <i class="tl-h r" data-tl-drag="r" aria-hidden="true"></i>
      </div>
      ${milestones(r, projectTasks(c))}
    </div>`;
}

// Open dated tasks a commitment covers (its project, or its course), as milestones on its bar.
function projectTasks(c) {
  if (!c.project_id && !c.course_id) return [];
  return state.data.homework.filter((h) => !h.done && h.due_date && (c.project_id ? h.project_id === c.project_id : h.course_id === c.course_id));
}

function milestones(r, hw) {
  const today = localKey();
  return hw.filter((h) => inRange(r, h.due_date)).map((h) => `<button class="tl-ms${h.due_date < today ? ' over' : ''}" style="left:${pos(r, h.due_date) + 50 / r.days}%"
    data-hw-open="${h.id}" title="${esc(`${h.title}: due ${fmt(h.due_date, { weekday: 'short', day: 'numeric', month: 'short' })} · ${hrs(effortOf(h))} of work`)}" aria-label="${esc(h.title)}"></button>`).join('');
}

function awayRow(r, away, breaks) {
  const items = [
    ...away.map((c) => `<div class="tl-item away${narrow(r, c.start_date, c.end_date)}" style="${spanStyle(r, c.start_date, c.end_date)};${hue(c.color)}" data-tl-c="${c.id}" data-tl-drag="move"
      title="${esc(`${c.title}: ${T.commitmentLabel(c)}\nDrag to move, drag an end to change the dates, click to edit.`)}">
      <i class="tl-h l" data-tl-drag="l" aria-hidden="true"></i><span class="tl-t">${esc(c.title)}</span><i class="tl-h r" data-tl-drag="r" aria-hidden="true"></i></div>`),
    ...breaks.map((b) => `<button class="tl-item brk${narrow(r, b.start_date, b.end_date)}" style="${spanStyle(r, b.start_date, b.end_date)}" data-goto="breaks"
      title="${esc(`Break${b.label ? `: ${b.label}` : ''} · ${T.rangeLabel(b.start_date, b.end_date)} · ${b.sessions} session${b.sessions === 1 ? '' : 's'} skipped`)}"><span class="tl-t">${esc(b.label || 'Break')}</span></button>`),
  ];
  return `<div class="tl-label"><span class="t">Time away</span><span class="muted small">${away.length + breaks.length}</span></div><div class="tl-lane">${items.join('')}</div>`;
}

function courseRows(r) {
  const { courses, events, homework } = state.data;
  const byCourse = courseById();
  const isExam = (e, c) => effectiveTag(e, c) === 'exam';
  const spans = T.courseSpans({ courses, events, from: r.from, to: r.to, isExam });
  const covered = new Set(list().filter((c) => c.course_id).map((c) => c.course_id));
  const dueByCourse = new Map();
  for (const h of homework) {
    if (h.done || !h.course_id || !h.due_date || !inRange(r, h.due_date) || covered.has(h.course_id)) continue;
    (dueByCourse.get(h.course_id) ?? dueByCourse.set(h.course_id, []).get(h.course_id)).push(h);
  }
  // Courses with deadlines in range but no sessions get a row too.
  for (const id of dueByCourse.keys()) {
    if (!spans.some((s) => s.course.id === id) && byCourse[id]) spans.push({ course: byCourse[id], first: null, last: null, weekly: new Map(), exams: [], sessions: 0 });
  }
  if (!spans.length) return '';
  const colors = courseColors(courses);
  return section('Courses') + spans.map((s) => {
    const color = colors.get(s.course.id) || 'gray';
    const weeks = [...s.weekly.values()];
    const avg = weeks.length ? weeks.reduce((a, b) => a + b, 0) / weeks.length : 0;
    const bar = s.first ? `<div class="tl-item course" style="${spanStyle(r, s.first, s.last)};${hue(color)}" data-course-open="${s.course.id}"
        title="${esc(`${s.course.name}: ${s.sessions} session${s.sessions === 1 ? '' : 's'} here, about ${hrs(avg)} a week`)}"></div>` : '';
    const exams = s.exams.map((x) => `<button class="tl-ms exam" style="left:${pos(r, x.day) + 50 / r.days}%" data-tl-day="${x.day}" title="${esc(`Exam: ${x.title}, ${fmt(x.day, { weekday: 'short', day: 'numeric', month: 'short' })}`)}" aria-label="${esc(`Exam ${x.title}`)}"></button>`).join('');
    return `<div class="tl-label" data-course-open="${s.course.id}" role="button" tabindex="0"><i class="dot" style="${hue(color)}"></i><span class="t">${esc(s.course.name)}</span>${avg ? `<span class="muted small">${esc(hrs(avg))}/w</span>` : ''}</div>
      <div class="tl-lane">${bar}${milestones(r, dueByCourse.get(s.course.id) || [])}${exams}</div>`;
  }).join('');
}

// Tasks with a date that no course row or commitment shows: one row per project, then the rest.
function taskRows(r) {
  const covered = new Set(list().filter((c) => c.project_id).map((c) => c.project_id));
  const open = state.data.homework.filter((h) => !h.done && h.due_date && !h.course_id && inRange(r, h.due_date) && !covered.has(h.project_id));
  if (!open.length) return '';
  const projects = new Map((state.data.projects || []).map((p) => [p.id, p]));
  const groups = new Map();
  for (const h of open) {
    const k = projects.has(h.project_id) ? h.project_id : 0;
    (groups.get(k) ?? groups.set(k, []).get(k)).push(h);
  }
  const rows = [...groups].sort(([a], [b]) => (a === 0) - (b === 0) || (projects.get(a)?.name || '').localeCompare(projects.get(b)?.name || ''));
  return section('To-dos') + rows.map(([pid, hw]) => {
    const p = projects.get(pid);
    const effort = hw.reduce((n, h) => n + effortOf(h), 0);
    return `<div class="tl-label">${p ? `<i class="dot" style="${hue(p.color)}"></i>` : ''}<span class="t">${esc(p ? p.name : 'Other to-dos')}</span><span class="muted small">${hw.length} · ${esc(hrs(effort))}</span></div>
      <div class="tl-lane">${milestones(r, hw)}</div>`;
  }).join('');
}

function dateRow(r) {
  const now = Date.now();
  const cds = (state.data.countdowns || []).map((cd) => ({ cd, key: localKey(C.occurrence(cd, now).at) })).filter((x) => inRange(r, x.key));
  if (!cds.length) return '';
  return `<div class="tl-label"><span class="t">Countdowns</span></div>
    <div class="tl-lane">${cds.map(({ cd, key }) => `<button class="tl-ms cd" style="left:${pos(r, key) + 50 / r.days}%;${hue(cd.color)}" data-goto="countdowns"
      title="${esc(`${cd.title}, ${fmt(key, { weekday: 'short', day: 'numeric', month: 'short' })}`)}" aria-label="${esc(cd.title)}"></button><span class="tl-ms-t" style="left:${pos(r, key) + 50 / r.days}%">${esc(cd.title)}</span>`).join('')}</div>`;
}

// ---------------------------------------------------------------- adding and editing

function quickAdd() {
  if (!tl().adding) return '<button class="add-row" data-tl-add><span class="plus" aria-hidden="true"><svg class="ic sm"><use href="#i-plus"/></svg></span>Add a commitment or time away<kbd>N</kbd></button>';
  return `
    <form class="quick-add" id="tl-quick" autocomplete="off">
      <input class="field" name="q" maxlength="200" placeholder="e.g. Thesis 10h/week until 15 dec · Job 12h/week mon wed · Essay 20h by 1 dec · Away 20-27 dec" aria-label="New commitment" required>
      <div class="quick-foot">
        <span class="quick-chips" id="tl-quick-chips"></span>
        <span class="spacer"></span>
        <button class="btn btn-borderless btn-small" type="button" data-tl-more>More options</button>
        <button class="btn btn-small" type="button" data-tl-cancel>Cancel</button>
        <button class="btn btn-primary btn-small" type="submit">Add</button>
      </div>
    </form>`;
}

function chips() {
  const q = document.querySelector('#tl-quick [name=q]');
  const out = document.getElementById('tl-quick-chips');
  if (!q || !out) return;
  if (!q.value.trim()) { out.innerHTML = '<span class="muted">A name, hours a week (or a total by a date), and when. Start with “Away” for time off.</span>'; return; }
  const p = T.parseCommitment(q.value);
  if (!p.ok) { out.innerHTML = `<span class="muted">${esc(p.error)}</span>`; return; }
  const v = p.value;
  out.innerHTML = [
    `<span class="chip on">${esc(v.title)}</span>`,
    v.kind === 'away' ? '<span class="chip on">time away</span>' : `<span class="chip on">${esc(T.hoursLabel(v.hours))}/week${v.total ? ` (${v.total} h in all)` : ''}</span>`,
    v.days ? `<span class="chip on">${esc(T.daysLabel(v.days))}</span>` : '',
    `<span class="chip">${esc(T.rangeLabel(v.start_date, v.end_date))}</span>`,
  ].join('');
}

function options(items, selected, none) {
  return `<option value="">${esc(none)}</option>${items.map((x) => `<option value="${x.id}" ${x.id === selected ? 'selected' : ''}>${esc(x.name)}</option>`).join('')}`;
}

function editor(c) {
  const isNew = !c.id;
  const today = localKey();
  const v = { title: '', hours: '', start_date: today, end_date: addDays(today, T.DEFAULT_WEEKS * 7 - 1), days: null, project_id: null, course_id: null, note: '', ...c };
  return `
    <form class="tl-form panel" id="tl-form" data-id="${c.id || ''}" autocomplete="off" style="${hue(v.color)}">
      <div class="tl-form-row">
        <input class="field tl-title" name="title" maxlength="120" value="${esc(v.title)}" placeholder="${v.kind === 'away' ? 'Trip to Rome' : 'Thesis, job, revision…'}" aria-label="Name" required>
        <div class="seg" role="group" aria-label="Kind">
          <label class="segment"><input type="radio" name="kind" value="work" ${v.kind !== 'away' ? 'checked' : ''}><span>Work</span></label>
          <label class="segment"><input type="radio" name="kind" value="away" ${v.kind === 'away' ? 'checked' : ''}><span>Time away</span></label>
        </div>
      </div>
      <div class="tl-form-grid">
        <label class="tl-f" ${v.kind === 'away' ? 'hidden' : ''} data-work><span>Hours a week</span><input class="field" name="hours" type="number" min="0.25" max="${T.MAX_HOURS}" step="0.25" value="${v.hours ?? ''}" placeholder="10"></label>
        <label class="tl-f"><span>From</span><input class="field" name="start_date" type="date" value="${v.start_date}" required></label>
        <label class="tl-f"><span>To</span><input class="field" name="end_date" type="date" value="${v.end_date}" required></label>
      </div>
      <div class="tl-f" ${v.kind === 'away' ? 'hidden' : ''} data-work><span>On</span>
        <div class="tl-days">${T.WD_SHORT.map((d, i) => `<label class="tl-day"><input type="checkbox" name="days" value="${i + 1}" ${!v.days || v.days.includes(i + 1) ? 'checked' : ''}><span>${d}</span></label>`).join('')}</div>
      </div>
      <div class="tl-form-grid" ${v.kind === 'away' ? 'hidden' : ''} data-work>
        <label class="tl-f"><span>Project</span><span class="select"><select name="project_id">${options((state.data.projects || []).filter((p) => !p.archived), v.project_id, 'None')}</select></span></label>
        <label class="tl-f"><span>Course</span><span class="select"><select name="course_id">${options(state.data.courses.filter((x) => x.tracked), v.course_id, 'None')}</select></span></label>
      </div>
      <div class="tl-f"><span>Colour</span><div class="tl-colors">${COLORS.map((x) => `<label class="tl-color" style="${hue(x)}" title="${x}"><input type="radio" name="color" value="${x}" ${v.color === x ? 'checked' : ''}><span></span></label>`).join('')}</div></div>
      <label class="tl-f"><span>Note</span><input class="field" name="note" maxlength="2000" value="${esc(v.note || '')}" placeholder="Optional"></label>
      <p class="muted small" id="tl-summary"></p>
      <div class="quick-foot">
        ${isNew ? '' : '<button class="btn btn-borderless btn-small bad" type="button" data-tl-delete>Delete</button>'}
        <span class="spacer"></span>
        <button class="btn btn-small" type="button" data-tl-cancel>Cancel</button>
        <button class="btn btn-primary btn-small" type="submit">${isNew ? 'Add' : 'Save'}</button>
      </div>
    </form>`;
}

function formValue(form) {
  const fd = new FormData(form);
  const kind = fd.get('kind');
  const days = fd.getAll('days').map(Number);
  return {
    title: fd.get('title'), kind, hours: kind === 'away' ? null : Number(fd.get('hours')) || null,
    start_date: fd.get('start_date'), end_date: fd.get('end_date'), days: kind === 'away' || days.length === 7 ? null : days,
    color: fd.get('color') || 'blue', project_id: kind === 'away' ? null : Number(fd.get('project_id')) || null,
    course_id: kind === 'away' ? null : Number(fd.get('course_id')) || null, note: fd.get('note'),
  };
}

// "About 2 h a day on Mon, Wed. This week: 4 h." under the form, as you type.
function summary() {
  const form = document.getElementById('tl-form');
  const out = document.getElementById('tl-summary');
  if (!form || !out) return;
  const v = formValue(form);
  form.querySelectorAll('[data-work]').forEach((el) => { el.hidden = v.kind === 'away'; });
  const r = T.cleanCommitment(v);
  if (!r.ok) { out.textContent = r.error; out.classList.add('bad'); return; }
  out.classList.remove('bad');
  const c = { id: 0, ...r.value };
  if (c.kind === 'away') { out.textContent = `${T.commitmentLabel(c)}. No free time on those days, and commitments pause.`; return; }
  const n = (c.days || [1, 2, 3, 4, 5, 6, 7].filter((d) => T.windowsOn(av(), addDays(weekOf(localKey()), d - 1)).length)).length || 7;
  const weeks = (diffDays(c.end_date, c.start_date) + 1) / 7;
  out.textContent = `About ${hrs((c.hours * 60) / n)} a day over ${n} day${n === 1 ? '' : 's'} a week, ${hrs(c.hours * 60 * weeks)} in all.`;
}

// ---------------------------------------------------------------- working hours

const HOUR_PRESETS = [
  ['Every day 8–22', T.DEFAULT_AVAILABILITY],
  ['Weekdays 9–18', [...Array(5).fill([[540, 1080]]), [], []]],
  ['Weekdays 9–18, Sat 10–14', [...Array(5).fill([[540, 1080]]), [[600, 840]], []]],
  ['Every day 9–21, lunch off', Array(7).fill([[540, 720], [780, 1260]])],
];
const windowsText = (ws) => ws.map(([a, b]) => `${T.clockOf(a)}–${T.clockOf(b)}`).join(', ');

// "9-12, 13:30-18" → [[540, 720], [810, 1080]]; "off" or empty → []. Null when it can't be read.
export function readWindows(text) {
  const s = String(text || '').trim().toLowerCase();
  if (!s || s === 'off' || s === '-') return [];
  const out = [];
  for (const part of s.split(/[,;]+/).map((x) => x.trim()).filter(Boolean)) {
    const m = /^(\d{1,2})(?:[:.h](\d{2}))?\s*(?:-|–|to)\s*(\d{1,2})(?:[:.h](\d{2}))?$/.exec(part);
    if (!m) return null;
    const a = Number(m[1]) * 60 + Number(m[2] || 0), b = Number(m[3]) * 60 + Number(m[4] || 0);
    if (a >= b || b > 1440) return null;
    out.push([a, b]);
  }
  return out;
}

function hoursEditor() {
  const a = av();
  return `
    <form class="tl-hours panel" id="tl-hours" autocomplete="off">
      <h3><span>Working hours</span></h3>
      <p class="muted small">When you're available for classes, to-dos and commitments. Today's free time, “Plan my week” and each week's load use them. Write ranges like <code>9-12, 13-18</code>, or <code>off</code>.</p>
      <div class="tl-presets">${HOUR_PRESETS.map(([l], i) => `<button class="btn btn-small" type="button" data-tl-preset="${i}">${esc(l)}</button>`).join('')}</div>
      <div class="tl-hours-grid">
        ${T.WEEKDAY_NAMES.map((d, i) => `<label><span>${d}</span><input class="field" name="d${i}" value="${esc(windowsText(a[i]) || 'off')}" aria-label="${d}"></label>`).join('')}
      </div>
      <p class="muted small" id="tl-hours-sum"></p>
      <div class="quick-foot">
        <button class="btn btn-borderless btn-small" type="button" data-tl-hours-reset>Back to 8–22 every day</button>
        <span class="spacer"></span>
        <button class="btn btn-small" type="button" data-tl-hours-cancel>Cancel</button>
        <button class="btn btn-primary btn-small" type="submit">Save</button>
      </div>
    </form>`;
}

function hoursValue(form) {
  const days = [];
  for (let i = 0; i < 7; i++) {
    const w = readWindows(form.elements[`d${i}`].value);
    if (!w) return { error: `${T.WEEKDAY_NAMES[i]}: write ranges like 9-12, 13-18, or off.` };
    days.push(w);
  }
  const r = T.cleanAvailability(days);
  return r.ok ? { days: r.value } : { error: r.error };
}

function hoursSummary() {
  const form = document.getElementById('tl-hours');
  const out = document.getElementById('tl-hours-sum');
  if (!form || !out) return;
  const v = hoursValue(form);
  out.classList.toggle('bad', Boolean(v.error));
  out.textContent = v.error || `${hrs(v.days.flat().reduce((n, [a, b]) => n + b - a, 0))} a week.`;
}

// ---------------------------------------------------------------- dragging bars

let drag = null;

function onPointerDown(e) {
  const handle = e.target.closest('[data-tl-drag]');
  const create = !handle && e.target.closest('[data-tl-create]');
  if ((!handle && !create) || e.button > 0) return;
  const lane = (handle || create).closest('.tl-lane');
  const r = range();
  const dayW = lane.getBoundingClientRect().width / r.days;
  const dayAt = (x) => Math.min(r.days - 1, Math.max(0, Math.floor((x - lane.getBoundingClientRect().left) / dayW)));
  if (create) {
    const d = dayAt(e.clientX);
    const ghost = document.createElement('div');
    ghost.className = 'tl-item ghost';
    lane.append(ghost);
    drag = { mode: 'new', r, lane, dayAt, a: d, b: d, ghost, x: e.clientX };
  } else {
    const item = handle.closest('[data-tl-c]');
    const c = byId(Number(item.dataset.tlC));
    if (!c) return;
    drag = { mode: handle.dataset.tlDrag, r, c, item, dayW, x: e.clientX, s: c.start_date, e: c.end_date, moved: false };
  }
  e.preventDefault();
  (handle || create).setPointerCapture?.(e.pointerId);
  paintDrag(e);
}

function paintDrag(e) {
  if (!drag) return;
  const { r } = drag;
  if (drag.mode === 'new') {
    drag.b = drag.dayAt(e.clientX);
    const [a, b] = [Math.min(drag.a, drag.b), Math.max(drag.a, drag.b)];
    drag.ghost.setAttribute('style', spanStyle(r, addDays(r.from, a), addDays(r.from, b)));
    drag.ghost.textContent = T.rangeLabel(addDays(r.from, a), addDays(r.from, b));
    return;
  }
  const n = Math.round((e.clientX - drag.x) / drag.dayW);
  if (n) drag.moved = true;
  const c = drag.c;
  let s = c.start_date, en = c.end_date;
  if (drag.mode === 'move') { s = addDays(c.start_date, n); en = addDays(c.end_date, n); }
  if (drag.mode === 'l') s = addDays(c.start_date, Math.min(n, diffDays(c.end_date, c.start_date)));
  if (drag.mode === 'r') en = addDays(c.end_date, Math.max(n, -diffDays(c.end_date, c.start_date)));
  drag.s = s; drag.e = en;
  drag.item.style.left = `${pos(r, s)}%`;
  drag.item.style.width = `${Math.max(pos(r, addDays(en, 1)) - pos(r, s), 100 / r.days)}%`;
  drag.item.classList.add('dragging');
  drag.item.dataset.tip = T.rangeLabel(s, en);
}

function onPointerUp() {
  if (!drag) return;
  const d = drag;
  drag = null;
  if (d.mode === 'new') {
    d.ghost.remove();
    const [a, b] = [Math.min(d.a, d.b), Math.max(d.a, d.b)];
    Object.assign(tl(), { editing: 'new', adding: false, preset: { start_date: addDays(d.r.from, a), end_date: addDays(d.r.from, b) } });
    render();
    return;
  }
  d.item.classList.remove('dragging');
  if (!d.moved) { Object.assign(tl(), { editing: d.c.id, adding: false }); render(); return; }
  if (d.s === d.c.start_date && d.e === d.c.end_date) return render();
  update(d.c.id, { start_date: d.s, end_date: d.e }, `“${d.c.title}”: ${T.rangeLabel(d.s, d.e)}.`);
}

document.addEventListener('pointerdown', (e) => { if (state.tab === 'timeline') onPointerDown(e); });
document.addEventListener('pointermove', (e) => { if (drag) paintDrag(e); });
document.addEventListener('pointerup', onPointerUp);
document.addEventListener('pointercancel', () => { if (drag) { drag.ghost?.remove(); drag = null; render(); } });

// ---------------------------------------------------------------- events

export function openAdd(text = '') {
  Object.assign(tl(), { adding: true, editing: null, draft: text });
  render();
}

export function openHours() { tl().hours = true; render(); document.querySelector('#tl-hours [name=d0]')?.focus(); hoursSummary(); }

export function onClick(e) {
  const t = e.target;
  // A commitment's bar is handled by the pointer events (move, resize, click to edit).
  if (t.closest('[data-tl-drag], [data-tl-create]')) return true;
  const nav = t.closest('[data-tl-nav]');
  if (nav) {
    const n = Number(nav.dataset.tlNav);
    const s = tl();
    if (n) s.from = addDays(s.from, n * Math.max(1, Math.floor(s.weeks / 2)) * 7); else enter();
    s.scroll = true;
    render();
    return true;
  }
  const week = t.closest('[data-tl-week]');
  if (week) { showDate(new Date(`${week.dataset.tlWeek}T12:00:00`).getTime()); return true; }
  const day = t.closest('[data-tl-day]');
  if (day) { showDate(new Date(`${day.dataset.tlDay}T12:00:00`).getTime()); return true; }
  const open = t.closest('[data-tl-open]');
  if (open) { Object.assign(tl(), { editing: Number(open.dataset.tlOpen), adding: false }); render(); return true; }
  if (t.closest('[data-tl-add]')) { openAdd(); return true; }
  if (t.closest('[data-tl-cancel]')) { Object.assign(tl(), { adding: false, editing: null, draft: '', preset: null }); render(); return true; }
  if (t.closest('[data-tl-more]')) {
    const q = document.querySelector('#tl-quick [name=q]')?.value || '';
    const p = T.parseCommitment(q);
    Object.assign(tl(), { adding: false, editing: 'new', preset: p.ok ? { ...p.value, total: undefined } : { title: q.trim() } });
    render();
    return true;
  }
  if (t.closest('[data-tl-delete]')) { remove(Number(document.getElementById('tl-form').dataset.id)); return true; }
  if (t.closest('[data-tl-hours]')) { if (tl().hours) { tl().hours = false; render(); } else openHours(); return true; }
  if (t.closest('[data-tl-hours-cancel]')) { tl().hours = false; render(); return true; }
  if (t.closest('[data-tl-hours-reset]')) { saveHours(null); return true; }
  const preset = t.closest('[data-tl-preset]');
  if (preset) {
    const form = document.getElementById('tl-hours');
    HOUR_PRESETS[Number(preset.dataset.tlPreset)][1].forEach((ws, i) => { form.elements[`d${i}`].value = windowsText(ws) || 'off'; });
    hoursSummary();
    return true;
  }
  return false;
}

export function onSubmit(e) {
  if (e.target.id === 'tl-quick') {
    e.preventDefault();
    const p = T.parseCommitment(new FormData(e.target).get('q'));
    if (!p.ok) { toast(p.error, true); return true; }
    const { total: _, ...body } = p.value;
    create(body);
    return true;
  }
  if (e.target.id === 'tl-form') {
    e.preventDefault();
    const body = formValue(e.target);
    const r = T.cleanCommitment(body);
    if (!r.ok) { toast(r.error, true); return true; }
    const id = Number(e.target.dataset.id);
    if (id) update(id, body, 'Saved.'); else create(body);
    return true;
  }
  if (e.target.id === 'tl-hours') {
    e.preventDefault();
    const v = hoursValue(e.target);
    if (v.error) { toast(v.error, true); return true; }
    saveHours(T.isDefaultAvailability(v.days) ? null : v.days);
    return true;
  }
  return false;
}

export function onInput(e) {
  if (e.target.closest('#tl-quick')) { tl().draft = e.target.value; chips(); return true; }
  if (e.target.closest('#tl-form')) { summary(); return true; }
  if (e.target.closest('#tl-hours')) { hoursSummary(); return true; }
  return false;
}

export function onChange(e) {
  if (e.target.name === 'tl-weeks') {
    const s = tl();
    s.weeks = Number(e.target.value);
    s.scroll = true;
    try { localStorage.setItem(SPAN_KEY, String(s.weeks)); } catch { /* storage unavailable */ }
    render();
    return true;
  }
  if (e.target.closest('#tl-form')) { summary(); return true; }
  return false;
}

// Keys on the Timeline page: N adds, H opens working hours, T goes to this week, ← and → move.
export function onPageKey(e) {
  const k = e.key.toLowerCase();
  if (k === 'escape' && (tl().adding || tl().editing || tl().hours)) { Object.assign(tl(), { adding: false, editing: null, hours: false }); render(); return true; }
  if (k === 'n') { e.preventDefault(); openAdd(); return true; }
  if (k === 'h') { e.preventDefault(); openHours(); return true; }
  if (k === 't') { e.preventDefault(); enter(); tl().scroll = true; render(); return true; }
  if (k === 'arrowleft' || k === 'arrowright') {
    const s = tl();
    s.from = addDays(s.from, (k === 'arrowleft' ? -7 : 7));
    render();
    return true;
  }
  return false;
}

export const SHORTCUTS = [[['N'], 'Timeline: add a commitment'], [['H'], 'Timeline: working hours'], [['←', '→'], 'Timeline: a week earlier or later']];

export function paletteItems(go) {
  const act = (id, label, icon, run, extra = {}) => ({ id: `act:${id}`, section: 'Actions', label, icon, run, ...extra });
  return [
    act('tl-add', 'Add a commitment or time away', 'i-plus', () => { go('timeline'); openAdd(); }, { home: true, sub: 'hours a week on the Timeline', keywords: 'timeline gantt thesis job project away holiday availability' }),
    act('tl-hours', 'Set working hours', 'i-today', () => { go('timeline'); openHours(); }, { keywords: 'availability available schedule timeline' }),
    ...list().filter((c) => c.end_date >= localKey()).map((c) => ({
      id: `tl:${c.id}`, section: 'Commitments', label: c.title, icon: 'i-timeline', sub: T.commitmentLabel(c),
      run: () => { go('timeline'); Object.assign(tl(), { editing: c.id, adding: false }); render(); },
    })),
  ];
}

// Offered below the palette's matches when the text reads as a commitment.
export function fallback(q, go) {
  const p = T.parseCommitment(q);
  if (!p.ok || (!/\d\s*(h|hr|hrs|hours?)\b/i.test(q) && p.value.kind !== 'away')) return [];
  const { total: _, ...body } = p.value;
  return [{ id: 'tl:new', recentId: '', section: 'Timeline', label: `Add “${body.title}”: ${T.commitmentLabel(body)}`, icon: 'i-timeline',
    run: () => { go('timeline'); create(body); } }];
}

// For Today: this week's load, in a line.
export function weekLine() {
  const w = T.weekLoads({ from: localKey(), count: 1, availability: av(), commitments: list(), courses: state.data.courses, events: state.data.events, homework: state.data.homework })[0];
  const parts = [w.classes && `${hrs(w.classes)} of class`, w.work && `${hrs(w.work)} of commitments`, w.due && `${hrs(w.due)} of to-dos due`].filter(Boolean);
  return { w, text: `This week: ${parts.length ? parts.join(', ') : 'nothing booked'}, against ${hrs(w.available)} available${w.away ? ` (away ${w.away} day${w.away === 1 ? '' : 's'})` : ''}.` };
}

// Time away covering a day, for Today and the Calendar.
export const awayOn = (key) => list().find((c) => c.kind === 'away' && c.start_date <= key && c.end_date >= key) || null;
