// Countdowns and count-ups: what's left until a date (or how long since one), shown the way you pick. Pure
// functions shared by the browser (public/countdowns-ui.js) and the Worker (src/countdowns.js validates with
// cleanCountdown), tested in node. Dates use the local time of wherever they run, so the browser does the maths.

export { COLORS } from './habits.js';
import { COLORS } from './habits.js';

export const UNITS = {
  auto: 'Automatic', days: 'Days', weeks: 'Weeks and days', months: 'Months and days', hours: 'Hours', clock: 'Live clock', workdays: 'Working days',
};
export const REPEATS = { none: 'Never', weekly: 'Every week', monthly: 'Every month', yearly: 'Every year' };
export const AFTER = { up: 'Keep counting up', past: 'Move it to Past' };
export const MAX_COUNTDOWNS = 100;
const TITLE_LEN = 80, NOTE_LEN = 500;
const MIN = 60000, HOUR = 3600000, DAY = 86400000;
const YEAR_MS = 365.25 * DAY;

// ---------------------------------------------------------------- validation

const text = (v, max) => (typeof v === 'string' ? v.trim().replace(/\s+/g, ' ').slice(0, max) : '');
const instant = (v) => Number.isSafeInteger(v) && Math.abs(v) < 200 * YEAR_MS;

// A countdown's fields, checked. `partial` (an edit) only checks what's there. Returns { ok, value } or { ok: false, error }.
export function cleanCountdown(raw, { partial = false } = {}) {
  if (!raw || typeof raw !== 'object') return { ok: false, error: 'Invalid countdown.' };
  const out = {};
  const has = (k) => Object.hasOwn(raw, k) && raw[k] !== undefined;
  if (!partial || has('title')) {
    out.title = text(raw.title, TITLE_LEN);
    if (!out.title) return { ok: false, error: 'Give the countdown a name.' };
  }
  if (!partial || has('at')) {
    if (!instant(raw.at)) return { ok: false, error: 'Pick a date.' };
    out.at = raw.at;
  }
  if (!partial || has('all_day')) out.all_day = raw.all_day === false || raw.all_day === 0 ? 0 : 1;
  for (const [k, list, def] of [['repeat', REPEATS, 'none'], ['units', UNITS, 'auto'], ['after', AFTER, 'up']]) {
    if (!partial || has(k)) {
      if (has(k) && !Object.hasOwn(list, raw[k])) return { ok: false, error: `Invalid ${k}.` };
      out[k] = has(k) ? raw[k] : def;
    }
  }
  if (has('start_at')) {
    if (raw.start_at !== null && !instant(raw.start_at)) return { ok: false, error: 'Invalid start date.' };
    out.start_at = raw.start_at;
  }
  if (!partial || has('color')) out.color = COLORS.includes(raw.color) ? raw.color : 'blue';
  if (has('pinned')) out.pinned = raw.pinned ? 1 : 0;
  if (has('note')) out.note = typeof raw.note === 'string' ? raw.note.trim().slice(0, NOTE_LEN) || null : null;
  for (const k of ['event_id', 'homework_id']) {
    if (has(k)) {
      if (raw[k] !== null && !Number.isSafeInteger(raw[k])) return { ok: false, error: `Invalid ${k}.` };
      out[k] = raw[k];
    }
  }
  return { ok: true, value: out };
}

// ---------------------------------------------------------------- dates

export const dayStart = (ms) => new Date(ms).setHours(0, 0, 0, 0);
const calDays = (a, b) => Math.round((dayStart(a) - dayStart(b)) / DAY); // local days from b to a, DST-proof

// `ms` moved by n months, keeping the day of the month where it exists (31 Jan + 1 month = 28/29 Feb).
export function addMonths(ms, n) {
  const d = new Date(ms);
  const day = d.getDate();
  d.setDate(1);
  d.setMonth(d.getMonth() + n);
  d.setDate(Math.min(day, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()));
  return d.getTime();
}
const step = (c, ms, k) => (c.repeat === 'weekly' ? new Date(ms).setDate(new Date(ms).getDate() + 7 * k)
  : addMonths(ms, k * (c.repeat === 'yearly' ? 12 : 1)));
// When an occurrence is over: the end of its day for an all-day one.
const endOf = (c, ms) => (c.all_day ? new Date(ms).setDate(new Date(ms).getDate() + 1) : ms + MIN);

// The occurrence that counts now: the first one not over yet (a repeating one), and the one before it.
export function occurrence(c, now = Date.now()) {
  if (c.repeat === 'none' || !c.repeat || now < endOf(c, c.at)) return { at: c.at, prev: null };
  const per = c.repeat === 'weekly' ? 7 * DAY : c.repeat === 'monthly' ? 30.44 * DAY : YEAR_MS;
  let k = Math.max(0, Math.floor((now - c.at) / per) - 1);
  while (endOf(c, step(c, c.at, k)) <= now) k++;
  return { at: step(c, c.at, k), prev: k > 0 ? step(c, c.at, k - 1) : c.at };
}

// Weekdays (Mon–Fri) from the day after `from` to `to`, both local days.
export function workdaysBetween(from, to) {
  let n = 0;
  for (let d = new Date(dayStart(from)), i = 0; i < 4000; i++) {
    d.setDate(d.getDate() + 1);
    if (d.getTime() > to) break;
    const w = d.getDay();
    if (w !== 0 && w !== 6) n++;
  }
  return n;
}

// ---------------------------------------------------------------- display

const pad = (n) => String(n).padStart(2, '0');
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;

// What a countdown shows now:
//   { state: 'future' | 'today' | 'up' | 'past', at, big, unit, parts, live, progress }
// `big` and `unit` are the main figure ("12", "days"); `parts` the same in one string ("3 w 2 d"); `live` says it
// changes every second (so the page ticks it); progress (0–1) runs from start_at, or the previous occurrence, to the date.
export function view(c, now = Date.now()) {
  const { at, prev } = occurrence(c, now);
  const future = c.all_day ? calDays(at, now) > 0 : at > now;
  const today = c.all_day && calDays(at, now) === 0;
  const state = today ? 'today' : future ? 'future' : c.after === 'past' ? 'past' : 'up';
  const ms = Math.abs(at - now);
  const days = Math.abs(calDays(at, now)); // calendar days, so a 9:00 exam on the 17th is 20 days away all day today
  const units = c.units || 'auto';
  let big, unit, parts, live = false;

  const clock = () => {
    const s = Math.floor(ms / 1000);
    const d = Math.floor(s / 86400);
    live = true;
    return `${d ? `${d}d ` : ''}${pad(Math.floor((s % 86400) / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
  };
  if (today) { big = 'Today'; unit = ''; parts = 'Today'; }
  else if (units === 'clock' && !c.all_day) { big = clock(); unit = ''; parts = big; }
  else if (units === 'hours' || (units === 'auto' && !c.all_day && ms < 2 * DAY)) {
    if (units === 'auto' && ms < HOUR) { big = clock().replace(/^00:/, ''); unit = ''; parts = big; }
    else {
      const h = Math.floor(ms / HOUR), m = Math.floor((ms % HOUR) / MIN);
      big = String(h); unit = h === 1 ? 'hour' : 'hours';
      parts = units === 'hours' ? plural(h, 'hour') : `${h} h ${pad(m)} min`;
      live = units === 'auto';
    }
  } else if (units === 'weeks') {
    const w = Math.floor(days / 7), d = days % 7;
    big = String(w); unit = w === 1 ? 'week' : 'weeks';
    parts = w ? `${w} w${d ? ` ${d} d` : ''}` : plural(d, 'day');
  } else if (units === 'months') {
    const [a, b] = future ? [now, at] : [at, now];
    let m = 0;
    while (addMonths(dayStart(a), m + 1) <= dayStart(b)) m++;
    const d = calDays(b, addMonths(dayStart(a), m));
    big = String(m); unit = m === 1 ? 'month' : 'months';
    parts = m ? `${m} mo${d ? ` ${d} d` : ''}` : plural(d, 'day');
  } else if (units === 'workdays') {
    const n = future ? workdaysBetween(now, at) : workdaysBetween(at, now);
    big = String(n); unit = n === 1 ? 'working day' : 'working days'; parts = `${big} ${unit}`;
  } else {
    big = String(days); unit = days === 1 ? 'day' : 'days'; parts = `${big} ${unit}`;
  }

  const from = c.repeat !== 'none' && prev != null ? prev : c.start_at;
  const progress = from != null && at > from ? Math.min(1, Math.max(0, (now - from) / (at - from))) : null;
  return { state, at, big, unit, parts, live, progress };
}

// Coming up first (soonest first), then the ones counting up (most recent first), then Past.
export function sortCountdowns(list, now = Date.now()) {
  const order = { today: 0, future: 1, up: 2, past: 3 };
  return list.map((c) => ({ c, v: view(c, now) }))
    .sort((x, y) => order[x.v.state] - order[y.v.state] || (x.v.state === 'future' || x.v.state === 'today' ? x.v.at - y.v.at : y.v.at - x.v.at));
}

// ---------------------------------------------------------------- quick add
// "Algo exam 12 dec 9:00", "Mum's birthday 3 march", "Quit coffee since 1 sep", "Trip in 3 weeks",
// "Rent 1st monthly". Day/month order is European for numeric dates (12/03 = 12 March). Returns null without a date.

export const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
export const MONTH_RE = '(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)';
const WEEKDAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];
export const WD_RE = '(mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)';

export function parseCountdown(input, now = Date.now()) {
  let t = ` ${String(input || '').trim()} `;
  const take = (re) => { const m = t.match(re); if (m) t = t.replace(m[0], ' '); return m; };
  const out = { repeat: 'none', after: 'up', all_day: 1 };
  const since = Boolean(take(/\s(?:since|from)\s(?=\S)/i));

  if (take(/\s(?:every\s+year|yearly|annually|each\s+year)\b/i)) out.repeat = 'yearly';
  else if (take(/\s(?:every\s+month|monthly|each\s+month)\b/i)) out.repeat = 'monthly';
  else if (take(/\s(?:every\s+week|weekly|each\s+week)\b/i)) out.repeat = 'weekly';
  else if (/\b(birthday|bday|anniversary)\b/i.test(t)) out.repeat = 'yearly';

  // Time: "9:00", "9.30pm", "9am", "at 18", "18h".
  let hour = null, minute = 0;
  let m = take(/\s(?:at\s+)?(\d{1,2})[:.](\d{2})\s*(am|pm)?(?=\s)/i) || take(/\s(?:at\s+)?(\d{1,2})\s*(am|pm)(?=\s)/i)
    || take(/\s(?:at\s+)?(\d{1,2})h(\d{2})?(?=\s)/i) || take(/\sat\s+(\d{1,2})(?=\s)/i);
  if (m) {
    const ampm = m.slice(1).find((x) => /^(am|pm)$/i.test(x || ''));
    const min = m.slice(2).find((x) => /^\d{2}$/.test(x || ''));
    hour = Number(m[1]) % (ampm ? 12 : 24) + (ampm?.toLowerCase() === 'pm' ? 12 : 0);
    minute = min ? Number(min) : 0;
    if (hour > 23 || minute > 59) hour = null;
  }

  const base = new Date(now);
  const today0 = dayStart(now);
  let date = null, yearGiven = false;
  if ((m = take(/\s(\d{4})-(\d{2})-(\d{2})(?=\s)/))) { date = new Date(+m[1], +m[2] - 1, +m[3]); yearGiven = true; }
  else if ((m = take(/\s(\d{1,2})[/.](\d{1,2})(?:[/.](\d{2,4}))?\.?(?=\s)/))) {
    const y = m[3] ? (m[3].length === 2 ? 2000 + Number(m[3]) : Number(m[3])) : base.getFullYear();
    date = new Date(y, +m[2] - 1, +m[1]); yearGiven = Boolean(m[3]);
    if (date.getMonth() !== +m[2] - 1) date = null;
  } else if ((m = take(new RegExp(`\\s(?:on\\s+|the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+${MONTH_RE}\\.?(?:,?\\s+(\\d{4}))?(?=\\s)`, 'i'))
    || take(new RegExp(`\\s(?:on\\s+)?${MONTH_RE}\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?(?=\\s)`, 'i')))) {
    const [dd, mon] = /^\d/.test(m[1]) ? [m[1], m[2]] : [m[2], m[1]];
    const mi = MONTHS.indexOf(mon.slice(0, 3).toLowerCase());
    date = new Date(m[3] ? +m[3] : base.getFullYear(), mi, +dd); yearGiven = Boolean(m[3]);
    if (date.getMonth() !== mi) date = null;
  } else if ((m = take(/\sin\s+(\d{1,3}|a|an|one|two|three)\s+(day|week|month|year)s?(?=\s)/i))) {
    const n = { a: 1, an: 1, one: 1, two: 2, three: 3 }[m[1].toLowerCase()] ?? Number(m[1]);
    const unit = m[2].toLowerCase();
    date = new Date(today0);
    if (unit === 'day') date.setDate(date.getDate() + n);
    else if (unit === 'week') date.setDate(date.getDate() + 7 * n);
    else date = new Date(addMonths(today0, unit === 'month' ? n : 12 * n));
    yearGiven = true;
  } else if ((m = take(/\s(today|tonight|tomorrow|tmr|yesterday)(?=\s)/i))) {
    const w = m[1].toLowerCase();
    date = new Date(today0);
    date.setDate(date.getDate() + (w === 'tomorrow' || w === 'tmr' ? 1 : w === 'yesterday' ? -1 : 0));
    if (w === 'tonight' && hour == null) hour = 20;
    yearGiven = true;
  } else if ((m = take(new RegExp(`\\s(?:on\\s+|next\\s+|this\\s+|last\\s+)?${WD_RE}(?=\\s)`, 'i')))) {
    const wd = WEEKDAYS.indexOf(m[1].slice(0, 3).toLowerCase());
    date = new Date(today0);
    let delta = (wd - date.getDay() + 7) % 7 || 7;
    if (since || /last/i.test(m[0])) delta -= 7;
    date.setDate(date.getDate() + delta);
    yearGiven = true;
  }
  // A day of the month on its own, for monthly ones: "rent 1st monthly".
  if (!date && out.repeat === 'monthly' && (m = take(/\s(?:on\s+the\s+|the\s+)?(\d{1,2})(?:st|nd|rd|th)(?=\s)/i))) {
    date = new Date(base.getFullYear(), base.getMonth(), +m[1]);
    if (date.getTime() < today0) date = new Date(addMonths(date.getTime(), 1));
    yearGiven = true;
  }
  if (!date || Number.isNaN(date.getTime())) return null;

  if (hour != null) { date.setHours(hour, minute, 0, 0); out.all_day = 0; }
  // No year: the next time that date comes, unless it's a "since" (the last time it came).
  if (!yearGiven) {
    const end = out.all_day ? new Date(date).setDate(date.getDate() + 1) : date.getTime();
    if (!since && end <= now) date.setFullYear(date.getFullYear() + 1);
    if (since && date.getTime() > now) date.setFullYear(date.getFullYear() - 1);
  }
  out.at = date.getTime();
  out.title = t.replace(/\s+(?:on|at|in|until|till|to|by|the)\s*$/i, ' ').replace(/^\s*(?:until|till|to)\s+/i, ' ')
    .replace(/\s+/g, ' ').replace(/^[\s,–—:-]+|[\s,–—:-]+$/g, '').trim().slice(0, TITLE_LEN);
  out.since = since;
  return out;
}
