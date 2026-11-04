// The day plan: what's on now, what to do next and where it fits. Pure functions, shared by the browser (the Today
// page) and the Worker (GET /api/plan, for scripts and Claude Code). Everything is computed, no AI.
//
// - Sessions are those of tracked courses, minus future ones you plan to skip.
// - To-dos are open homework and catch-ups, ranked by when you must start them at the latest:
//   due time minus the effort they need. Overdue ones come first.
// - Free time is the part of each day inside your working hours (DAY_START to DAY_END unless you set your own, see
//   timeline.js) with no session, in blocks of 30 min or more, and the to-dos are fitted into today's blocks in rank
//   order. Days you're away have none. Commitments ("Thesis 10h/week") are counted in the week's load.
// - A to-do planned on the Calendar (planned_at) takes its block out of the free time, and isn't fitted again.

import { DEFAULT_AVAILABILITY, awayDays, dayWindows, minutesOfDay } from './timeline.js';
import { keyInZone } from './habits.js';

const MIN = 60000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
export const DAY_START = 8;   // hours, local time
export const DAY_END = 22;
const MIN_GAP = 30 * MIN;
const HOMEWORK_EFFORT = 60;   // minutes, when the item doesn't say
const SOON = 7 * DAY;         // to-dos due later than this are only counted

export const effortOf = (h) => h.effort_minutes || (h.kind === 'catchup' ? 90 : HOMEWORK_EFFORT);
// The time block a to-do is planned in, [start, end), or null.
export const plannedBlock = (h) => (h.planned_at == null ? null : [h.planned_at, h.planned_at + (h.planned_minutes || effortOf(h)) * MIN]);

// Midnight (as an instant) of the local day containing `ms`, in time zone `tz`.
function offset(ms, tz) {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: tz, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric',
  }).formatToParts(new Date(ms)).map((x) => [x.type, Number(x.value)]));
  return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - Math.floor(ms / 1000) * 1000;
}
export function localMidnight(ms, tz) {
  const shifted = ms + offset(ms, tz);
  const mid = shifted - (((shifted % DAY) + DAY) % DAY);
  // Two passes, for days that start or end on a DST change.
  let t = mid - offset(mid, tz);
  t = mid - offset(t, tz);
  return t;
}
// The instant `hours` after a local midnight (DST-safe).
const atHour = (midnight, hours, tz) => {
  const naive = midnight + offset(midnight, tz) + hours * HOUR;
  let t = naive - offset(naive, tz);
  t = naive - offset(t, tz);
  return t;
};

// Free blocks of [from, to) around `busy` (sorted [start, end] pairs).
function freeBlocks(from, to, busy) {
  const out = [];
  let t = from;
  for (const [s, e] of busy) {
    if (e <= t) continue;
    if (s >= to) break;
    if (s - t >= MIN_GAP) out.push({ start: t, end: s });
    t = Math.max(t, e);
  }
  if (to - t >= MIN_GAP) out.push({ start: t, end: to });
  return out;
}

// overdue | urgent (must start within a day) | due (within 3 days) | soon.
function urgency(h, now) {
  if (h.due_at < now) return 'overdue';
  if (h.latestStart - now < DAY) return 'urgent';
  return h.due_at - now < 3 * DAY ? 'due' : 'soon';
}

export function buildPlan({ courses, events, homework, now = Date.now(), tz = Intl.DateTimeFormat().resolvedOptions().timeZone, availability = null, commitments = [] }) {
  const av = availability || DEFAULT_AVAILABILITY;
  const away = awayDays(commitments);
  const keyOf = (midnight) => keyInZone(midnight + 2 * HOUR, tz);
  // Free blocks of the local day starting at `midnight`, inside its working hours and not before `from`.
  const freeOn = (midnight, busy, from = 0) => dayWindows(keyOf(midnight), { availability: av, away })
    .flatMap(([a, b]) => freeBlocks(Math.max(from, atHour(midnight, a / 60, tz)), atHour(midnight, b / 60, tz), busy));
  const byId = Object.fromEntries(courses.map((c) => [c.id, c]));
  const sessions = events
    .filter((e) => byId[e.course_id]?.tracked && !(e.status === 'absent' && e.start_at > now) && e.status !== 'excused')
    .sort((a, b) => a.start_at - b.start_at);
  // All-day entries (holidays, term dates) aren't classes: they never block time.
  const timed = sessions.filter((e) => e.end_at - e.start_at < 20 * HOUR);

  const today = localMidnight(now, tz);
  const tomorrow = localMidnight(today + DAY + 2 * HOUR, tz);
  const current = timed.filter((e) => e.start_at <= now && e.end_at > now);
  const upcoming = timed.filter((e) => e.start_at > now);
  const todaySessions = timed.filter((e) => e.end_at > today && e.start_at < tomorrow);
  const nextByCourse = new Map();
  for (const e of upcoming) if (!nextByCourse.has(e.course_id)) nextByCourse.set(e.course_id, e);

  // To-dos, by the latest time you can start them and still finish on time.
  const open = homework.filter((h) => !h.done).map((h) => {
    const effort = effortOf(h);
    const item = { ...h, effort, latestStart: h.due_at - effort * MIN, course: byId[h.course_id]?.name ?? null };
    // A catch-up due at the course's next session: say so rather than give a time.
    const next = nextByCourse.get(h.course_id);
    return { ...item, urgency: urgency(item, now), beforeSession: next && Math.abs(next.start_at - h.due_at) < MIN ? next.id : null };
  });
  open.sort((a, b) => Number(b.due_at < now) - Number(a.due_at < now) || a.latestStart - b.latestStart);
  const todo = open.filter((h) => h.due_at < now + SOON);
  const later = open.length - todo.length;

  // Today's free time, with to-dos fitted in rank order. A block too short for the next to-do gets the first
  // one that fits; a long to-do that fits nowhere is started in the longest block.
  const blocks = open.map((h) => [h, plannedBlock(h)]).filter(([, b]) => b && b[1] > today && b[0] < today + 8 * DAY);
  const busy = [...timed.map((e) => [e.start_at, e.end_at]), ...blocks.map(([, b]) => b)].sort((a, b) => a[0] - b[0]);
  const planned = blocks.filter(([, [s, e]]) => e > now && s < tomorrow)
    .map(([h, [start, end]]) => ({ id: h.id, title: h.title, start, end, minutes: Math.round((end - start) / MIN) }));
  const gaps = freeOn(today, busy, now).map((g) => ({ ...g, minutes: Math.round((g.end - g.start) / MIN), fits: [] }));
  const plannedToday = new Set(planned.map((p) => p.id));
  const pending = todo.filter((h) => !plannedToday.has(h.id));
  for (const g of gaps) {
    let left = g.minutes;
    for (let i = 0; i < pending.length && left >= 30;) {
      if (pending[i].effort <= left) {
        g.fits.push({ id: pending[i].id, title: pending[i].title, minutes: pending[i].effort });
        left -= pending[i].effort;
        pending.splice(i, 1);
      } else i++;
    }
    g.left = left;
  }
  if (pending.length) {
    const roomy = gaps.filter((g) => g.left >= 45).sort((a, b) => b.left - a.left)[0];
    if (roomy) {
      roomy.fits.push({ id: pending[0].id, title: pending[0].title, minutes: roomy.left, partial: true });
      roomy.left = 0;
    }
  }

  // The next seven days: hours in class, hours of work due, free hours between classes, and clashes
  // (two courses at the same time, e.g. an EPITA and a Tampere session).
  const weekEnd = today + 7 * DAY;
  const week = { classHours: 0, workHours: 0, commitHours: 0, freeHours: 0, awayDays: 0, clashes: [] };
  for (let d = 0; d < 7; d++) {
    const mid = localMidnight(today + d * DAY + 2 * HOUR, tz);
    const key = keyOf(mid);
    if (away.has(key)) week.awayDays++;
    week.freeHours += freeOn(mid, busy, d === 0 ? now : 0).reduce((n, g) => n + (g.end - g.start) / HOUR, 0);
    week.commitHours += commitments.reduce((n, c) => n + minutesOfDay(c, key, av, away), 0) / 60;
  }
  const inWeek = upcoming.filter((e) => e.start_at < weekEnd);
  week.classHours = inWeek.reduce((n, e) => n + (e.end_at - e.start_at) / HOUR, 0);
  week.workHours = todo.reduce((n, h) => n + h.effort / 60, 0);
  for (let i = 0; i < inWeek.length; i++) {
    for (let j = i + 1; j < inWeek.length && inWeek[j].start_at < inWeek[i].end_at; j++) {
      if (inWeek[j].course_id !== inWeek[i].course_id) week.clashes.push([inWeek[i].id, inWeek[j].id]);
    }
  }
  const asked = week.workHours + week.commitHours;
  week.load = week.freeHours ? asked / week.freeHours : asked ? Infinity : 0;

  return {
    now, tz, today, away: away.has(keyOf(today)),
    current: current.map((e) => e.id),
    next: upcoming[0]?.id ?? null,
    todaySessions: todaySessions.map((e) => e.id),
    tomorrowFirst: timed.find((e) => e.start_at >= tomorrow && e.start_at < tomorrow + DAY)?.id ?? null,
    gaps, planned, todo, later, week,
  };
}
