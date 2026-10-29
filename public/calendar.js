// Calendar maths, kept apart from the page (public/calendar-ui.js) so it can be tested: the days each view shows,
// overlapping items side by side, course colours, and "Plan my week", which fits open to-dos into free time before
// their deadlines. Local time throughout: it only runs in the browser.

const MIN = 60000;
const DAY = 24 * 60 * MIN;

// Days per view; a month shows the weeks it touches.
export const VIEWS = { day: 1, '3day': 3, week: 7, month: 0 };

export const startOfDay = (ms) => new Date(ms).setHours(0, 0, 0, 0);
export const addDays = (ms, n) => { const d = new Date(ms); d.setDate(d.getDate() + n); return d.getTime(); };
export const mondayOf = (ms) => addDays(startOfDay(ms), -((new Date(ms).getDay() + 6) % 7));
// The instant `minutes` after the start of a local day (DST-safe: 9:00 is 9:00 on a 23-hour day too).
export const atMinutes = (day, minutes) => new Date(day).setHours(0, minutes, 0, 0);

// The days a view shows around `date`: { start, end, days: [local midnights] }.
export function period(view, date) {
  if (view === 'month') {
    const first = new Date(startOfDay(date));
    first.setDate(1);
    const start = mondayOf(first.getTime());
    const next = new Date(first);
    next.setMonth(first.getMonth() + 1);
    const n = Math.ceil(Math.round((next.getTime() - start) / DAY) / 7) * 7;
    const days = Array.from({ length: n }, (_, i) => addDays(start, i));
    return { start, end: addDays(start, n), days };
  }
  const start = view === 'week' ? mondayOf(date) : startOfDay(date);
  const days = Array.from({ length: VIEWS[view] }, (_, i) => addDays(start, i));
  return { start, end: addDays(start, days.length), days };
}

// The date one period before (dir -1) or after (dir 1).
export function shift(view, date, dir) {
  if (view !== 'month') return addDays(date, dir * VIEWS[view]);
  const d = new Date(date);
  d.setDate(1);
  d.setMonth(d.getMonth() + dir);
  return d.getTime();
}

// ISO 8601 week number.
export function isoWeek(ms) {
  const d = new Date(startOfDay(ms));
  d.setDate(d.getDate() + 3 - ((d.getDay() + 6) % 7)); // the Thursday of this week decides the year
  const jan4 = new Date(d.getFullYear(), 0, 4);
  return 1 + Math.round(((d - jan4) / DAY - 3 + ((jan4.getDay() + 6) % 7)) / 7);
}

// Items that overlap share the width of their day, like in Google Calendar: [{ id, start, end }] → Map id →
// { col, cols }. A group of items that overlap one another, directly or through others, has `cols` columns, and each
// item takes the first column free at its start.
export function layout(items) {
  const out = new Map();
  let group = [], ends = [], groupEnd = -Infinity;
  const close = () => { for (const id of group) out.get(id).cols = ends.length; group = []; ends = []; };
  for (const it of [...items].sort((a, b) => a.start - b.start || b.end - a.end)) {
    if (it.start >= groupEnd) { close(); groupEnd = -Infinity; }
    let col = ends.findIndex((end) => end <= it.start);
    if (col < 0) { col = ends.length; ends.push(it.end); } else ends[col] = it.end;
    out.set(it.id, { col, cols: 1 });
    group.push(it.id);
    groupEnd = Math.max(groupEnd, it.end);
  }
  close();
  return out;
}

// One colour per course, the tracked ones first so the courses you see get different colours.
const PALETTE = ['blue', 'orange', 'green', 'purple', 'teal', 'pink', 'yellow', 'red'];
export function courseColors(courses) {
  const sorted = [...courses].sort((a, b) => (b.tracked ? 1 : 0) - (a.tracked ? 1 : 0) || a.id - b.id);
  return new Map(sorted.map((c, i) => [c.id, PALETTE[i % PALETTE.length]]));
}

// The first start in [from, to) for a block of `len` ms that overlaps nothing in `taken` (sorted by start), on the
// `step` grid; null if there's none.
function firstFit(taken, from, to, len, snap) {
  let t = snap(from);
  for (const [s, e] of taken) {
    if (e <= t) continue;
    if (s >= t + len) break;
    t = snap(e);
  }
  return t + len <= to ? t : null;
}

// "Plan my week", like Motion or Reclaim: each to-do ({ id, due_at, effort } in minutes, in the order to plan them)
// gets the earliest free slot that ends before it's due (overdue ones: the earliest at all), between dayStart and
// dayEnd (hours), at least `buffer` minutes from classes (`busy`, [start, end] pairs), never on top of blocks already
// planned (`blocks`), and with at most `cap` minutes of planned work a day. A block is the to-do's effort, up to
// `maxBlock` minutes (a bigger job gets its first part). Nothing starts before `now` or ends after `until`.
// Returns { planned: [{ id, start, minutes }], unplaced: [id] }.
export function autoPlan({ todos, busy = [], blocks = [], now, until, dayStart = 8, dayEnd = 22, buffer = 10, cap = 240, maxBlock = 120, step = 15 }) {
  const grid = step * MIN;
  const snap = (t) => Math.ceil(t / grid) * grid;
  const taken = [...busy.map(([s, e]) => [s - buffer * MIN, e + buffer * MIN]), ...blocks].sort((a, b) => a[0] - b[0]);
  const load = new Map();
  const addLoad = (s, minutes) => load.set(startOfDay(s), (load.get(startOfDay(s)) || 0) + minutes);
  for (const [s, e] of blocks) addLoad(s, (e - s) / MIN);

  const planned = [], unplaced = [];
  for (const h of todos) {
    const minutes = Math.min(h.effort, maxBlock);
    const len = minutes * MIN;
    const deadline = h.due_at > now ? Math.min(h.due_at, until) : until;
    let start = null;
    for (let day = startOfDay(now); start === null && day < deadline; day = addDays(day, 1)) {
      if ((load.get(day) || 0) + minutes > cap) continue;
      start = firstFit(taken, Math.max(now, atMinutes(day, dayStart * 60)), Math.min(atMinutes(day, dayEnd * 60), deadline), len, snap);
    }
    if (start === null) { unplaced.push(h.id); continue; }
    planned.push({ id: h.id, start, minutes });
    taken.push([start, start + len]);
    taken.sort((a, b) => a[0] - b[0]);
    addLoad(start, minutes);
  }
  return { planned, unplaced };
}
