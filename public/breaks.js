// Break planning: what happens if you skip every session in a date range, which windows are safe,
// and which weeks could be skipped entirely. Pure functions, shared by the browser and the Worker.
import { TAGS, effectiveTag, computeStats } from './stats.js';

export const HOLIDAY_RE = /\b(vacances|holidays?|bank holiday|jour f[ée]ri[ée]|f[ée]ri[ée]|cong[ée]s?)\b/i;
const DAY = 86400000;
const EPS = 1e-9;

export const isDate = (s) => /^\d{4}-\d{2}-\d{2}$/.test(s || '') && !Number.isNaN(Date.parse(s))
  && new Date(Date.parse(s)).toISOString().startsWith(s);
export const addDays = (date, n) => new Date(Date.parse(date + 'T00:00:00Z') + n * DAY).toISOString().slice(0, 10);
export const weekdayOf = (date) => new Date(date + 'T00:00:00Z').getUTCDay(); // 0 = Sunday
export const dayDiff = (a, b) => Math.round((Date.parse(b) - Date.parse(a)) / DAY);

// ms -> "YYYY-MM-DD" in the given time zone.
export function makeDayKey(tz) {
  const f = new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' });
  return (ms) => f.format(new Date(ms));
}

// How much room a course has after the break. Mirrors computeStats' levels, but ignores
// exam/mandatory sessions missed in the past, which a break can't change.
function levelOf(stats) {
  if (stats.remaining < -EPS) return 'fail';
  if (stats.remaining < Math.max(1, stats.maxWeight) - EPS) return 'risk';
  return 'ok';
}

// Everything break planning needs, computed once per request/render.
export function breakModel({ courses, events, threshold, unit, now = Date.now(), tz }) {
  const dayKey = makeDayKey(tz);
  const byId = Object.fromEntries(courses.map((c) => [c.id, c]));
  const tracked = courses.filter((c) => c.tracked);
  const opts = { threshold, unit, now };
  const evByCourse = new Map(tracked.map((c) => [c.id, []]));
  for (const e of events) evByCourse.get(e.course_id)?.push(e);
  const base = new Map(tracked.map((c) => [c.id, computeStats(evByCourse.get(c.id), byId, opts)]));

  // School holidays (untracked "Vacances" etc.), possibly multi-day all-day events.
  const holidays = new Set();
  for (const e of events) {
    const c = byId[e.course_id];
    if (c?.tracked || !HOLIDAY_RE.test(c?.name || '')) continue;
    for (let t = e.start_at; t < Math.max(e.end_at, e.start_at + 1); t += DAY) holidays.add(dayKey(t));
  }

  // Future sessions of tracked courses: the ones a break can affect.
  const candidates = [];
  for (const c of tracked) {
    for (const e of evByCourse.get(c.id)) {
      if (e.start_at <= now) continue;
      const tag = effectiveTag(e, c);
      candidates.push({
        e, course: c, day: dayKey(e.start_at),
        mustAttend: TAGS[tag].mustAttend && e.status !== 'excused',
        skippable: TAGS[tag].counts && e.status !== 'absent' && e.status !== 'excused',
        weight: unit === 'hours' ? (e.end_at - e.start_at) / 3600000 : 1,
      });
    }
  }
  // Last day the calendar covers: beyond it "no classes" only means "no data".
  const lastDay = events.length ? dayKey(Math.max(...events.map((e) => e.start_at))) : dayKey(now);
  // Courses with upcoming sessions but no room for even one absence.
  const noSkip = tracked.filter((c) => base.get(c.id).remaining < 1 - EPS && candidates.some((x) => x.course.id === c.id && x.skippable));
  return { byId, tracked, opts, base, holidays, candidates, evByCourse, unit, dayKey, today: dayKey(now), lastDay, noSkip };
}

function holidayCount(m, from, to) {
  let n = 0;
  for (let d = from; d <= to; d = addDays(d, 1)) if (m.holidays.has(d)) n++;
  return n;
}

// Skip every session from `from` to `to` (inclusive, local dates): the effect on each course.
export function simulateBreak(m, from, to) {
  if (from < m.today) from = m.today;
  const inRange = m.candidates.filter((x) => x.day >= from && x.day <= to);
  const skipped = inRange.filter((x) => x.skippable);
  const conflicts = inRange.filter((x) => x.mustAttend);
  const courses = [...new Set(skipped.map((x) => x.course.id))].map((cid) => {
    const ids = new Set(skipped.filter((x) => x.course.id === cid).map((x) => x.e.id));
    const after = computeStats(m.evByCourse.get(cid).map((e) => (ids.has(e.id) ? { ...e, status: 'absent' } : e)), m.byId, m.opts);
    return { course: m.byId[cid], skipped: ids.size, before: m.base.get(cid), after, level: levelOf(after) };
  }).sort((a, b) => a.after.remaining - b.after.remaining);
  const level = conflicts.length || courses.some((c) => c.level === 'fail') ? 'fail'
    : courses.some((c) => c.level === 'risk') ? 'risk' : 'ok';
  return {
    from, to, days: Math.max(0, dayDiff(from, to) + 1), level,
    sessions: skipped.map((x) => x.e), conflicts: conflicts.map((x) => x.e), courses,
    holidayDays: holidayCount(m, from, to),
  };
}

// Per-day buckets for fast window scanning: skippable weight per course, exam/mandatory count.
function buckets(m, from, n) {
  const days = Array.from({ length: n }, () => ({ w: new Map(), must: 0, sessions: 0 }));
  for (const x of m.candidates) {
    const i = dayDiff(from, x.day);
    if (i < 0 || i >= n) continue;
    if (x.mustAttend) days[i].must++;
    if (x.skippable) {
      days[i].w.set(x.course.id, (days[i].w.get(x.course.id) || 0) + x.weight);
      days[i].sessions++;
    }
  }
  return days;
}

function checkWindow(m, acc) {
  let margin = Infinity;
  for (const [cid, w] of acc) {
    const s = m.base.get(cid);
    const rem = s.remaining - w;
    if (rem < -EPS) return null;
    margin = Math.min(margin, rem / Math.max(1, s.maxWeight)); // in "sessions of slack"
  }
  return margin;
}

// Best windows of exactly `days` consecutive days off between `from` and `to`. A window is safe
// if no course drops below the minimum and it contains no exam/mandatory session. Ranked by
// fewest sessions skipped (so weekends and school holidays are used), then most slack left.
export function findBreakWindows(m, { days, from, to, limit = 5 }) {
  days = Math.max(1, Math.min(60, Math.round(days)));
  from = !from || from < m.today ? m.today : from;
  to = !to || to > m.lastDay ? m.lastDay : to;
  const n = dayDiff(from, to) + 1;
  if (n < days) return { windows: [], longest: null };
  const b = buckets(m, from, n);

  const found = [];
  for (let s = 0; s + days <= n; s++) {
    const acc = new Map();
    let must = 0, sessions = 0;
    for (let d = s; d < s + days; d++) {
      must += b[d].must; sessions += b[d].sessions;
      for (const [cid, w] of b[d].w) acc.set(cid, (acc.get(cid) || 0) + w);
    }
    if (must) continue;
    const margin = checkWindow(m, acc);
    if (margin === null) continue;
    const wFrom = addDays(from, s), wTo = addDays(from, s + days - 1);
    found.push({ from: wFrom, to: wTo, days, sessions, margin, level: margin < 1 - EPS ? 'risk' : 'ok', holidayDays: holidayCount(m, wFrom, wTo) });
  }
  found.sort((a, b) => (a.level === b.level ? 0 : a.level === 'ok' ? -1 : 1)
    || a.sessions - b.sessions || b.holidayDays - a.holidayDays || b.margin - a.margin || a.from.localeCompare(b.from));
  const windows = [];
  for (const w of found) {
    if (windows.length >= limit) break;
    if (windows.some((o) => !(w.to < o.from || w.from > o.to))) continue; // no overlaps
    windows.push(w);
  }
  windows.sort((a, b) => a.from.localeCompare(b.from));
  return { windows, longest: windows.length ? null : longestSafe(m, from, to) };
}

// Longest safe run of days in the range (used when no window of the wanted length exists).
export function longestSafe(m, from, to) {
  const n = dayDiff(from, to) + 1;
  const b = buckets(m, from, n);
  let best = null;
  for (let s = 0; s < n; s++) {
    const acc = new Map();
    let sessions = 0;
    for (let d = s; d < n; d++) {
      if (b[d].must) break;
      for (const [cid, w] of b[d].w) acc.set(cid, (acc.get(cid) || 0) + w);
      sessions += b[d].sessions;
      if (checkWindow(m, acc) === null) break;
      const len = d - s + 1;
      if (!best || len > best.days || (len === best.days && sessions < best.sessions)) {
        best = { from: addDays(from, s), to: addDays(from, d), days: len, sessions };
      }
    }
  }
  return best;
}

// Monday-to-Sunday weeks from the current one: could you skip the whole week?
export function weekOverview(m, weeks = 12) {
  const monday = addDays(m.today, -((weekdayOf(m.today) + 6) % 7));
  return Array.from({ length: weeks }, (_, i) => ({ from: addDays(monday, i * 7) })).filter((w) => w.from <= m.lastDay).map(({ from }) => {
    const to = addDays(from, 6);
    const sim = simulateBreak(m, from < m.today ? m.today : from, to);
    return {
      from, to, level: sim.sessions.length ? sim.level : 'free', sessions: sim.sessions.length,
      holidayDays: sim.holidayDays,
      blockers: [
        ...sim.conflicts.map((e) => `${effectiveTag(e, m.byId[e.course_id])}: ${m.byId[e.course_id].name}`),
        ...sim.courses.filter((c) => c.level === 'fail').map((c) => `below minimum: ${c.course.name}`),
      ],
    };
  });
}

// Calendar of possible start days: for each day from this week's Monday to the end of the
// calendar, the verdict for a break of `days` days starting that day.
export function startDayGrid(m, days) {
  const monday = addDays(m.today, -((weekdayOf(m.today) + 6) % 7));
  const weeks = [];
  for (let start = monday; start <= m.lastDay; start = addDays(start, 7)) {
    weeks.push(Array.from({ length: 7 }, (_, i) => {
      const date = addDays(start, i);
      if (date < m.today) return { date, past: true };
      if (date > m.lastDay) return { date, beyond: true };
      const sim = simulateBreak(m, date, addDays(date, days - 1));
      return {
        date, to: sim.to, holiday: m.holidays.has(date),
        level: sim.sessions.length || sim.conflicts.length ? sim.level : 'free',
        sessions: sim.sessions.length,
        blockers: [
          ...sim.conflicts.map((e) => `${effectiveTag(e, m.byId[e.course_id])}: ${m.byId[e.course_id].name}`),
          ...sim.courses.filter((c) => c.level === 'fail').map((c) => `below minimum: ${c.course.name}`),
        ],
      };
    }));
  }
  return weeks;
}
