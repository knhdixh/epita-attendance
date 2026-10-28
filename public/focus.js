// Focus (Pomodoro) timer and stats. Pure functions shared by the browser (public/focus-ui.js) and the Worker
// (src/focus.js cleans settings with them), tested in node.
//
// The timer is a small state object kept in localStorage, so a reload or a second tab carries on with the same
// timer. It stores instants, not a countdown, so a throttled background tab still ends the phase on time:
//   { phase: 'idle' | 'focus' | 'short' | 'long', next, run, startedAt, endsAt, pausedAt, pausedMs, done, target }
// `next` is the phase Start begins when idle (`custom` minutes instead of the setting, if you changed it), `done` counts focus sessions since the last long break, `run` is a
// random id the server uses to record a session once (several tabs may finish the same one), and `target` is what
// the focus is for: { homework_id, course_id, label }, each optional.

import { localMidnight } from './plan.js';

const MIN = 60000;
export const PHASES = { focus: 'Focus', short: 'Short break', long: 'Long break' };
export const DEFAULT_SETTINGS = {
  focus: 25, short: 5, long: 15, longEvery: 4, goal: 8, autoBreaks: true, autoFocus: false, sound: true, notify: true,
  tasks: [], tags: [],   // your own things to work on ("Thesis: literature review") and tags ("reading")
};
const LIMITS = { focus: [1, 180], short: [1, 60], long: [1, 90], longEvery: [2, 12], goal: [1, 24] };
const FLAGS = ['autoBreaks', 'autoFocus', 'sound', 'notify'];
export const MAX_TASKS = 30, MAX_TAGS = 20, TAGS_PER_SESSION = 5;
const TASK_LEN = 80, TAG_LEN = 24;

// Tags are lower case; tasks keep their case. Both are trimmed, and duplicates (ignoring case) dropped.
export const cleanTag = (t) => (typeof t === 'string' ? t.trim().replace(/^#+/, '').replace(/\s+/g, ' ').toLowerCase().slice(0, TAG_LEN) : '');
export const cleanTask = (t) => (typeof t === 'string' ? t.trim().replace(/\s+/g, ' ').slice(0, TASK_LEN) : '');
function uniqueList(list, clean, max) {
  if (!Array.isArray(list)) return [];
  const seen = new Set(), out = [];
  for (const x of list.map(clean)) if (x && !seen.has(x.toLowerCase()) && out.length < max) { seen.add(x.toLowerCase()); out.push(x); }
  return out;
}
export const cleanTags = (list, max = TAGS_PER_SESSION) => uniqueList(list, cleanTag, max);
export const MIN_LOGGED_MS = MIN; // a focus shorter than this isn't recorded
const STALE_MS = 5 * MIN;          // a phase that ended longer ago than this doesn't start the next one by itself

// Settings with every field valid: anything missing or out of range takes its default.
export function cleanSettings(raw) {
  const s = { ...DEFAULT_SETTINGS };
  if (!raw || typeof raw !== 'object') return s;
  for (const [k, [lo, hi]] of Object.entries(LIMITS)) {
    const n = Number(raw[k]);
    if (raw[k] !== null && raw[k] !== '' && Number.isInteger(n) && n >= lo && n <= hi) s[k] = n;
  }
  for (const k of FLAGS) if (typeof raw[k] === 'boolean') s[k] = raw[k];
  s.tasks = uniqueList(raw.tasks, cleanTask, MAX_TASKS);
  s.tags = uniqueList(raw.tags, cleanTag, MAX_TAGS);
  return s;
}

// ---------------------------------------------------------------- timer

export const idle = (done = 0, next = 'focus', target = null) => ({ phase: 'idle', next, done, target });

export function start(t, settings, now, run) {
  const phase = t.phase === 'idle' ? t.next : t.phase;
  const minutes = t.phase === 'idle' && t.custom ? t.custom : settings[phase];
  return {
    phase, next: phase, run, startedAt: now, endsAt: now + minutes * MIN, pausedAt: null, pausedMs: 0,
    done: t.done, target: t.target,
  };
}

// Time adjustments. Idle: the length of the next phase (for that phase only). Running: moves its end, keeping at
// least a minute left and at most MAX_PHASE_MIN in all.
export const MAX_PHASE_MIN = 180;
const clampMin = (m) => Math.min(MAX_PHASE_MIN, Math.max(1, Math.round(m)));
export const nextLength = (t, settings) => (t.phase === 'idle' ? t.custom || settings[t.next] : Math.round(length(t) / MIN));

export function setLength(t, settings, minutes) {
  if (t.phase !== 'idle') return t;
  const m = clampMin(minutes);
  const { custom: _, ...rest } = t;
  return m === settings[t.next] ? rest : { ...rest, custom: m };
}

export function adjust(t, settings, deltaMin, now) {
  if (t.phase === 'idle') return setLength(t, settings, nextLength(t, settings) + deltaMin);
  const floor = (t.pausedAt ?? now) + MIN;
  const ceiling = t.startedAt + t.pausedMs + MAX_PHASE_MIN * MIN;
  return { ...t, endsAt: Math.max(floor, Math.min(ceiling, t.endsAt + deltaMin * MIN)) };
}

// Typed time: "25" is minutes, "12:30" minutes and seconds, "1:05:00" hours too. Null if it isn't a time.
export function parseClock(text) {
  const parts = String(text).trim().split(':');
  if (parts.length > 3 || !parts.every((p) => /^\d{1,3}$/.test(p))) return null;
  const n = parts.map(Number);
  if (n.slice(1).some((x) => x > 59)) return null;
  const [h, m, sec] = n.length === 3 ? n : n.length === 2 ? [0, ...n] : [0, n[0], 0];
  return ((h * 60 + m) * 60 + sec) * 1000;
}

// Running: sets the time left exactly (to the second), within the same limits as adjust. Idle: the length of the
// next phase, in whole minutes.
export function setRemaining(t, settings, ms, now) {
  if (t.phase === 'idle') return setLength(t, settings, ms / MIN);
  const from = t.pausedAt ?? now;
  const ceiling = t.startedAt + t.pausedMs + MAX_PHASE_MIN * MIN;
  return { ...t, endsAt: Math.max(from + MIN, Math.min(ceiling, from + ms)) };
}

export const pause = (t, now) => (t.phase === 'idle' || t.pausedAt ? t : { ...t, pausedAt: now });

export function resume(t, now) {
  if (t.phase === 'idle' || !t.pausedAt) return t;
  const gap = now - t.pausedAt;
  return { ...t, endsAt: t.endsAt + gap, pausedMs: t.pausedMs + gap, pausedAt: null };
}

export const remaining = (t, now) => (t.phase === 'idle' ? 0 : Math.max(0, t.endsAt - (t.pausedAt ?? now)));
export const length = (t) => (t.phase === 'idle' ? 0 : t.endsAt - t.startedAt - t.pausedMs);
export const progress = (t, now) => (t.phase === 'idle' ? 0 : Math.min(1, 1 - remaining(t, now) / length(t)));
export const isDue = (t, now) => t.phase !== 'idle' && !t.pausedAt && now >= t.endsAt;

// What a focus phase records: the time actually focused, without pauses. Null for breaks and very short focus.
function entry(t, now, completed) {
  if (t.phase !== 'focus') return null;
  const end = Math.min(t.pausedAt ?? now, t.endsAt);
  const focusMs = end - t.startedAt - t.pausedMs;
  if (focusMs < MIN_LOGGED_MS) return null;
  return {
    client_id: t.run, started_at: t.startedAt, ended_at: Math.min(now, t.endsAt),
    planned_seconds: Math.round(length(t) / 1000), focus_seconds: Math.round(focusMs / 1000), completed,
    homework_id: t.target?.homework_id ?? null, course_id: t.target?.course_id ?? null, label: t.target?.label ?? null,
    tags: cleanTags(t.target?.tags),
  };
}

// The phase ran out, or Skip was pressed (`early`): what to record, and the timer that follows. Every
// `longEvery`-th focus is followed by a long break, which resets the count.
export function finish(t, settings, now, run, { early = false } = {}) {
  if (t.phase === 'idle') return { log: null, next: t };
  const fresh = now - t.endsAt < STALE_MS;
  if (t.phase === 'focus') {
    const log = entry(t, now, !early);
    const done = t.done + (early ? 0 : 1);
    const after = idle(done, !early && done >= settings.longEvery ? 'long' : 'short', t.target);
    return { log, next: settings.autoBreaks && fresh ? start(after, settings, now, run) : after };
  }
  const after = idle(t.phase === 'long' ? 0 : t.done, 'focus', t.target);
  return { log: null, next: settings.autoFocus && fresh ? start(after, settings, now, run) : after };
}

// Stop: a focus records what was done so far; the next Start begins a fresh focus.
export const stop = (t, now) => ({ log: entry(t, now, false), next: idle(t.done, 'focus', t.target) });

// ---------------------------------------------------------------- stats
// Sessions are { started_at, focus_seconds, completed, course_id, homework_id, label }. Days are local days.

export const dayStart = (ms) => new Date(ms).setHours(0, 0, 0, 0);
export const addDays = (ms, n) => { const d = new Date(ms); d.setDate(d.getDate() + n); return d.getTime(); };
export const dayKey = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`; };
export const weekStart = (ms) => addDays(dayStart(ms), -((new Date(ms).getDay() + 6) % 7)); // Monday

// Minutes and finished pomodoros per local day.
export function dailyTotals(sessions) {
  const map = new Map();
  for (const s of sessions) {
    const k = dayKey(s.started_at);
    const d = map.get(k) || { minutes: 0, count: 0 };
    d.minutes += s.focus_seconds / 60;
    d.count += s.completed ? 1 : 0;
    map.set(k, d);
  }
  return map;
}

// Minutes focused per homework item.
export function minutesByHomework(sessions) {
  const map = new Map();
  for (const s of sessions) if (s.homework_id != null) map.set(s.homework_id, (map.get(s.homework_id) || 0) + s.focus_seconds / 60);
  return map;
}

// Everything the Focus page shows. `range` is the number of days (ending today) the breakdowns cover.
export function focusStats(sessions, { now, range = 7 } = {}) {
  const totals = dailyTotals(sessions);
  const day = (ms) => totals.get(dayKey(ms)) || { minutes: 0, count: 0 };
  const today0 = dayStart(now);

  // A streak is consecutive days with some focus; today only breaks it once it's over.
  let streak = 0;
  for (let d = day(today0).minutes ? today0 : addDays(today0, -1); day(d).minutes > 0; d = addDays(d, -1)) streak++;

  const mon = weekStart(now);
  const week = Array.from({ length: 7 }, (_, i) => { const ms = addDays(mon, i); return { ms, ...day(ms), future: ms > today0 }; });
  const elapsed = week.filter((d) => !d.future).length;
  const sum = (from, n) => Array.from({ length: n }, (_, i) => day(addDays(from, i)).minutes).reduce((a, b) => a + b, 0);

  const from = addDays(today0, -(range - 1));
  const inRange = sessions.filter((s) => s.started_at >= from);
  const courses = new Map(), homework = new Map(), tags = new Map(), hours = new Array(24).fill(0);
  for (const s of inRange) {
    const m = s.focus_seconds / 60;
    courses.set(s.course_id ?? null, (courses.get(s.course_id ?? null) || 0) + m);
    for (const tag of s.tags || []) tags.set(tag, (tags.get(tag) || 0) + m);
    if (s.homework_id != null || s.label) {
      const key = s.homework_id ?? `label:${s.label}`;
      const h = homework.get(key) || { homework_id: s.homework_id ?? null, label: s.label, course_id: s.course_id ?? null, minutes: 0, count: 0 };
      h.minutes += m; h.count += s.completed ? 1 : 0;
      homework.set(key, h);
    }
    hours[new Date(s.started_at).getHours()] += m;
  }
  const rangeMinutes = inRange.reduce((a, s) => a + s.focus_seconds / 60, 0);
  const activeDays = new Set(inRange.map((s) => dayKey(s.started_at))).size;
  const peak = hours.some((m) => m > 0) ? hours.indexOf(Math.max(...hours)) : null;
  let best = null;
  for (const [k, d] of totals) if (!best || d.minutes > best.minutes) best = { key: k, ...d };

  return {
    today: day(today0), streak, week,
    thisWeek: sum(mon, elapsed), lastWeek: sum(addDays(mon, -7), elapsed), // last week up to the same weekday
    range: {
      days: range, minutes: rangeMinutes, count: inRange.filter((s) => s.completed).length, activeDays,
      perActiveDay: activeDays ? rangeMinutes / activeDays : 0, peakHour: peak, hours,
      courses: [...courses].map(([course_id, minutes]) => ({ course_id, minutes })).sort((a, b) => b.minutes - a.minutes),
      homework: [...homework.values()].sort((a, b) => b.minutes - a.minutes),
      tags: [...tags].map(([tag, minutes]) => ({ tag, minutes })).sort((a, b) => b.minutes - a.minutes),
    },
    best, day,
  };
}

// A compact summary for GET /api/plan (scripts, Claude Code). The Worker runs in UTC, so days are those of `tz`.
export function focusSummary(sessions, { now, tz }) {
  const fmt = new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' });
  const key = (ms) => fmt.format(new Date(ms));
  const byDay = new Map();
  for (const s of sessions) {
    const d = byDay.get(key(s.started_at)) || { minutes: 0, count: 0 };
    d.minutes += s.focus_seconds / 60;
    d.count += s.completed ? 1 : 0;
    byDay.set(key(s.started_at), d);
  }
  // Local days going back from today, stepping back from today's local noon so a 23 or 25 h day never skips one.
  const noon = localMidnight(now, tz) + 12 * 3600000;
  const days = (n) => Array.from({ length: n }, (_, i) => key(noon - i * 86400000));
  const today = byDay.get(key(now)) || { minutes: 0, count: 0 };
  let streak = 0;
  for (const k of days(400).slice(today.minutes ? 0 : 1)) { if (!byDay.get(k)?.minutes) break; streak++; }
  const week = new Set(days(7));
  const last7 = sessions.filter((s) => week.has(key(s.started_at)));
  const courses = new Map();
  const tags = new Map();
  for (const s of last7) {
    courses.set(s.course_id ?? null, (courses.get(s.course_id ?? null) || 0) + s.focus_seconds / 60);
    for (const tag of s.tags || []) tags.set(tag, (tags.get(tag) || 0) + s.focus_seconds / 60);
  }
  const round = (m) => Math.round(m);
  return {
    today: { minutes: round(today.minutes), pomodoros: today.count },
    streak,
    last7: {
      minutes: round(last7.reduce((a, s) => a + s.focus_seconds / 60, 0)),
      pomodoros: last7.filter((s) => s.completed).length,
      byDay: days(7).reverse().map((date) => ({ date, minutes: round(byDay.get(date)?.minutes || 0) })),
      byCourse: [...courses].map(([course_id, m]) => ({ course_id, minutes: round(m) })).sort((a, b) => b.minutes - a.minutes),
      byTag: [...tags].map(([tag, m]) => ({ tag, minutes: round(m) })).sort((a, b) => b.minutes - a.minutes),
    },
  };
}
