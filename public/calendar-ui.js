// Calendar: day, 3-day, week and month views of everything in the app. Sessions in course colours with their
// attendance, homework deadlines and breaks, and the focus time you logged. Time blocking: drag a to-do
// from "To plan" onto the grid (or click it, then a time), move or resize its block, tick it off from there, or let
// "Plan my week" fill your free time before each deadline. Click an empty slot, or drag across one, to add a to-do
// there; click a session to mark it. On phones it opens on 3 days, swipes between periods, and nothing needs a drag.
import { state, esc, api, toast, render, courseById, fmtTime, groupChip } from './app.js';
import { TAGS, effectiveTag, countsAttendance } from './stats.js';
import { effortOf, plannedBlock, DAY_START, DAY_END } from './plan.js';
import * as K from './calendar.js';
import { dueLabel, homeworkForSession } from './homework-ui.js';
import { hrs, focusOn } from './focus-ui.js';

const $app = document.getElementById('app');
const $scroller = document.getElementById('scroller');
const MIN = 60000, HOUR = 60 * MIN, DAY = 24 * HOUR;
const SNAP = 15;               // minutes
const NEW_BLOCK = 60;          // a click on an empty slot proposes an hour
const MAX_BLOCK = 120;         // a dropped or auto-planned to-do gets its effort, up to 2 h
const PLAN_AHEAD = 14 * DAY;   // "Plan my week" plans to-dos due within two weeks
const VIEW_KEY = 'a80-cal-view';
const LAYER_KEY = 'a80-cal-layers';
const LAYERS = [['deadlines', 'Deadlines'], ['focus', 'Focus time'], ['breaks', 'Breaks'], ['done', 'Done to-dos']];
const LENGTHS = [15, 30, 45, 60, 90, 120, 180, 240];
const VIEW_LABELS = [['day', 'Day', '1'], ['3day', '3 days', '2'], ['week', 'Week', '3'], ['month', 'Month', '4']];

const isPhone = () => matchMedia('(max-width: 760px)').matches;
const tz = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
const pad = (n) => String(n).padStart(2, '0');
const localDate = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; };
const fmtDay = (ms, o = { weekday: 'short', day: 'numeric', month: 'short' }) => new Date(ms).toLocaleDateString([], o);
const span = (s, e) => `${fmtTime(s)}–${fmtTime(e)}`;
const lenLabel = (m) => (m < 60 ? `${m} min` : `${Math.round(m / 30) / 2} h`);
const hwById = (id) => state.data.homework.find((h) => h.id === id);

function cal() {
  if (!state.cal) {
    let view = null, layers = {};
    try { view = localStorage.getItem(VIEW_KEY); layers = JSON.parse(localStorage.getItem(LAYER_KEY) || '{}') || {}; } catch { /* storage unavailable */ }
    state.cal = {
      view: Object.hasOwn(K.VIEWS, view ?? '') ? view : isPhone() ? '3day' : 'week',
      date: Date.now(),
      layers: { deadlines: true, focus: true, breaks: true, done: true, ...layers },
      pop: null,        // { kind: 'ev' | 'plan' | 'new', id?, fresh }
      sel: null,        // { day, start, end }: the slot a new to-do goes in
      placing: null,    // a to-do waiting for a tap on the grid
      proposals: null,  // "Plan my week" before you keep it: [{ id, start, minutes }]
      unplaced: [],
      scroll: true,     // scroll to now (or the first item) after the next render
    };
  }
  return state.cal;
}

// ---------------------------------------------------------------- data

// Everything the calendar shows between start and end.
function collect(start, end) {
  const c = cal();
  const { courses, events, homework, breaks = [], focus } = state.data;
  const byId = courseById();
  const colors = K.courseColors(courses);
  const color = (courseId) => colors.get(courseId) || 'gray';
  const sessions = events.filter((e) => byId[e.course_id]?.tracked && e.end_at > start && e.start_at < end);
  const proposals = new Map((c.proposals || []).map((p) => [p.id, p]));
  const blocks = homework.filter((h) => h.planned_at != null && !proposals.has(h.id) && (!h.done || c.layers.done))
    .map((h) => { const [s, e] = plannedBlock(h); return { h, start: s, end: e }; })
    .filter((b) => b.end > start && b.start < end);
  const ghosts = [...proposals.values()].map((p) => ({ h: hwById(p.id), start: p.start, end: p.start + p.minutes * MIN }))
    .filter((g) => g.h && g.end > start && g.start < end);
  const due = c.layers.deadlines ? homework.filter((h) => (!h.done || c.layers.done) && h.due_at > start - DAY && h.due_at < end + DAY) : [];
  const fx = c.layers.focus ? (focus?.sessions || []).filter((f) => f.ended_at > start && f.started_at < end) : [];
  const brks = c.layers.breaks ? breaks.filter((b) => b.end_date >= localDate(start) && b.start_date <= localDate(end - 1)) : [];
  return { byId, color, sessions, blocks, ghosts, due, fx, brks };
}

// What a day has, for one column (or one month cell).
function dayItems(d, day) {
  const end = K.addDays(day, 1);
  const inDay = (s, e) => e > day && s < end;
  const key = localDate(day);
  return {
    timed: d.sessions.filter((e) => e.end_at - e.start_at < 20 * HOUR && inDay(e.start_at, e.end_at)),
    allDay: d.sessions.filter((e) => e.end_at - e.start_at >= 20 * HOUR && inDay(e.start_at, e.end_at)),
    blocks: d.blocks.filter((b) => inDay(b.start, b.end)),
    ghosts: d.ghosts.filter((g) => inDay(g.start, g.end)),
    dueTimed: d.due.filter((h) => h.due_time && h.due_date === key),
    dueAllDay: d.due.filter((h) => !h.due_time && h.due_date === key),
    fx: d.fx.filter((f) => inDay(f.started_at, f.ended_at)),
    brks: d.brks.filter((b) => b.start_date <= key && b.end_date >= key),
  };
}

// ---------------------------------------------------------------- page

export function subtitle() {
  const c = cal();
  const p = K.period(c.view, c.date);
  const parts = [];
  if (c.view !== 'month') parts.push(`Week ${K.isoWeek(p.days[0])}${c.view === 'week' || K.isoWeek(p.days.at(-1)) === K.isoWeek(p.days[0]) ? '' : `–${K.isoWeek(p.days.at(-1))}`}`);
  const now = Date.now();
  const planned = state.data.homework.filter((h) => !h.done && h.planned_at != null && h.planned_at >= p.start && h.planned_at < p.end).length;
  if (planned) parts.push(`${planned} planned`);
  const toPlan = unplannedTodos(now).length;
  if (toPlan) parts.push(`${toPlan} to plan`);
  return parts.join(' · ');
}

function title(c, p) {
  const m = (ms, o) => new Date(ms).toLocaleDateString([], o);
  if (c.view === 'month') return m(c.date, { month: 'long', year: 'numeric' });
  if (c.view === 'day') return m(p.start, { weekday: 'long', day: 'numeric', month: 'long' });
  const a = p.days[0], b = p.days.at(-1);
  return m(a, { month: 'long' }) === m(b, { month: 'long' }) ? m(a, { month: 'long', year: 'numeric' })
    : `${m(a, { month: 'short' })} – ${m(b, { month: 'short', year: 'numeric' })}`;
}

export function renderCalendar() {
  const c = cal();
  const p = K.period(c.view, c.date);
  const d = collect(p.start, p.end);
  const placing = c.placing && hwById(c.placing);
  if (c.placing && !placing) c.placing = null;

  $app.innerHTML = `
    <div class="cal-bar">
      <div class="cal-nav">
        <button class="icon-btn cal-prev" data-cal-nav="-1" title="Previous (K or ←)" aria-label="Previous"><svg class="ic" aria-hidden="true"><use href="#i-chevron"/></svg></button>
        <button class="btn btn-small" data-cal-nav="0" title="Today (T)">Today</button>
        <button class="icon-btn cal-next" data-cal-nav="1" title="Next (J or →)" aria-label="Next"><svg class="ic" aria-hidden="true"><use href="#i-chevron"/></svg></button>
        <span class="cal-title-wrap">
          <button class="cal-title" data-cal-pick title="Go to a date">${esc(title(c, p))}<svg class="ic sm" aria-hidden="true"><use href="#i-chevron"/></svg></button>
          <input class="cal-jump" type="date" data-cal-jump value="${localDate(c.date)}" tabindex="-1" aria-label="Go to a date">
        </span>
      </div>
      <div class="seg cal-views" role="group" aria-label="View">
        ${VIEW_LABELS.map(([v, l, k]) => `<label class="segment" title="${l} (${k})"><input type="radio" name="cal-view" value="${v}" ${c.view === v ? 'checked' : ''}><span>${l}</span></label>`).join('')}
      </div>
      <button class="btn btn-small cal-auto" data-cal-autoplan title="Fit your open to-dos into free time before their deadlines (P)"><svg class="ic sm" aria-hidden="true"><use href="#i-wand"/></svg>Plan my week</button>
    </div>
    ${c.proposals ? proposalBanner(c) : ''}
    ${placing ? `<div class="banner cal-banner"><span>${isPhone() ? 'Tap' : 'Click'} a time to plan <b>${esc(placing.title)}</b>.</span>
      <button class="btn btn-small" data-cal-cancel>Cancel</button></div>` : ''}
    <div class="cal-layout ${c.view === 'month' ? 'is-month' : ''}">
      ${c.view === 'month' ? monthView(c, p, d) : timeGrid(c, p, d)}
      ${c.view === 'month' ? '' : tray(c, d)}
    </div>
    ${c.pop ? popover(c, d) : ''}`;
  requestAnimationFrame(afterPaint);
}

function proposalBanner(c) {
  const n = c.proposals.length;
  const left = c.unplaced.map(hwById).filter(Boolean);
  return `<div class="banner cal-banner cal-proposal">
    <span>${n ? `Planned <b>${n}</b> to-do${n === 1 ? '' : 's'} in your free time, each before it's due.` : 'Nothing could be planned.'}
      ${left.length ? `<span class="muted">No room before the deadline for ${left.slice(0, 3).map((h) => `“${esc(h.title)}”`).join(', ')}${left.length > 3 ? ` and ${left.length - 3} more` : ''}.</span>` : ''}
      ${n ? `<span class="muted">${isPhone() ? 'Tap one to leave it out.' : 'Drag one to change it, click to leave it out.'}</span>` : ''}</span>
    ${n ? '<button class="btn btn-primary btn-small" data-cal-keep>Keep</button>' : ''}
    <button class="btn btn-small" data-cal-discard>${n ? 'Discard' : 'Close'}</button>
  </div>`;
}

// ---------------------------------------------------------------- time grid (day, 3 days, week)

let geom = { lo: 7, hh: 48 };

function timeGrid(c, p, d) {
  const now = Date.now();
  const today = K.startOfDay(now);
  const days = p.days.map((day) => ({ day, ...dayItems(d, day) }));

  // Hours shown: 7:00 to 22:00, stretched to fit anything earlier or later.
  const hourOf = (ms, day) => (ms <= day ? 0 : ms >= K.addDays(day, 1) ? 24 : new Date(ms).getHours() + new Date(ms).getMinutes() / 60);
  let lo = DAY_START - 1, hi = DAY_END;
  for (const x of days) {
    for (const [s, e] of [...x.timed.map((v) => [v.start_at, v.end_at]), ...x.blocks.map((b) => [b.start, b.end]), ...x.ghosts.map((g) => [g.start, g.end]),
      ...x.dueTimed.map((h) => [h.due_at - 30 * MIN, h.due_at])]) {
      lo = Math.min(lo, Math.floor(hourOf(s, x.day)));
      hi = Math.max(hi, Math.ceil(hourOf(e, x.day)));
    }
  }
  const hh = isPhone() ? 44 : 48;
  geom = { lo, hh };
  const y = (ms, day) => (hourOf(ms, day) - lo) * hh;

  // An empty weekend day in the week view is narrow.
  const empty = (x) => !x.timed.length && !x.blocks.length && !x.ghosts.length && !x.dueTimed.length && !x.allDay.length && !x.dueAllDay.length;
  const widths = days.map((x) => (c.view === 'week' && [0, 6].includes(new Date(x.day).getDay()) && x.day !== today && empty(x) ? '.45fr' : '1fr'));
  const tpl = `${isPhone() ? 40 : 52}px ${widths.map((w) => `minmax(0, ${w})`).join(' ')}`;
  const anyAllDay = days.some((x) => x.allDay.length || x.dueAllDay.length || x.brks.length);

  const head = days.map((x) => {
    const classHours = x.timed.reduce((n, e) => n + (Math.min(e.end_at, K.addDays(x.day, 1)) - Math.max(e.start_at, x.day)) / HOUR, 0);
    const meta = [classHours ? `${Math.round(classHours * 2) / 2} h class` : '', x.blocks.length ? `${x.blocks.length} planned` : ''].filter(Boolean).join(' · ');
    return `<button class="cal-dh${x.day === today ? ' today' : ''}${x.day < today ? ' past' : ''}" data-cal-open="${x.day}" title="Open this day">
      <span class="dow">${esc(fmtDay(x.day, { weekday: 'short' }))}</span><span class="n">${new Date(x.day).getDate()}</span>
      ${meta && c.view === 'day' ? `<span class="meta">${esc(meta)}</span>` : ''}
    </button>`;
  }).join('');

  const allDay = anyAllDay ? days.map((x) => `<div class="cal-ad">${allDayChips(x, d)}</div>`).join('') : '';

  const cols = days.map((x) => {
    const items = [
      ...x.timed.map((e) => ({ key: `e${e.id}`, kind: 'ev', e, start: Math.max(e.start_at, x.day), end: Math.min(e.end_at, K.addDays(x.day, 1)) })),
      ...x.blocks.map((b) => ({ key: `p${b.h.id}`, kind: 'plan', ...b })),
      ...x.ghosts.map((g) => ({ key: `g${g.h.id}`, kind: 'ghost', ...g })),
    ];
    // Short items still take 20 minutes of room, so they don't sit on top of the next one.
    const lay = K.layout(items.map((it) => ({ id: it.key, start: it.start, end: Math.max(it.end, it.start + 20 * MIN) })));
    const clash = new Set();
    const sess = x.timed;
    for (let i = 0; i < sess.length; i++) {
      for (let j = i + 1; j < sess.length; j++) {
        const a = sess[i], b = sess[j];
        if (a.course_id !== b.course_id && a.start_at < b.end_at && b.start_at < a.end_at) { clash.add(a.id); clash.add(b.id); }
      }
    }
    const blocks = items.map((it) => {
      const { col, cols: n } = lay.get(it.key);
      const top = y(it.start, x.day);
      const height = Math.max(18, y(it.end, x.day) - top - 2);
      const pos = `top:${top}px;height:${height}px;--l:${col / n};--w:${1 / n}`;
      return it.kind === 'ev' ? sessionBlock(it.e, d, pos, height, clash.has(it.e.id), now) : planBlock(it, d, pos, height, now);
    }).join('');
    const dues = x.dueTimed.map((h) => `<button class="cal-due${h.done ? ' done' : h.due_at < now ? ' over' : ''}" style="top:${y(h.due_at, x.day)}px" data-hw-open="${h.id}" title="Due ${esc(fmtTime(h.due_at))}: ${esc(h.title)}"><span>${esc(h.title)}</span></button>`).join('');
    const fx = x.fx.map((f) => {
      const top = y(Math.max(f.started_at, x.day), x.day);
      return `<i class="cal-fx" style="top:${top}px;height:${Math.max(3, y(Math.min(f.ended_at, K.addDays(x.day, 1)), x.day) - top)}px" title="Focused ${esc(hrs(f.focus_seconds / 60))}${f.label ? `: ${esc(f.label)}` : ''} (${esc(span(f.started_at, f.ended_at))})"></i>`;
    }).join('');
    const sel = c.sel && c.sel.day === x.day ? `<div class="cal-sel" style="top:${y(c.sel.start, x.day)}px;height:${Math.max(18, y(c.sel.end, x.day) - y(c.sel.start, x.day))}px"><span>${esc(span(c.sel.start, c.sel.end))}</span></div>` : '';
    const nowLine = x.day === today ? `<div class="cal-now" style="top:${y(now, x.day)}px" aria-hidden="true"></div>` : '';
    return `<div class="cal-col${x.day === today ? ' today' : ''}${x.day < today ? ' past' : ''}" data-cal-col="${x.day}">${fx}${blocks}${dues}${sel}${nowLine}</div>`;
  }).join('');

  const hours = Array.from({ length: hi - lo }, (_, i) => `<span style="top:${i * hh}px">${i ? esc(new Date(2000, 0, 1, lo + i).toLocaleTimeString([], { hour: 'numeric' })) : ''}</span>`).join('');

  return `
    <div class="cal-week cal-swipe" style="--tpl:${tpl};--hh:${hh}px;--rows:${hi - lo}">
      <div class="cal-top">
        <div class="cal-corner"><span>W${K.isoWeek(p.days[0])}</span></div>${head}
        ${anyAllDay ? `<div class="cal-corner ad">all-day</div>${allDay}` : ''}
      </div>
      <div class="cal-body">
        <div class="cal-hours">${hours}</div>${cols}
      </div>
    </div>`;
}

function allDayChips(x, d) {
  const chips = [
    ...x.brks.map((b) => `<button class="cal-chip brk" data-goto="breaks" title="Break${b.label ? `: ${esc(b.label)}` : ''}">${esc(b.label || 'Break')}</button>`),
    ...x.allDay.map((e) => `<button class="cal-chip" style="--cc:var(--c-${d.color(e.course_id)})" data-cal-ev="${e.id}">${esc(e.summary)}</button>`),
    ...x.dueAllDay.map((h) => `<button class="cal-chip due${h.done ? ' done' : h.due_at < Date.now() ? ' over' : ''}" data-hw-open="${h.id}" title="Due ${esc(dueLabel(h))}">Due: ${esc(h.title)}</button>`),
  ];
  return chips.join('');
}

function sessionBlock(e, d, pos, height, clash, now) {
  const course = d.byId[e.course_id];
  const counts = countsAttendance(course);
  const tag = effectiveTag(e, course);
  const past = e.start_at <= now;
  const st = !counts ? '' : e.status === 'absent' ? (past ? 'absent' : 'skip') : e.status === 'excused' ? 'excused' : e.status === 'present' ? 'present' : past ? 'unmarked' : '';
  const label = { absent: 'missed', skip: 'planned skip', excused: 'excused', present: 'attended', unmarked: 'unmarked' }[st] || '';
  const tagLabel = tag !== 'normal' && TAGS[tag] ? TAGS[tag].label : '';
  return `<div class="cal-item cal-ev st-${st || 'none'}${past ? ' past' : ''}${clash ? ' clash' : ''}${tag === 'exam' ? ' exam' : ''}" style="${pos};--cc:var(--c-${d.color(e.course_id)})"
      data-cal-ev="${e.id}" role="button" tabindex="0" aria-label="${esc(`${e.summary}, ${span(e.start_at, e.end_at)}${label ? `, ${label}` : ''}`)}">
    <div class="t">${tagLabel ? `<b class="tag">${esc(tagLabel)}</b> ` : ''}${esc(e.summary)}</div>
    ${height > 34 ? `<div class="s">${esc(span(e.start_at, e.end_at))}${e.location ? ` · ${esc(e.location)}` : ''}</div>` : ''}
    ${st === 'unmarked' ? '<i class="mark" title="Unmarked"></i>' : st === 'present' ? '<i class="mark ok" title="Attended"></i>' : ''}
  </div>`;
}

function planBlock(it, d, pos, height, now) {
  const h = it.h;
  const ghost = it.kind === 'ghost';
  const missed = !ghost && !h.done && it.end <= now;
  const attr = ghost ? `data-cal-ghost="${h.id}"` : `data-cal-plan="${h.id}"`;
  return `<div class="cal-item cal-plan${ghost ? ' ghost' : ''}${h.done ? ' done' : ''}${missed ? ' missed' : ''}" style="${pos};--cc:var(--c-${h.course_id ? d.color(h.course_id) : 'gray'})"
      ${attr} role="button" tabindex="0" aria-label="${esc(`${ghost ? 'Proposed: ' : 'Planned: '}${h.title}, ${span(it.start, it.end)}`)}">
    <div class="t">${h.done ? '<svg class="ic sm" aria-hidden="true"><use href="#i-check"/></svg>' : ''}${esc(h.title)}</div>
    ${height > 34 ? `<div class="s">${esc(span(it.start, it.end))}${missed ? ' · not done' : ''}</div>` : ''}
    ${ghost || h.done ? '' : '<span class="cal-grip" aria-hidden="true"></span>'}
  </div>`;
}

// ---------------------------------------------------------------- month

function monthView(c, p, d) {
  const now = Date.now();
  const today = K.startOfDay(now);
  const month = new Date(c.date).getMonth();
  const dows = p.days.slice(0, 7).map((day) => `<span>${esc(fmtDay(day, { weekday: isPhone() ? 'narrow' : 'short' }))}</span>`).join('');
  const cells = p.days.map((day) => {
    const x = dayItems(d, day);
    const exams = x.timed.filter((e) => effectiveTag(e, d.byId[e.course_id]) === 'exam');
    const lines = [
      ...x.brks.slice(0, 1).map((b) => `<li class="brk">${esc(b.label || 'Break')}</li>`),
      ...exams.map((e) => `<li class="exam" style="--cc:var(--c-${d.color(e.course_id)})">${esc(fmtTime(e.start_at))} ${esc(e.summary)}</li>`),
      ...[...x.dueTimed, ...x.dueAllDay].map((h) => `<li class="due${h.done ? ' done' : h.due_at < now ? ' over' : ''}">${esc(h.title)}</li>`),
      ...x.blocks.map((b) => `<li class="plan${b.h.done ? ' done' : ''}">${esc(fmtTime(b.start))} ${esc(b.h.title)}</li>`),
    ];
    const max = isPhone() ? 2 : 3;
    const dots = x.timed.filter((e) => !exams.includes(e)).slice(0, 8)
      .map((e) => `<i style="--cc:var(--c-${d.color(e.course_id)})"${e.status === 'absent' ? ' class="off"' : ''}></i>`).join('');
    const label = `${fmtDay(day, { weekday: 'long', day: 'numeric', month: 'long' })}: ${x.timed.length} session${x.timed.length === 1 ? '' : 's'}`;
    return `<button class="cal-cell${new Date(day).getMonth() !== month ? ' out' : ''}${day === today ? ' today' : ''}${day < today ? ' past' : ''}${x.brks.length ? ' on-break' : ''}" data-cal-open="${day}" aria-label="${esc(label)}">
      <span class="n">${new Date(day).getDate()}</span>
      ${dots ? `<span class="dots" aria-hidden="true">${dots}</span>` : ''}
      ${lines.length ? `<ul>${lines.slice(0, max).join('')}${lines.length > max ? `<li class="more">${lines.length - max} more</li>` : ''}</ul>` : ''}
    </button>`;
  }).join('');
  return `<div class="cal-month cal-swipe"><div class="cal-mh">${dows}</div><div class="cal-mgrid">${cells}</div></div>`;
}

// ---------------------------------------------------------------- "To plan"

// Open to-dos with no block, or whose block passed without them being done; in the order Today ranks them.
function unplannedTodos(now = Date.now()) {
  return state.data.homework
    .filter((h) => !h.done && (h.planned_at == null || plannedBlock(h)[1] <= now))
    .map((h) => ({ h, latest: h.due_at - effortOf(h) * MIN }))
    .sort((a, b) => Number(b.h.due_at < now) - Number(a.h.due_at < now) || a.latest - b.latest)
    .map((x) => x.h);
}

function tray(c, d) {
  const now = Date.now();
  const list = unplannedTodos(now).filter((h) => !(c.proposals || []).some((p) => p.id === h.id));
  return `
    <aside class="cal-tray" aria-label="To plan">
      <h4>To plan ${list.length ? `<span class="muted">${list.length}</span>` : ''}</h4>
      ${list.length ? `<div class="cal-todos">${list.slice(0, 40).map((h) => `
        <div class="cal-todo${c.placing === h.id ? ' on' : ''}" data-cal-todo="${h.id}" role="button" tabindex="0" style="--cc:var(--c-${h.course_id ? d.color(h.course_id) : 'gray'})"
          title="${isPhone() ? 'Tap, then tap a time' : 'Drag onto the calendar, or click and pick a time'}">
          <span class="dot" aria-hidden="true"></span>
          <span class="txt"><span class="t">${esc(h.title)}</span>
            <span class="s${h.due_at < now ? ' over' : ''}">${h.planned_at != null ? `missed ${esc(fmtDay(h.planned_at, { weekday: 'short' }))} · ` : ''}due ${esc(dueLabel(h))} · ${esc(lenLabel(effortOf(h)))}</span></span>
        </div>`).join('')}</div>
        <p class="hint">${isPhone() ? 'Tap one, then a time.' : 'Drag one onto the calendar, or click it and pick a time.'} <b>Plan my week</b> places them all.</p>`
        : '<p class="empty">Every open to-do has a time. Click an empty slot to add one.</p>'}
      <details class="cal-layers"${isPhone() ? '' : ' open'}>
        <summary>Show</summary>
        ${LAYERS.map(([k, l]) => `<label class="checkbox check"><input type="checkbox" data-cal-layer="${k}" ${c.layers[k] ? 'checked' : ''}><span></span>${l}</label>`).join('')}
      </details>
    </aside>`;
}

// ---------------------------------------------------------------- popovers

function popover(c, d) {
  const pop = c.pop;
  if (pop.kind === 'ev') {
    const e = state.data.events.find((x) => x.id === pop.id);
    if (!e) { c.pop = null; return ''; }
    const course = d.byId[e.course_id];
    const counts = countsAttendance(course);
    const tag = effectiveTag(e, course);
    const past = e.start_at <= Date.now();
    const st = e.status;
    const catchup = state.data.homework.find((h) => h.kind === 'catchup' && h.event_id === e.id);
    const clashes = state.data.events.filter((o) => o.id !== e.id && o.course_id !== e.course_id && d.byId[o.course_id]?.tracked && o.start_at < e.end_at && e.start_at < o.end_at);
    const due = homeworkForSession(e);
    return `<div class="cal-pop" id="cal-pop" role="dialog" aria-label="${esc(e.summary)}" data-event="${e.id}" style="--cc:var(--c-${d.color(e.course_id)})">
      <button class="icon-btn cal-pop-x" data-cal-close aria-label="Close"><svg class="ic sm" aria-hidden="true"><use href="#i-plus"/></svg></button>
      <div class="cal-pop-head"><span class="sw" aria-hidden="true"></span><b>${esc(e.summary)}</b></div>
      <div class="cal-pop-meta">
        <span>${esc(fmtDay(e.start_at, { weekday: 'long', day: 'numeric', month: 'long' }))}, ${esc(span(e.start_at, e.end_at))}</span>
        ${e.location ? `<span>${esc(e.location)}</span>` : ''}
        <span>${tag !== 'normal' ? `<span class="chip tag-${tag}">${esc(TAGS[tag].label.toLowerCase())}</span> ` : ''}${groupChip(e.cals)}</span>
        ${e.note ? `<span><em>${esc(e.note)}</em></span>` : ''}
        ${clashes.length ? `<span class="bad">Overlaps ${clashes.slice(0, 2).map((o) => esc(o.summary)).join(', ')}</span>` : ''}
      </div>
      ${counts ? `<div class="cal-pop-row"><div class="status" role="group" aria-label="Attendance">
        <button data-status="present" class="${st === 'present' ? 'on' : ''}" title="${past ? 'Attended' : 'Will attend'}">✓</button>
        <button data-status="absent" class="${st === 'absent' ? 'on' : ''} ${TAGS[tag].mustAttend && !past ? 'danger' : ''}" title="${past ? 'Absent' : 'Plan to skip'}">×</button>
        <button data-status="excused" class="${st === 'excused' ? 'on' : ''}" title="Excused (doesn't count)">E</button>
        ${past ? `<button data-action="catchup" class="${catchup ? 'on' : ''}" title="${catchup ? 'Catch-up added: open it' : 'Missed it? Add a catch-up'}">↺</button>` : ''}
        <button data-action="note" title="Add a note">+</button>
      </div><span class="muted small">${st === 'absent' ? (past ? 'Missed' : 'Planned skip') : st === 'present' ? (past ? 'Attended' : 'Will attend') : st === 'excused' ? 'Excused' : past ? 'Not marked yet' : ''}</span></div>` : ''}
      ${due.length ? `<div class="cal-pop-sec">Due at this session</div>${due.map((h) => `<a href="#homework" class="cal-pop-link" data-hw-open="${h.id}">${esc(h.title)}</a>`).join('')}` : ''}
      <div class="cal-pop-foot"><a href="#sessions" data-course-open="${e.course_id}">All ${esc(course?.name || 'course')} sessions →</a></div>
    </div>`;
  }

  if (pop.kind === 'plan') {
    const h = hwById(pop.id);
    if (!h || h.planned_at == null) { c.pop = null; return ''; }
    const [s, e] = plannedBlock(h);
    const minutes = Math.round((e - s) / MIN);
    const course = d.byId[h.course_id];
    const lengths = LENGTHS.includes(minutes) ? LENGTHS : [...LENGTHS, minutes].sort((a, b) => a - b);
    return `<div class="cal-pop" id="cal-pop" role="dialog" aria-label="${esc(h.title)}" data-hw="${h.id}" style="--cc:var(--c-${h.course_id ? d.color(h.course_id) : 'gray'})">
      <button class="icon-btn cal-pop-x" data-cal-close aria-label="Close"><svg class="ic sm" aria-hidden="true"><use href="#i-plus"/></svg></button>
      <div class="cal-pop-head">
        <label class="checkbox round hw-check" title="${h.done ? 'Mark not done' : 'Mark done'}"><input type="checkbox" data-hw-done ${h.done ? 'checked' : ''} aria-label="Done: ${esc(h.title)}"><span></span></label>
        <b>${esc(h.title)}</b>
      </div>
      <div class="cal-pop-meta">
        <span>${esc(fmtDay(s, { weekday: 'long', day: 'numeric', month: 'long' }))}, ${esc(span(s, e))}</span>
        <span class="${!h.done && h.due_at < Date.now() ? 'bad' : ''}">Due ${esc(dueLabel(h))}${course ? ` · ${esc(course.name)}` : ''}</span>
        ${h.notes ? `<span class="muted">${esc(h.notes.length > 140 ? `${h.notes.slice(0, 140)}…` : h.notes)}</span>` : ''}
      </div>
      <div class="cal-pop-row">
        ${h.done ? '' : '<button class="btn btn-primary btn-small" data-hw-focus><svg class="ic sm" aria-hidden="true"><use href="#i-focus"/></svg>Focus now</button>'}
        <div class="select"><select data-cal-len aria-label="Length">${lengths.map((m) => `<option value="${m}" ${m === minutes ? 'selected' : ''}>${lenLabel(m)}</option>`).join('')}</select></div>
        <button class="btn btn-small" data-cal-move title="Pick a new time">Move</button>
        <button class="btn btn-small" data-cal-unplan title="Back to To plan">Unplan</button>
      </div>
      <div class="cal-pop-foot"><button class="btn btn-borderless btn-small" data-hw-edit>Edit details</button></div>
    </div>`;
  }

  // A new to-do in the selected slot, or an existing one moved there.
  const sel = c.sel;
  if (!sel) { c.pop = null; return ''; }
  const tracked = state.data.courses.filter((x) => x.tracked);
  const todos = unplannedTodos().slice(0, 5);
  return `<div class="cal-pop" id="cal-pop" role="dialog" aria-label="New to-do">
    <button class="icon-btn cal-pop-x" data-cal-close aria-label="Close"><svg class="ic sm" aria-hidden="true"><use href="#i-plus"/></svg></button>
    <form id="cal-new" autocomplete="off">
      <div class="cal-pop-when">${esc(fmtDay(sel.start, { weekday: 'long', day: 'numeric', month: 'short' }))} · ${esc(span(sel.start, sel.end))}</div>
      <input class="field cal-new-title" name="title" required maxlength="200" placeholder="What will you work on?" aria-label="To-do">
      <div class="cal-pop-row">
        <div class="select"><select name="course_id" aria-label="Course"><option value="">No course</option>
          ${tracked.map((x) => `<option value="${x.id}" ${x.id === state.hwCourse ? 'selected' : ''}>${esc(x.name)}</option>`).join('')}</select></div>
        <button class="btn btn-primary btn-small" type="submit" name="as" value="plan">Add</button>
      </div>
      <button class="btn btn-borderless btn-small cal-as-due" type="submit" name="as" value="due">Add as a deadline at ${esc(fmtTime(sel.start))} instead</button>
    </form>
    ${todos.length ? `<div class="cal-pop-sec">Or plan one here</div>
      ${todos.map((h) => `<button class="cal-pop-link" data-cal-here="${h.id}">${esc(h.title)}<span class="muted"> · ${esc(lenLabel(effortOf(h)))}</span></button>`).join('')}` : ''}
  </div>`;
}

// ---------------------------------------------------------------- after render

function afterPaint() {
  if (state.tab !== 'calendar') return;
  const c = cal();
  const topbar = document.getElementById('topbar');
  document.documentElement.style.setProperty('--topbar-h', `${topbar.offsetHeight}px`);
  if (c.scroll) { c.scroll = false; scrollToFocus(); }
  positionPop();
  if (c.pop?.fresh) {
    c.pop.fresh = false;
    const pop = document.getElementById('cal-pop');
    (pop?.querySelector('.cal-new-title') || pop?.querySelector('button, input, select'))?.focus({ preventScroll: true });
  }
}

// Brings now into view (or the first item of the period, or 8:00), a third of the way down.
function scrollToFocus() {
  const body = $app.querySelector('.cal-body');
  if (!body) { $scroller.scrollTop = 0; return; }
  const now = $app.querySelector('.cal-now');
  const firstTop = Math.min(...[...$app.querySelectorAll('.cal-col .cal-item')].map((el) => el.offsetTop));
  const y = now ? now.offsetTop : Number.isFinite(firstTop) ? firstTop - 12 : (DAY_START - geom.lo) * geom.hh;
  const head = $app.querySelector('.cal-top')?.offsetHeight || 0;
  const bodyTop = body.getBoundingClientRect().top - $scroller.getBoundingClientRect().top + $scroller.scrollTop;
  const view = $scroller.clientHeight - head - (document.getElementById('topbar').offsetHeight);
  $scroller.scrollTop = Math.max(0, bodyTop + y - head - document.getElementById('topbar').offsetHeight - (now ? view / 3 : 0));
}

// Next to what it's about on a wide screen; a sheet at the bottom on a phone (CSS).
function positionPop() {
  const pop = document.getElementById('cal-pop');
  if (!pop || isPhone()) return;
  const c = cal();
  const anchor = c.pop.kind === 'ev' ? $app.querySelector(`.cal-col [data-cal-ev="${c.pop.id}"], [data-cal-ev="${c.pop.id}"]`)
    : c.pop.kind === 'plan' ? $app.querySelector(`[data-cal-plan="${c.pop.id}"]`) : $app.querySelector('.cal-sel');
  const w = pop.offsetWidth, h = pop.offsetHeight;
  if (!anchor) { pop.style.left = `${(innerWidth - w) / 2}px`; pop.style.top = `${Math.max(12, (innerHeight - h) / 3)}px`; return; }
  const r = anchor.getBoundingClientRect();
  let left = r.right + 10;
  if (left + w > innerWidth - 12) left = r.left - w - 10;
  if (left < 12) left = Math.min(Math.max(12, r.left), innerWidth - w - 12);
  const top = Math.min(Math.max(12, r.top), innerHeight - h - 12);
  pop.style.left = `${left}px`;
  pop.style.top = `${top}px`;
}

// ---------------------------------------------------------------- actions

function setView(view) {
  const c = cal();
  c.view = view;
  c.pop = null; c.sel = null; c.scroll = true;
  try { localStorage.setItem(VIEW_KEY, view); } catch { /* storage unavailable */ }
  render();
}

function nav(dir) {
  const c = cal();
  c.date = dir === 0 ? Date.now() : K.shift(c.view, c.date, dir);
  c.pop = null; c.sel = null; c.scroll = true;
  render();
}

function closePop() {
  const c = cal();
  if (!c.pop && !c.sel) return false;
  c.pop = null; c.sel = null;
  render();
  return true;
}

function openDay(day) {
  const c = cal();
  c.date = day;
  c.view = 'day';
  c.pop = null; c.sel = null; c.scroll = true;
  render();
}

// Coming to the page from another one: today, nothing open.
export function enter() {
  const c = cal();
  Object.assign(c, { date: Date.now(), pop: null, sel: null, placing: null, scroll: true });
}

// Shows the calendar on a date, from anywhere (a "planned" chip on a homework row).
export function showDate(ms) {
  const c = cal();
  c.date = ms;
  if (c.view === 'month') c.view = isPhone() ? '3day' : 'week';
  c.pop = null; c.sel = null; c.scroll = true;
  state.tab = 'calendar';
  history.replaceState(null, '', '#calendar');
  render();
}

function upsert(row) {
  const list = state.data.homework;
  const i = list.findIndex((h) => h.id === row.id);
  if (i >= 0) list[i] = row; else list.push(row);
  list.sort((a, b) => a.due_at - b.due_at);
}

// Moves, resizes, plans or unplans one to-do: shown at once, put back if the server says no.
async function setPlan(id, plannedAt, minutes) {
  const h = hwById(id);
  if (!h) return;
  const before = { planned_at: h.planned_at, planned_minutes: h.planned_minutes };
  const body = { planned_at: plannedAt, planned_minutes: plannedAt == null ? null : minutes };
  Object.assign(h, body);
  render();
  try {
    upsert(await api(`/api/homework/${id}`, { method: 'PATCH', body }));
    render();
  } catch (err) {
    Object.assign(h, before);
    render();
    toast(err.message, true);
  }
}

// The block a to-do gets when it's dropped somewhere: its effort, up to 2 h.
const dropLength = (h) => Math.min(effortOf(h), MAX_BLOCK);

function placeAt(id, start) {
  const c = cal();
  const h = hwById(id);
  c.placing = null; c.pop = null; c.sel = null;
  if (!h) return render();
  const minutes = h.planned_at != null && h.planned_minutes ? h.planned_minutes : dropLength(h);
  setPlan(id, start, minutes);
  toast(`Planned “${h.title}” for ${fmtDay(start)} ${fmtTime(start)}.`);
}

export function autoPlanNow() {
  const c = cal();
  const now = Date.now();
  const { events, homework } = state.data;
  const byId = courseById();
  const busy = events.filter((e) => byId[e.course_id]?.tracked && e.end_at > now && e.end_at - e.start_at < 20 * HOUR
    && e.status !== 'excused' && !(e.status === 'absent' && e.start_at > now)).map((e) => [e.start_at, e.end_at]);
  const blocks = homework.filter((h) => !h.done).map(plannedBlock).filter((b) => b && b[1] > now);
  const todos = unplannedTodos(now).filter((h) => h.due_at < now + PLAN_AHEAD).map((h) => ({ id: h.id, due_at: h.due_at, effort: effortOf(h) }));
  if (!todos.length) {
    toast(unplannedTodos(now).length ? 'Nothing due in the next two weeks needs a time.' : 'Every open to-do already has a time.');
    return;
  }
  const r = K.autoPlan({ todos, busy, blocks, now, until: K.addDays(K.startOfDay(now), 8), dayStart: DAY_START, dayEnd: DAY_END, maxBlock: MAX_BLOCK });
  c.proposals = r.planned;
  c.unplaced = r.unplaced;
  c.placing = null; c.pop = null; c.sel = null;
  // Show the week the plan is in.
  if (c.view === 'month' || c.view === 'day' || !(now >= K.period(c.view, c.date).start && now < K.period(c.view, c.date).end)) {
    if (c.view === 'month' || c.view === 'day') c.view = isPhone() ? '3day' : 'week';
    c.date = now;
  }
  c.scroll = true;
  render();
}

async function keepProposals() {
  const c = cal();
  const items = c.proposals.map((p) => ({ id: p.id, planned_at: p.start, planned_minutes: p.minutes }));
  const before = items.map((it) => { const h = hwById(it.id); return [h, h && { planned_at: h.planned_at, planned_minutes: h.planned_minutes }]; });
  c.proposals = null; c.unplaced = [];
  for (const it of items) Object.assign(hwById(it.id) || {}, { planned_at: it.planned_at, planned_minutes: it.planned_minutes });
  render();
  try {
    const { homework } = await api('/api/homework/plan', { method: 'POST', body: { items } });
    homework.forEach(upsert);
    render();
    toast(`Planned ${items.length} to-do${items.length === 1 ? '' : 's'}.`);
  } catch (err) {
    for (const [h, was] of before) if (h) Object.assign(h, was);
    render();
    toast(err.message, true);
  }
}

async function createTodo(form, as) {
  const c = cal();
  const sel = c.sel;
  const fd = new FormData(form);
  const minutes = Math.max(SNAP, Math.round((sel.end - sel.start) / MIN));
  const courseId = fd.get('course_id') ? Number(fd.get('course_id')) : null;
  const body = as === 'due'
    ? { title: fd.get('title'), notes: '', course_id: courseId, due_date: localDate(sel.start), due_time: `${pad(new Date(sel.start).getHours())}:${pad(new Date(sel.start).getMinutes())}`, tz: tz(), remind_minutes: 1440 }
    : { title: fd.get('title'), notes: '', course_id: courseId, due_date: localDate(sel.start), due_time: null, tz: tz(), remind_minutes: null,
      effort_minutes: minutes, planned_at: sel.start, planned_minutes: minutes };
  form.querySelectorAll('button').forEach((b) => { b.disabled = true; });
  try {
    upsert(await api('/api/homework', { method: 'POST', body }));
    state.hwCourse = courseId;
    c.pop = null; c.sel = null;
    render();
    toast(as === 'due' ? `Added “${body.title}”, due ${fmtTime(sel.start)}.` : `Planned “${body.title}”.`);
  } catch (err) {
    form.querySelectorAll('button').forEach((b) => { b.disabled = false; });
    toast(err.message, true);
  }
}

// ---------------------------------------------------------------- pointer: select, drag, resize, drop

// Minutes after midnight at a pointer's height in a column, on the 15-minute grid.
function minutesAt(col, clientY, round = Math.round) {
  const r = col.getBoundingClientRect();
  const m = ((clientY - r.top) / geom.hh) * 60 + geom.lo * 60;
  return Math.max(0, Math.min(24 * 60, round(m / SNAP) * SNAP));
}
const colAt = (x, y) => document.elementFromPoint(x, y)?.closest?.('.cal-col');

let drag = null;
let swallow = false;   // the click that follows a handled drag

function liveSel(col, start, end, label) {
  let el = $app.querySelector('.cal-sel.live');
  if (!el) { el = document.createElement('div'); el.className = 'cal-sel live'; el.innerHTML = '<span></span>'; }
  if (el.parentNode !== col) col.append(el);
  const day = Number(col.dataset.calCol);
  const top = ((start - day) / HOUR - geom.lo) * geom.hh;
  el.style.top = `${top}px`;
  el.style.height = `${Math.max(18, ((end - start) / HOUR) * geom.hh)}px`;
  el.firstChild.textContent = label ?? span(start, end);
}

document.addEventListener('pointerdown', (e) => {
  swallow = false;
  if (state.tab !== 'calendar' || e.button !== 0 || e.pointerType === 'touch') return;
  const t = e.target;
  if (t.closest('.cal-pop')) return;
  const c = cal();
  const todo = t.closest('[data-cal-todo]');
  const block = t.closest('[data-cal-plan], [data-cal-ghost]');
  const col = t.closest('.cal-col');
  const p0 = { x0: e.clientX, y0: e.clientY, moved: false };
  if (todo) drag = { ...p0, kind: 'todo', id: Number(todo.dataset.calTodo) };
  else if (block) {
    const ghost = block.dataset.calGhost != null;
    drag = { ...p0, kind: t.closest('.cal-grip') ? 'resize' : 'move', ghost, id: Number(ghost ? block.dataset.calGhost : block.dataset.calPlan), el: block,
      grab: e.clientY - block.getBoundingClientRect().top };
  } else if (col && !t.closest('.cal-item, .cal-due')) {
    // A click outside an open popover only closes it.
    if (c.pop) { e.preventDefault(); swallow = true; closePop(); return; }
    drag = { ...p0, kind: 'select', col, day: Number(col.dataset.calCol) };
  }
  if (drag) e.preventDefault(); // no text selection while dragging
});

document.addEventListener('pointermove', (e) => {
  if (!drag) return;
  if (!drag.moved) {
    if (Math.hypot(e.clientX - drag.x0, e.clientY - drag.y0) < 4) return;
    drag.moved = true;
    document.body.classList.add('cal-dragging');
    if (drag.kind === 'move' || drag.kind === 'resize') drag.el.classList.add('dragging');
    if (drag.kind === 'todo') {
      const h = hwById(drag.id);
      drag.float = Object.assign(document.createElement('div'), { className: 'cal-float', textContent: h?.title || '' });
      document.body.append(drag.float);
    }
  }
  if (drag.kind === 'select') {
    const a = minutesAt(drag.col, drag.y0, Math.floor), b = minutesAt(drag.col, e.clientY, Math.ceil);
    const [s, en] = [Math.min(a, b), Math.max(a, b, Math.min(a, b) + SNAP)];
    drag.range = [K.atMinutes(drag.day, s), K.atMinutes(drag.day, en)];
    liveSel(drag.col, ...drag.range);
  } else if (drag.kind === 'resize') {
    const col = drag.el.closest('.cal-col');
    const h = hwById(drag.id);
    const start = drag.ghost ? cal().proposals.find((p) => p.id === drag.id).start : h.planned_at;
    const end = Math.max(start + SNAP * MIN, K.atMinutes(Number(col.dataset.calCol), minutesAt(col, e.clientY)));
    drag.minutes = Math.min(12 * 60, Math.round((end - start) / MIN));
    drag.el.style.height = `${Math.max(18, (drag.minutes / 60) * geom.hh - 2)}px`;
    drag.el.querySelector('.s')?.replaceChildren(span(start, start + drag.minutes * MIN));
  } else {
    if (drag.float) drag.float.style.transform = `translate(${e.clientX + 12}px, ${e.clientY + 8}px)`;
    const col = colAt(e.clientX, e.clientY);
    if (!col) { drag.target = null; $app.querySelector('.cal-sel.live')?.remove(); return; }
    const h = hwById(drag.id);
    const len = drag.kind === 'todo' ? dropLength(h)
      : drag.ghost ? cal().proposals.find((p) => p.id === drag.id).minutes : Math.round((plannedBlock(h)[1] - plannedBlock(h)[0]) / MIN);
    const start = K.atMinutes(Number(col.dataset.calCol), minutesAt(col, e.clientY - (drag.grab || 0)));
    drag.target = start;
    if (drag.kind === 'move') drag.el.style.visibility = 'hidden';
    liveSel(col, start, start + len * MIN);
  }
});

document.addEventListener('pointerup', (e) => {
  if (!drag) return;
  const d = drag;
  drag = null;
  document.body.classList.remove('cal-dragging');
  d.float?.remove();
  $app.querySelector('.cal-sel.live')?.remove();
  swallow = true;
  setTimeout(() => { swallow = false; }, 0); // only the click this pointerup makes
  const c = cal();
  if (!d.moved) {
    // A plain click.
    if (d.kind === 'todo') { c.placing = c.placing === d.id ? null : d.id; c.pop = null; c.sel = null; return render(); }
    if (d.kind === 'select') return slotClick(d.col, e.clientY);
    if (d.ghost) return dropProposal(d.id);
    c.pop = { kind: 'plan', id: d.id, fresh: true }; c.sel = null;
    return render();
  }
  if (d.kind === 'select') {
    if (c.placing) return placeAt(c.placing, d.range[0]);
    c.sel = { day: d.day, start: d.range[0], end: d.range[1] };
    c.pop = { kind: 'new', fresh: true };
    return render();
  }
  if (d.kind === 'resize') {
    if (d.ghost) { c.proposals.find((p) => p.id === d.id).minutes = d.minutes; return render(); }
    return setPlan(d.id, hwById(d.id).planned_at, d.minutes);
  }
  if (d.target == null) return render();
  if (d.kind === 'todo') return placeAt(d.id, d.target);
  if (d.ghost) { c.proposals.find((p) => p.id === d.id).start = d.target; return render(); }
  const h = hwById(d.id);
  return setPlan(d.id, d.target, Math.round((plannedBlock(h)[1] - plannedBlock(h)[0]) / MIN));
});

document.addEventListener('pointercancel', () => {
  if (!drag) return;
  drag.float?.remove();
  drag = null;
  document.body.classList.remove('cal-dragging');
  render();
});

// A click (or tap) on an empty slot: plan the waiting to-do there, or open "new to-do" for an hour from there.
function slotClick(col, clientY) {
  const c = cal();
  const day = Number(col.dataset.calCol);
  const start = K.atMinutes(day, minutesAt(col, clientY, Math.floor));
  if (c.placing) return placeAt(c.placing, start);
  if (c.pop) return closePop();
  c.sel = { day, start, end: Math.min(K.addDays(day, 1), start + NEW_BLOCK * MIN) };
  c.pop = { kind: 'new', fresh: true };
  render();
}

function dropProposal(id) {
  const c = cal();
  c.proposals = c.proposals.filter((p) => p.id !== id);
  render();
}

// Swipe left or right on the grid for the next or previous period.
let touch = null;
document.addEventListener('touchstart', (e) => {
  if (state.tab !== 'calendar' || e.touches.length !== 1 || !e.target.closest('.cal-swipe') || e.target.closest('.cal-pop')) { touch = null; return; }
  touch = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() };
}, { passive: true });
document.addEventListener('touchend', (e) => {
  if (!touch) return;
  const dx = e.changedTouches[0].clientX - touch.x, dy = e.changedTouches[0].clientY - touch.y;
  const quick = Date.now() - touch.t < 600;
  touch = null;
  if (quick && Math.abs(dx) > 60 && Math.abs(dx) > 2 * Math.abs(dy)) nav(dx < 0 ? 1 : -1);
}, { passive: true });

// Keeps the popover next to its block while the page scrolls, and the now line moving.
$scroller.addEventListener('scroll', () => { if (state.tab === 'calendar' && state.cal?.pop) positionPop(); }, { passive: true });
let paintedDay = K.startOfDay(Date.now());
setInterval(() => {
  if (state.tab !== 'calendar' || drag || document.hidden) return;
  if (K.startOfDay(Date.now()) !== paintedDay) { paintedDay = K.startOfDay(Date.now()); return render(); }
  const line = $app.querySelector('.cal-now');
  const col = line?.closest('.cal-col');
  if (line && col) line.style.top = `${((Date.now() - Number(col.dataset.calCol)) / HOUR - geom.lo) * geom.hh}px`;
}, 30000);

// ---------------------------------------------------------------- palette and keys

export function paletteItems(go) {
  const act = (id, label, icon, run, extra = {}) => ({ id: `act:${id}`, section: 'Actions', label, icon, run, ...extra });
  const to = (view) => () => { go('calendar'); setView(view); };
  return [
    act('cal-plan', 'Plan my week', 'i-wand', () => { go('calendar'); autoPlanNow(); }, { home: true, sub: 'fit open to-dos into free time', keywords: 'schedule auto time block calendar' }),
    act('cal-day', 'Calendar: day view', 'i-month', to('day'), { keywords: 'calendar' }),
    act('cal-3day', 'Calendar: 3 days', 'i-month', to('3day'), { keywords: 'calendar three' }),
    act('cal-week', 'Calendar: week view', 'i-month', to('week'), { keywords: 'calendar' }),
    act('cal-month', 'Calendar: month view', 'i-month', to('month'), { keywords: 'calendar' }),
  ];
}

export const SHORTCUTS = [
  [['T'], 'Calendar: today'], [['J'], 'Calendar: next (or →)'], [['K'], 'Calendar: previous (or ←)'],
  [['1', '–', '4'], 'Calendar: day, 3 days, week, month'], [['P'], 'Calendar: plan my week'],
];

// Keys on the Calendar page (not while typing).
export function onPageKey(e) {
  const k = e.key.toLowerCase();
  const views = { 1: 'day', d: 'day', 2: '3day', x: '3day', 3: 'week', w: 'week', 4: 'month' };
  if (k === 't') nav(0);
  else if (k === 'j' || e.key === 'ArrowRight') nav(1);
  else if (k === 'k' || e.key === 'ArrowLeft') nav(-1);
  else if (views[k]) setView(views[k]);
  else if (k === 'p') autoPlanNow();
  else return false;
  e.preventDefault();
  return true;
}

// Before the app's own keys: Escape steps back out (popover, then placing, then a proposed plan); Enter opens.
export function onKeydown(e) {
  if (state.tab !== 'calendar' || !state.cal) return false;
  const c = cal();
  if (e.key === 'Escape') {
    if (c.pop || c.sel) { closePop(); return true; }
    if (c.placing) { c.placing = null; render(); return true; }
    if (c.proposals) { c.proposals = null; c.unplaced = []; render(); return true; }
    return false;
  }
  if (e.key === 'Enter' && e.target.matches?.('.cal-item, .cal-todo')) { e.preventDefault(); e.target.click(); return true; }
  return false;
}

// ---------------------------------------------------------------- events (return true if handled)

export function onClick(e) {
  const t = e.target;
  const show = t.closest('[data-cal-show]');
  if (show) { e.preventDefault(); showDate(Number(show.dataset.calShow)); return true; }
  if (state.tab !== 'calendar') return false;
  if (swallow) { swallow = false; return true; }
  const c = cal();

  const navBtn = t.closest('[data-cal-nav]');
  if (navBtn) { nav(Number(navBtn.dataset.calNav)); return true; }
  if (t.closest('[data-cal-pick]')) {
    const input = $app.querySelector('[data-cal-jump]');
    try { input.showPicker(); } catch { input.focus(); }
    return true;
  }
  if (t.closest('[data-cal-autoplan]')) { autoPlanNow(); return true; }
  if (t.closest('[data-cal-keep]')) { keepProposals(); return true; }
  if (t.closest('[data-cal-discard]')) { c.proposals = null; c.unplaced = []; render(); return true; }
  if (t.closest('[data-cal-cancel]')) { c.placing = null; render(); return true; }
  if (t.closest('[data-cal-close]')) { closePop(); return true; }

  const here = t.closest('[data-cal-here]');
  if (here && c.sel) {
    const id = Number(here.dataset.calHere);
    const start = c.sel.start;
    c.pop = null; c.sel = null;
    setPlan(id, start, dropLength(hwById(id)));
    return true;
  }
  const pop = t.closest('#cal-pop');
  if (pop) {
    const id = Number(pop.dataset.hw);
    if (t.closest('[data-cal-move]')) { c.placing = id; c.pop = null; render(); return true; }
    if (t.closest('[data-cal-unplan]')) { c.pop = null; setPlan(id, null, null); return true; }
    if (t.closest('[data-hw-focus]')) { c.pop = null; render(); focusOn(id); return true; }
    // Links to other pages close it; attendance buttons and the done box are the app's own handlers.
    if (t.closest('a, [data-hw-open], [data-course-open], [data-hw-edit]')) { c.pop = null; c.sel = null; }
    return false;
  }

  const ev = t.closest('[data-cal-ev]');
  if (ev) { c.pop = { kind: 'ev', id: Number(ev.dataset.calEv), fresh: true }; c.sel = null; render(); return true; }
  const plan = t.closest('[data-cal-plan]');
  if (plan) {
    if (c.placing) return slotClick(plan.closest('.cal-col'), e.clientY), true;
    c.pop = { kind: 'plan', id: Number(plan.dataset.calPlan), fresh: true }; c.sel = null; render(); return true;
  }
  const ghost = t.closest('[data-cal-ghost]');
  if (ghost) { dropProposal(Number(ghost.dataset.calGhost)); return true; }
  const todo = t.closest('[data-cal-todo]');
  if (todo) {
    const id = Number(todo.dataset.calTodo);
    c.placing = c.placing === id ? null : id; c.pop = null; c.sel = null;
    render();
    return true;
  }
  const open = t.closest('[data-cal-open]');
  if (open) {
    if (c.placing && c.view === 'month') { c.date = Number(open.dataset.calOpen); c.view = isPhone() ? '3day' : 'week'; c.scroll = true; render(); return true; }
    openDay(Number(open.dataset.calOpen));
    return true;
  }
  const col = t.closest('.cal-col');
  if (col && !t.closest('.cal-due')) { slotClick(col, e.clientY); return true; }
  if (c.pop && !t.closest('.cal-pop')) closePop();
  return false;
}

export function onChange(e) {
  if (state.tab !== 'calendar') return false;
  const t = e.target;
  const c = cal();
  if (t.name === 'cal-view') { setView(t.value); return true; }
  if (t.matches('[data-cal-jump]')) {
    if (t.value) { c.date = new Date(`${t.value}T12:00:00`).getTime(); c.pop = null; c.sel = null; c.scroll = true; render(); }
    return true;
  }
  if (t.matches('[data-cal-layer]')) {
    c.layers[t.dataset.calLayer] = t.checked;
    try { localStorage.setItem(LAYER_KEY, JSON.stringify(c.layers)); } catch { /* storage unavailable */ }
    render();
    return true;
  }
  if (t.matches('[data-cal-len]')) {
    const id = Number(t.closest('[data-hw]').dataset.hw);
    setPlan(id, hwById(id).planned_at, Number(t.value));
    return true;
  }
  return false;
}

export function onSubmit(e) {
  if (e.target.id !== 'cal-new') return false;
  e.preventDefault();
  createTodo(e.target, e.submitter?.value || 'plan');
  return true;
}
