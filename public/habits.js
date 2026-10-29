// Habits: schedules, streaks and stats. Pure functions shared by the browser (public/habits-ui.js) and the Worker
// (src/habits.js validates with them, GET /api/plan summarises with them), tested in node.
//
// Days are local dates as 'YYYY-MM-DD' keys. All the date maths below works on the keys themselves (in UTC), so
// it gives the same answer in the browser and in the Worker, whatever their time zones: the browser decides which
// day "today" is, and the rest follows from that.

export const COLORS = ['blue', 'green', 'teal', 'purple', 'pink', 'red', 'orange', 'yellow', 'gray'];
export const MAX_HABITS = 50;
const NAME_LEN = 60, UNIT_LEN = 20, ICON_LEN = 8, MAX_TARGET = 99;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
const DAY = 86400000;
export const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

// ---------------------------------------------------------------- day keys

const pad = (n) => String(n).padStart(2, '0');
export const localKey = (ms = Date.now()) => { const d = new Date(ms); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; };
export const keyMs = (key) => Date.UTC(+key.slice(0, 4), +key.slice(5, 7) - 1, +key.slice(8, 10));
const fromMs = (ms) => new Date(ms).toISOString().slice(0, 10);
export const addDays = (key, n) => fromMs(keyMs(key) + n * DAY);
export const diffDays = (a, b) => Math.round((keyMs(a) - keyMs(b)) / DAY);   // a − b
export const dow = (key) => ((new Date(keyMs(key)).getUTCDay() + 6) % 7) + 1;  // 1 = Monday … 7 = Sunday
export const weekOf = (key) => addDays(key, 1 - dow(key));                   // its Monday
export const monthOf = (key) => key.slice(0, 7);
export const isKey = (k) => typeof k === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(k) && fromMs(keyMs(k)) === k;
// A local day in a time zone (the Worker runs in UTC).
export const keyInZone = (ms, tz) => new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(ms));

// ---------------------------------------------------------------- validation

export function cleanSchedule(s) {
  if (!s || typeof s !== 'object') return null;
  const n = Number(s.n);
  if (s.type === 'daily') return { type: 'daily' };
  if (s.type === 'days' && Array.isArray(s.days)) {
    const days = [...new Set(s.days.map(Number))].filter((d) => Number.isInteger(d) && d >= 1 && d <= 7).sort();
    if (days.length === 7) return { type: 'daily' };
    return days.length ? { type: 'days', days } : null;
  }
  if (s.type === 'weekly' && Number.isInteger(n) && n >= 1 && n <= 7) return n === 7 ? { type: 'daily' } : { type: 'weekly', n };
  if (s.type === 'monthly' && Number.isInteger(n) && n >= 1 && n <= 31) return { type: 'monthly', n };
  if (s.type === 'interval' && Number.isInteger(n) && n >= 1 && n <= 365) return n === 1 ? { type: 'daily' } : { type: 'interval', n };
  return null;
}

const text = (v, max) => (typeof v === 'string' ? v.trim().replace(/\s+/g, ' ').slice(0, max) : '');

// A habit's fields, checked. `partial` (an edit) only checks what's there. Returns { ok, value } or { ok: false, error }.
export function cleanHabit(raw, { partial = false } = {}) {
  if (!raw || typeof raw !== 'object') return { ok: false, error: 'Invalid habit.' };
  const out = {};
  const has = (k) => Object.hasOwn(raw, k) && raw[k] !== undefined;
  if (!partial || has('name')) {
    out.name = text(raw.name, NAME_LEN);
    if (!out.name) return { ok: false, error: 'Give the habit a name.' };
  }
  if (has('icon')) out.icon = [...text(raw.icon, 32)].slice(0, ICON_LEN).join('') || null;
  if (!partial || has('color')) out.color = COLORS.includes(raw.color) ? raw.color : 'blue';
  if (!partial || has('schedule')) {
    out.schedule = has('schedule') ? cleanSchedule(raw.schedule) : { type: 'daily' };
    if (!out.schedule) return { ok: false, error: 'Invalid schedule.' };
  }
  if (!partial || has('target')) {
    const t = has('target') ? Number(raw.target) : 1;
    if (!Number.isInteger(t) || t < 1 || t > MAX_TARGET) return { ok: false, error: `The daily target is a number from 1 to ${MAX_TARGET}.` };
    out.target = t;
  }
  if (has('unit')) out.unit = text(raw.unit, UNIT_LEN) || null;
  if (has('archived')) out.archived = raw.archived ? 1 : 0;
  if (has('remind_at')) {
    out.remind_at = raw.remind_at || null;
    if (out.remind_at !== null && !TIME_RE.test(out.remind_at)) return { ok: false, error: 'Invalid reminder time.' };
  }
  return { ok: true, value: out };
}

// ---------------------------------------------------------------- reminders

const minutesInZone = (ms, tz) => {
  const [h, m] = new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(ms)).split(':');
  return Number(h) * 60 + Number(m);
};

// The local day a habit's reminder is for, if its time in `tz` passed less than `window` ms before `now`; else null.
// Whether the habit is due that day and not done yet is up to habitStats, once its check-ins are loaded.
export function reminderDay(habit, now, tz, window = 5 * 60000) {
  if (!habit.remind_at) return null;
  const late = minutesInZone(now, tz) - (Number(habit.remind_at.slice(0, 2)) * 60 + Number(habit.remind_at.slice(3)));
  return late >= 0 && late * 60000 < window ? keyInZone(now, tz) : null;
}

// ---------------------------------------------------------------- schedules

export function scheduleLabel(s) {
  switch (s?.type) {
    case 'days': {
      const k = s.days.join('');
      if (k === '12345') return 'Weekdays';
      if (k === '67') return 'Weekends';
      return s.days.map((d) => DOW[d - 1]).join(', ');
    }
    case 'weekly': return s.n === 1 ? 'Once a week' : `${s.n}× a week`;
    case 'monthly': return s.n === 1 ? 'Once a month' : `${s.n}× a month`;
    case 'interval': return s.n === 2 ? 'Every other day' : `Every ${s.n} days`;
    default: return 'Every day';
  }
}

const period = (s) => (s.type === 'weekly' ? weekOf : s.type === 'monthly' ? monthOf : null);
const quota = (s) => (s.type === 'weekly' || s.type === 'monthly' ? s.n : 0);
// The first day of the period after `p` (a Monday key for weeks, 'YYYY-MM' for months), going backwards.
const prevPeriod = (s, p) => (s.type === 'weekly' ? addDays(p, -7) : monthOf(addDays(`${p}-01`, -1)));
const nextPeriod = (s, p) => (s.type === 'weekly' ? addDays(p, 7) : monthOf(addDays(`${p}-01`, 32)));

// ---------------------------------------------------------------- stats
// `counts` maps day keys to check-ins (a Map); a day is done when it reaches the habit's target. `today` is the
// user's local day; `start` the first day the habit existed (its creation, or an earlier backfilled check-in).

export function habitStats(habit, counts, { today, start = today } = {}) {
  const s = habit.schedule, target = habit.target || 1;
  const n = (d) => counts.get(d) || 0;
  const done = (d) => n(d) >= target;
  const doneDays = [...counts.keys()].filter((d) => d <= today && done(d)).sort();
  if (doneDays.length && doneDays[0] < start) start = doneDays[0];
  const todayDone = done(today);

  let streak = 0, best = 0, unit = 'day', due = true, progress = null;
  if (s.type === 'daily' || s.type === 'days') {
    const isDue = (d) => s.type === 'daily' || s.days.includes(dow(d));
    // Today only breaks the streak once it's over.
    for (let d = todayDone ? today : addDays(today, -1); d >= start; d = addDays(d, -1)) {
      if (!isDue(d)) continue;
      if (!done(d)) break;
      streak++;
    }
    let run = 0;
    for (let d = start; d <= today; d = addDays(d, 1)) {
      if (!isDue(d)) continue;
      if (done(d)) best = Math.max(best, ++run);
      else if (d !== today) run = 0;
    }
    due = isDue(today);
  } else if (s.type === 'weekly' || s.type === 'monthly') {
    unit = s.type === 'weekly' ? 'week' : 'month';
    const of = period(s);
    const per = new Map();
    for (const d of doneDays) per.set(of(d), (per.get(of(d)) || 0) + 1);
    const met = (p) => (per.get(p) || 0) >= s.n;
    const cur = of(today), first = of(start);
    for (let p = met(cur) ? cur : prevPeriod(s, cur); p >= first && met(p); p = prevPeriod(s, p)) streak++;
    let run = 0;
    for (let p = first; p <= cur; p = nextPeriod(s, p)) {
      if (met(p)) best = Math.max(best, ++run);
      else if (p !== cur) run = 0;
    }
    progress = { done: per.get(cur) || 0, of: s.n };
    due = todayDone || progress.done < s.n;
  } else if (s.type === 'interval') {
    unit = 'time';
    const last = doneDays[doneDays.length - 1];
    if (last && diffDays(today, last) <= s.n) {
      streak = 1;
      for (let i = doneDays.length - 1; i > 0 && diffDays(doneDays[i], doneDays[i - 1]) <= s.n; i--) streak++;
    }
    let run = 0;
    for (let i = 0; i < doneDays.length; i++) {
      run = i && diffDays(doneDays[i], doneDays[i - 1]) <= s.n ? run + 1 : 1;
      best = Math.max(best, run);
    }
    due = todayDone || !last || diffDays(today, last) >= s.n;
    progress = last && !todayDone ? { since: diffDays(today, last) } : null;
  }

  // How often it was done as planned over the last 30 days (fewer for a newer habit). Today counts once done.
  const from = start > addDays(today, -29) ? start : addDays(today, -29);
  const span = diffDays(today, from) + (todayDone ? 1 : 0);
  const inWindow = doneDays.filter((d) => d >= from).length;
  let rate = null;
  if (span > 0) {
    if (s.type === 'daily' || s.type === 'days') {
      let planned = 0, hit = 0;
      for (let d = from; d < today || (d === today && todayDone); d = addDays(d, 1)) {
        if (s.type === 'daily' || s.days.includes(dow(d))) { planned++; if (done(d)) hit++; }
      }
      rate = planned ? hit / planned : null;
    } else {
      const expected = s.type === 'interval' ? span / s.n : (span * quota(s)) / (s.type === 'weekly' ? 7 : 30.44);
      rate = Math.min(1, inWindow / Math.max(expected, 1));
    }
  }

  const weekdays = new Array(7).fill(0);
  for (const d of doneDays) weekdays[dow(d) - 1]++;
  return {
    today: n(today), todayDone, due, streak, best: Math.max(best, streak), unit, progress, rate,
    total: doneDays.length, checkins: [...counts.entries()].reduce((a, [d, c]) => a + (d <= today ? c : 0), 0),
    weekdays, start,
  };
}

// Done days per month, for the last `months` months ending with today's.
export function monthTotals(habit, counts, today, months = 12) {
  const per = new Map();
  for (const [d, c] of counts) if (c >= (habit.target || 1)) per.set(monthOf(d), (per.get(monthOf(d)) || 0) + 1);
  const out = [];
  let m = monthOf(today);
  for (let i = 0; i < months; i++) { out.unshift({ month: m, days: per.get(m) || 0 }); m = monthOf(addDays(`${m}-01`, -1)); }
  return out;
}

// "3 days", "2 weeks", "1 time"
export const streakLabel = (n, unit) => `${n} ${unit}${n === 1 ? '' : 's'}`;

// ---------------------------------------------------------------- quick add
// "Gym mon wed fri", "Read 20 pages 3x a week", "💧 Water 8 glasses a day", "Stretch every other day". Returns the
// habit it describes and the bits it understood (shown as chips while typing).

const DAY_NAMES = [['mon', 'monday'], ['tue', 'tues', 'tuesday'], ['wed', 'weds', 'wednesday'], ['thu', 'thur', 'thurs', 'thursday'],
  ['fri', 'friday'], ['sat', 'saturday'], ['sun', 'sunday']];
const dayIndex = (w) => DAY_NAMES.findIndex((names) => names.includes(w.toLowerCase())) + 1;
const NUM_WORDS = { once: 1, twice: 2, thrice: 3, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7 };
const num = (w) => (/^\d+$/.test(w) ? Number(w) : NUM_WORDS[w.toLowerCase()]);
const DAY_RE = DAY_NAMES.flat().sort((a, b) => b.length - a.length).join('|');

export function parseHabit(input) {
  let t = ` ${String(input || '').trim()} `;
  let scheduled = false;
  const out = { schedule: { type: 'daily' }, target: 1, unit: null, icon: null };
  const take = (re, fn) => { const m = t.match(re); if (!m) return false; t = t.replace(m[0], ' '); fn(m); return true; };

  const emoji = t.match(/^\s*(\p{Extended_Pictographic}(?:️|‍\p{Extended_Pictographic}|\p{Emoji_Modifier})*)/u);
  if (emoji) { out.icon = emoji[1]; t = t.replace(emoji[1], ' '); }

  // Times a day: "8x a day", "×8", "8 glasses a day", "8 times daily".
  take(/\s(\d{1,2})\s*(?:x|×|times)\s*(?:a|per|\/|each)?\s*day\b|\s(\d{1,2})\s*(?:x|×|times)\s+daily\b/i, (m) => { out.target = Number(m[1] || m[2]); })
    || take(/\s(\d{1,2})\s+([a-z]{2,20})\s+(?:a|per|each)\s+day\b/i, (m) => { out.target = Number(m[1]); out.unit = m[2].toLowerCase(); })
    || take(/\s[x×](\d{1,2})(?=\s)/i, (m) => { out.target = Number(m[1]); });
  out.target = Math.min(MAX_TARGET, Math.max(1, out.target));

  const set = (schedule) => { out.schedule = schedule; scheduled = true; };
  take(/\s(?:every|each)\s+other\s+day\b/i, () => set({ type: 'interval', n: 2 }))
    || take(/\severy\s+(\d{1,3})\s+days?\b/i, (m) => set(cleanSchedule({ type: 'interval', n: Number(m[1]) })))
    || take(/\s(\d|once|twice|thrice|one|two|three|four|five|six)\s*(?:x|×|times)?\s*(?:a|per|\/|each)\s*week\b|\s(\d)\s*x\s*\/\s*w(?:ee)?k\b|\s(once|twice)\s+weekly\b/i,
      (m) => set(cleanSchedule({ type: 'weekly', n: num(m[1] || m[2] || m[3]) })))
    || take(/\s(\d{1,2}|once|twice|thrice|one|two|three|four|five|six)\s*(?:x|×|times)?\s*(?:a|per|\/|each)\s*month\b|\smonthly\b/i,
      (m) => set(cleanSchedule({ type: 'monthly', n: m[1] ? num(m[1]) : 1 })))
    || take(/\s(?:on\s+|every\s+)?weekdays\b/i, () => set({ type: 'days', days: [1, 2, 3, 4, 5] }))
    || take(/\s(?:on\s+|every\s+)?weekends?\b/i, () => set({ type: 'days', days: [6, 7] }))
    || take(/\sweekly\b|\s(?:once\s+)?(?:a|per|each)\s+week\b/i, () => set({ type: 'weekly', n: 1 }))
    || take(new RegExp(`\\s(?:on\\s+|every\\s+)?((?:${DAY_RE})s?(?:\\s*(?:,|and|&|\\/|\\s)\\s*(?:${DAY_RE})s?)*)(?=\\s)`, 'i'), (m) => {
      const days = m[1].split(/[\s,&/]+|and/i).map((w) => dayIndex(w) || dayIndex(w.replace(/s$/i, ''))).filter(Boolean);
      set(cleanSchedule({ type: 'days', days }) || { type: 'daily' });
    })
    || take(/\s(?:every\s*day|everyday|each\s+day|daily)\b/i, () => set({ type: 'daily' }));

  out.name = t.replace(/\s+/g, ' ').replace(/^[\s,–—-]+|[\s,–—-]+$/g, '').trim().slice(0, NAME_LEN);
  out.chips = [scheduled ? scheduleLabel(out.schedule) : null, out.target > 1 ? `${out.target}${out.unit ? ` ${out.unit}` : '×'} a day` : null].filter(Boolean);
  return out;
}
