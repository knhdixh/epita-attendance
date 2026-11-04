// Timeline: what your weeks hold. Commitments ("Thesis 10h/week until 15 dec", "Away 20-27 dec") and your working hours
// (when you're available, per weekday), set against what the timetable and to-dos already ask of each week. Pure
// functions on day keys ('YYYY-MM-DD'), shared by the browser (the Timeline page, Today, the Calendar) and the Worker
// (validation, GET /api/plan). Weekdays are 1 = Monday … 7 = Sunday, as in habits.js.
import { COLORS, addDays, diffDays, dow, isKey, keyInZone, localKey, weekOf } from './habits.js';
import { dayFromPhrase } from './quickadd.js';
import { effortOf } from './plan.js';

export const KINDS = ['work', 'away'];
export const MAX_COMMITMENTS = 100;
export const MAX_HOURS = 80;            // per week
export const MAX_SPAN_DAYS = 2 * 366;
export const DEFAULT_WEEKS = 8;         // a commitment without an end lasts this long
export const NO_DATE = '9999-12-31';    // undated tasks (src/homework.js)
const DAY = 86400000;
const pad = (n) => String(n).padStart(2, '0');

// ---------------------------------------------------------------- working hours

// Per weekday (index 0 = Monday), the windows you're available, [from, to) in minutes after midnight. The default is
// what the day plan has always used, 8:00 to 22:00 every day, so nothing changes until you set your own.
export const DEFAULT_AVAILABILITY = Array.from({ length: 7 }, () => [[8 * 60, 22 * 60]]);
const MAX_WINDOWS = 4;

export function cleanAvailability(raw) {
  if (!Array.isArray(raw) || raw.length !== 7) return { ok: false, error: 'Working hours need seven days, Monday first.' };
  const days = [];
  for (const [i, day] of raw.entries()) {
    if (!Array.isArray(day) || day.length > MAX_WINDOWS) return { ok: false, error: `Up to ${MAX_WINDOWS} time ranges a day.` };
    const ws = day.map((w) => (Array.isArray(w) ? w.map(Number) : [])).sort((a, b) => a[0] - b[0]);
    for (const [j, w] of ws.entries()) {
      const [from, to] = w;
      if (w.length !== 2 || !Number.isInteger(from) || !Number.isInteger(to) || from < 0 || to > 1440 || from >= to) {
        return { ok: false, error: `${WEEKDAY_NAMES[i]}: a time range must start before it ends.` };
      }
      if (j && from < ws[j - 1][1]) return { ok: false, error: `${WEEKDAY_NAMES[i]}: the time ranges overlap.` };
    }
    days.push(ws);
  }
  return { ok: true, value: days };
}

// The user's working hours: the saved ones, or the default.
export function availabilityOf(user) {
  if (!user?.availability) return DEFAULT_AVAILABILITY;
  try {
    const r = cleanAvailability(typeof user.availability === 'string' ? JSON.parse(user.availability) : user.availability);
    return r.ok ? r.value : DEFAULT_AVAILABILITY;
  } catch { return DEFAULT_AVAILABILITY; }
}
export const isDefaultAvailability = (av) => JSON.stringify(av) === JSON.stringify(DEFAULT_AVAILABILITY);

export const windowsOn = (av, key) => av[dow(key) - 1] || [];
export const minutesOn = (av, key) => windowsOn(av, key).reduce((n, [a, b]) => n + b - a, 0);
export const WEEKDAY_NAMES = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
export const WD_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
export const clockOf = (m) => `${pad(Math.floor(m / 60))}:${pad(m % 60)}`;

// "Mon–Fri 9:00–18:00, Sat 10:00–14:00": same windows on neighbouring days are grouped.
export function availabilityLabel(av) {
  const fmt = (ws) => (ws.length ? ws.map(([a, b]) => `${clockOf(a)}–${clockOf(b)}`).join(', ') : 'off');
  const out = [];
  for (let i = 0; i < 7;) {
    let j = i;
    while (j + 1 < 7 && fmt(av[j + 1]) === fmt(av[i])) j++;
    out.push(`${i === j ? WD_SHORT[i] : `${WD_SHORT[i]}–${WD_SHORT[j]}`} ${fmt(av[i])}`);
    i = j + 1;
  }
  return out.join(' · ');
}

// ---------------------------------------------------------------- commitments

const text = (v, max) => (typeof v === 'string' ? v.trim().replace(/\s+/g, ' ').slice(0, max) : '');
const idOrNull = (v) => (v === null || v === undefined || v === '' ? null : Number.isSafeInteger(Number(v)) && Number(v) > 0 ? Number(v) : NaN);

// A commitment's fields, checked. `partial` (an edit) only checks what's there; `current` is the stored row, so an
// edit that moves one end is checked against the other. Returns { ok, value } or { ok: false, error }.
export function cleanCommitment(raw, { partial = false, current = null } = {}) {
  const r = raw || {};
  const v = {};
  const has = (k) => !partial || k in r;
  if (has('title')) {
    v.title = text(r.title, 120);
    if (!v.title) return { ok: false, error: 'Give it a name.' };
  }
  if (has('kind')) {
    v.kind = r.kind ?? 'work';
    if (!KINDS.includes(v.kind)) return { ok: false, error: 'A commitment is work or time away.' };
  }
  const kind = v.kind ?? current?.kind ?? 'work';
  if (has('hours') || (partial && 'kind' in r)) {
    const h = r.hours === null || r.hours === undefined || r.hours === '' ? null : Number(r.hours);
    if (kind === 'away') v.hours = null;
    else if (!(h > 0 && h <= MAX_HOURS)) return { ok: false, error: `Hours a week: more than 0, up to ${MAX_HOURS}.` };
    else v.hours = Math.round(h * 4) / 4;
  }
  for (const k of ['start_date', 'end_date']) {
    if (has(k)) {
      if (!isKey(r[k])) return { ok: false, error: k === 'start_date' ? 'Invalid start date.' : 'Invalid end date.' };
      v[k] = r[k];
    }
  }
  const start = v.start_date ?? current?.start_date, end = v.end_date ?? current?.end_date;
  if (start && end) {
    if (end < start) return { ok: false, error: 'It has to end on or after the day it starts.' };
    if (diffDays(end, start) > MAX_SPAN_DAYS) return { ok: false, error: 'Two years at most.' };
  }
  if (has('days')) {
    if (r.days === null || r.days === undefined) v.days = null;
    else {
      const days = Array.isArray(r.days) ? [...new Set(r.days.map(Number))].filter((d) => Number.isInteger(d) && d >= 1 && d <= 7).sort() : [];
      if (!days.length) return { ok: false, error: 'Pick at least one day, or none for any day.' };
      v.days = days.length === 7 ? null : days;
    }
  }
  if (has('color')) {
    v.color = r.color ?? (kind === 'away' ? 'gray' : 'blue');
    if (!COLORS.includes(v.color)) return { ok: false, error: 'Invalid colour.' };
  }
  for (const k of ['project_id', 'course_id']) {
    if (has(k)) {
      v[k] = idOrNull(r[k]);
      if (Number.isNaN(v[k])) return { ok: false, error: 'Invalid link.' };
    }
  }
  if (has('note')) {
    v.note = typeof r.note === 'string' && r.note.trim() ? r.note.trim().slice(0, 2000) : null;
  }
  return { ok: true, value: v };
}

// ---------------------------------------------------------------- one sentence → a commitment

const AWAY_WORDS = /^(away|off|holiday|holidays|vacation|trip|travel|travelling|traveling|sick|leave|ooo)$/i;
const WD = { mon: 1, tue: 2, wed: 3, thu: 4, fri: 5, sat: 6, sun: 7 };
const wdOf = (w) => {
  const m = /^(mon|tue|wed|thu|fri|sat|sun)[a-z]*,?$/i.exec(w);
  return m && /^(mon(day)?|tue(s(day)?)?|wed(nesday)?|thu(r(s(day)?)?)?|fri(day)?|sat(urday)?|sun(day)?),?$/i.test(w) ? WD[m[1].toLowerCase()] : null;
};
const FROM = new Set(['from', 'starting', 'start', 'since', 'beginning']);
const UNTIL = new Set(['until', 'till', 'til', 'to', 'through', 'thru', '-', 'by', 'ending', 'due']);
const NUM = '(\\d+(?:[.,]\\d+)?)';
const HOURS = '(?:h|hr|hrs|hour|hours)';
const PER_WEEK = new RegExp(`(?:^|\\s)${NUM}\\s*${HOURS}\\s*(?:\\/\\s*(?:w|wk|week)|(?:a|per|each|every)\\s+week|weekly|pw)(?=$|[\\s,.])`, 'i');
const TOTAL = new RegExp(`(?:^|\\s)${NUM}\\s*${HOURS}(?:\\s+(?:in\\s+)?total)?(?=$|[\\s,.])`, 'i');
const DURATION = /^(\d{1,3}|a|an|one|two|three|four)$/i;
const WORD_NUM = { a: 1, an: 1, one: 1, two: 2, three: 3, four: 4 };

// The longest date phrase (up to four words) at words[i], as { key, len }, or null.
function dateAt(words, i, today) {
  for (let len = Math.min(4, words.length - i); len >= 1; len--) {
    const phrase = words.slice(i, i + len).join(' ').replace(/,$/, '');
    // A weekday alone is a work day, not a date, unless a keyword asked for a date (the caller decides).
    const key = dayFromPhrase(phrase, today);
    if (key) return { key, len };
  }
  return null;
}

// "Thesis 10h/week until 15 dec", "Job 12h a week mon wed from 1 nov to 20 dec", "Essay 20h by 1 dec" (spread over
// the weeks until then), "Revision 6h/week for 3 weeks", "Away 20-27 dec", "Off tomorrow", "Holiday next week".
// Returns { ok, value: { title, kind, hours, start_date, end_date, days, total? } } or { ok: false, error }.
export function parseCommitment(input, { today = localKey() } = {}) {
  let t = ` ${String(input || '').trim()} `;
  let hours = null, total = null;
  let m = PER_WEEK.exec(t);
  if (m) { hours = Number(m[1].replace(',', '.')); t = t.replace(m[0], ' '); }
  else if ((m = TOTAL.exec(t))) { total = Number(m[1].replace(',', '.')); t = t.replace(m[0], ' '); }
  // "20-27 dec" and "20 dec-3 jan": a range is two dates around a dash.
  t = t.replace(/(\d{1,2})\s*[-–]\s*(\d{1,2})(\s+[a-z]{3,9}\.?)(?=$|[\s,])/gi, '$1$3 - $2$3').replace(/\s*[–—]\s*|\s+-\s*|\s*-\s+/g, ' - ');
  const words = t.trim().split(/\s+/).filter(Boolean);
  const away = AWAY_WORDS.test(words[0] || '');
  const kind = away ? 'away' : 'work';
  let start = null, end = null, by = false, span = null;
  const days = new Set(), title = [];
  for (let i = 0; i < words.length; i++) {
    const w = words[i].toLowerCase();
    if (FROM.has(w) || UNTIL.has(w)) {
      const d = dateAt(words, i + 1, today);
      if (d) {
        if (FROM.has(w)) start = d.key;
        else { end = d.key; by = by || w === 'by' || w === 'due'; }
        i += d.len;
        continue;
      }
    }
    if (w === 'for' && DURATION.test(words[i + 1] || '') && /^(day|days|week|weeks|month|months)$/i.test(words[i + 2] || '')) {
      const n = WORD_NUM[words[i + 1].toLowerCase()] ?? Number(words[i + 1]);
      const unit = words[i + 2].toLowerCase();
      span = unit.startsWith('day') ? n : unit.startsWith('week') ? 7 * n : 30 * n;
      i += 2;
      continue;
    }
    if ((w === 'this' || w === 'next') && /^week$/i.test(words[i + 1] || '')) {
      const monday = w === 'this' ? weekOf(today) : addDays(weekOf(today), 7);
      start = start ?? (w === 'this' ? today : monday);
      end = end ?? addDays(monday, 6);
      i += 1;
      continue;
    }
    if (w === 'weekdays') { [1, 2, 3, 4, 5].forEach((d) => days.add(d)); continue; }
    if (w === 'weekends' || w === 'weekend' && kind === 'work') { days.add(6); days.add(7); continue; }
    if ((w === 'on' || w === 'and' || w === 'every') && wdOf(words[i + 1] || '')) continue;
    if (w === 'daily' || (w === 'every' && /^day$/i.test(words[i + 1] || ''))) { if (w === 'every') i++; continue; }
    const wd = wdOf(w);
    if (wd && kind === 'work') { days.add(wd); continue; }
    // A bare date: the start (and, for time away, the end too unless one follows).
    if (i > 0 || away) {
      const d = i > 0 ? dateAt(words, i, today) : null;
      if (d && !(d.len === 1 && /^\d+$/.test(words[i]))) {
        if (!start) start = d.key; else if (!end) end = d.key;
        i += d.len - 1;
        continue;
      }
    }
    title.push(words[i]);
  }
  const name = title.join(' ').replace(/^[-,\s]+|[-,\s]+$/g, '');
  // A date without a year is its next occurrence, but a start date can be in the recent past ("from 1 nov" on the 4th).
  if (start && diffDays(start, today) > 240) {
    const back = `${Number(start.slice(0, 4)) - 1}${start.slice(4)}`;
    if (isKey(back) && diffDays(today, back) <= 120) start = back;
  }
  if (start && end && end < start && isKey(`${Number(end.slice(0, 4)) + 1}${end.slice(4)}`)) end = `${Number(end.slice(0, 4)) + 1}${end.slice(4)}`;
  if (!name) return { ok: false, error: 'Name it first, e.g. “Thesis 10h/week until 15 dec”.' };
  start = start ?? today;
  if (span) end = addDays(start, span - 1);
  if (kind === 'away') {
    end = end ?? start;
  } else {
    if (hours === null && total === null) {
      return { ok: false, error: 'Say how many hours: “10h/week”, or “20h by 1 dec” for a total. Time off starts with “Away”.' };
    }
    if (total !== null && !end) return { ok: false, error: `By when? e.g. “${name} ${total}h by 1 dec”.` };
    end = end ?? addDays(start, DEFAULT_WEEKS * 7 - 1);
  }
  if (end < start) return { ok: false, error: 'It ends before it starts.' };
  if (total !== null) {
    const weeks = Math.max(1, (diffDays(end, start) + 1) / 7);
    hours = Math.max(0.25, Math.round((total / weeks) * 4) / 4);
  }
  if (hours !== null && !(hours > 0 && hours <= MAX_HOURS)) return { ok: false, error: `Up to ${MAX_HOURS} hours a week.` };
  void by;
  const value = {
    title: name[0].toUpperCase() + name.slice(1), kind, hours: kind === 'away' ? null : hours, start_date: start, end_date: end,
    days: kind === 'work' && days.size && days.size < 7 ? [...days].sort() : null,
  };
  if (total !== null) value.total = total;
  return { ok: true, value };
}

// ---------------------------------------------------------------- labels

const fmtKey = (key, withYear) => new Date(`${key}T12:00:00Z`).toLocaleDateString([], { day: 'numeric', month: 'short', timeZone: 'UTC', ...(withYear ? { year: 'numeric' } : {}) });
export function rangeLabel(a, b, today = localKey()) {
  const year = a.slice(0, 4) !== today.slice(0, 4) || b.slice(0, 4) !== today.slice(0, 4);
  if (a === b) return fmtKey(a, year);
  if (a.slice(0, 7) === b.slice(0, 7)) return `${Number(a.slice(8))}–${fmtKey(b, year)}`;
  return `${fmtKey(a, year)} – ${fmtKey(b, year)}`;
}
export const hoursLabel = (h) => `${Math.round(h * 10) / 10} h`;
export const daysLabel = (days) => (days?.length ? (days.join() === '1,2,3,4,5' ? 'weekdays' : days.join() === '6,7' ? 'weekends' : days.map((d) => WD_SHORT[d - 1]).join(', ')) : '');

// "10 h/week · Mon, Wed · 3 Nov – 15 Dec" or "Away · 20–27 Dec · 8 days".
export function commitmentLabel(c, today = localKey()) {
  const range = rangeLabel(c.start_date, c.end_date, today);
  if (c.kind === 'away') {
    const n = diffDays(c.end_date, c.start_date) + 1;
    return `Away · ${range} · ${n} day${n === 1 ? '' : 's'}`;
  }
  return [`${hoursLabel(c.hours)}/week`, daysLabel(c.days), range].filter(Boolean).join(' · ');
}

// ---------------------------------------------------------------- what a week holds

// The days a commitment's hours are spread over: its own weekdays, else every weekday you're available.
function workDays(c, av) {
  if (c.days?.length) return c.days;
  const open = [1, 2, 3, 4, 5, 6, 7].filter((d) => (av[d - 1] || []).length);
  return open.length ? open : [1, 2, 3, 4, 5, 6, 7];
}

// Every day key you're away (time-away commitments).
export function awayDays(commitments) {
  const out = new Set();
  for (const c of commitments) {
    if (c.kind !== 'away') continue;
    for (let k = c.start_date, i = 0; k <= c.end_date && i <= MAX_SPAN_DAYS; k = addDays(k, 1), i++) out.add(k);
  }
  return out;
}

// Minutes of `c` on day `key`: a work commitment's weekly hours, spread evenly over its work days. Nothing on a day
// you're away.
export function minutesOfDay(c, key, av, away = new Set()) {
  if (c.kind !== 'work' || key < c.start_date || key > c.end_date || away.has(key)) return 0;
  const wds = workDays(c, av);
  return wds.includes(dow(key)) ? (c.hours * 60) / wds.length : 0;
}

// The sessions that take your time, as plan.js counts them: tracked courses, not excused, not future ones you'll skip,
// not all-day entries.
export function busySessions(courses, events, now = Date.now()) {
  const byId = Object.fromEntries(courses.map((c) => [c.id, c]));
  return events.filter((e) => byId[e.course_id]?.tracked && e.status !== 'excused' && !(e.status === 'absent' && e.start_at > now)
    && e.end_at - e.start_at < 20 * 3600000);
}

// Homework that a commitment already budgets for (same project, or same course) isn't counted twice.
function coveredBy(commitments, h) {
  return commitments.some((c) => c.kind === 'work' && ((c.project_id && c.project_id === h.project_id) || (c.course_id && c.course_id === h.course_id))
    && h.due_date >= c.start_date && h.due_date <= c.end_date);
}

export const LEVELS = { light: 0.5, ok: 0.85, tight: 1 };
export function levelOf(load) {
  if (load > LEVELS.tight) return 'over';
  if (load > LEVELS.ok) return 'tight';
  if (load > LEVELS.light) return 'ok';
  return 'light';
}

// `count` weeks from the Monday `from`: for each, what you're available (working hours, minus days away), and what's
// asked of you: classes, commitments, and the effort of open to-dos due that week. Minutes throughout.
// tz turns session times into days. Returns [{ start, end, available, classes, commitments: [{ id, minutes }], work,
// due, dueItems, total, free, load, level, away }].
export function weekLoads({ from, count = 8, availability = DEFAULT_AVAILABILITY, commitments = [], courses = [], events = [], homework = [], tz, now = Date.now() }) {
  const monday = weekOf(from);
  const away = awayDays(commitments);
  const sessions = busySessions(courses, events, now);
  const classByDay = new Map();
  for (const e of sessions) {
    const k = tz ? keyInZone(e.start_at, tz) : localKey(e.start_at);
    classByDay.set(k, (classByDay.get(k) || 0) + (e.end_at - e.start_at) / 60000);
  }
  const open = homework.filter((h) => !h.done && h.due_date && h.due_date !== NO_DATE);
  const weeks = [];
  for (let w = 0; w < count; w++) {
    const start = addDays(monday, 7 * w), end = addDays(start, 6);
    let available = 0, classes = 0, awayN = 0;
    const per = new Map();
    for (let d = 0; d < 7; d++) {
      const k = addDays(start, d);
      if (away.has(k)) awayN++;
      else available += minutesOn(availability, k);
      classes += classByDay.get(k) || 0;
      for (const c of commitments) {
        const m = minutesOfDay(c, k, availability, away);
        if (m) per.set(c.id, (per.get(c.id) || 0) + m);
      }
    }
    const dueItems = open.filter((h) => h.due_date >= start && h.due_date <= end && !coveredBy(commitments, h));
    const due = dueItems.reduce((n, h) => n + effortOf(h), 0);
    const work = [...per.values()].reduce((a, b) => a + b, 0);
    const total = classes + work + due;
    const load = available ? total / available : total ? Infinity : 0;
    weeks.push({
      start, end, available: Math.round(available), classes: Math.round(classes), work: Math.round(work), due: Math.round(due),
      commitments: [...per].map(([id, minutes]) => ({ id, minutes: Math.round(minutes) })), dueItems: dueItems.map((h) => h.id),
      total: Math.round(total), free: Math.round(available - total), load, level: levelOf(load), away: awayN,
    });
  }
  return weeks;
}

// For a day plan: the windows of `key` you're available in ([] when away), as [from, to) minutes after midnight.
export function dayWindows(key, { availability = DEFAULT_AVAILABILITY, away = new Set() } = {}) {
  return away.has(key) ? [] : windowsOn(availability, key);
}

// Each tracked course's sessions between two day keys, grouped: first and last day, minutes per week (Monday key),
// and the exams. For the Timeline's course rows.
export function courseSpans({ courses, events, from, to, tz, isExam = () => false }) {
  const out = new Map();
  for (const e of events) {
    const course = courses.find((c) => c.id === e.course_id);
    if (!course?.tracked) continue;
    const k = tz ? keyInZone(e.start_at, tz) : localKey(e.start_at);
    if (k < from || k > to) continue;
    let s = out.get(course.id);
    if (!s) out.set(course.id, (s = { course, first: k, last: k, weekly: new Map(), exams: [], sessions: 0 }));
    if (k < s.first) s.first = k;
    if (k > s.last) s.last = k;
    s.sessions++;
    const wk = weekOf(k);
    s.weekly.set(wk, (s.weekly.get(wk) || 0) + (e.end_at - e.start_at) / 60000);
    if (isExam(e, course)) s.exams.push({ id: e.id, day: k, title: e.summary });
  }
  return [...out.values()].sort((a, b) => a.first.localeCompare(b.first) || a.course.name.localeCompare(b.course.name));
}

export { DAY };
