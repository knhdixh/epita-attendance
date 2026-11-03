// Todoist-style repeats: "every day", "every other week", "every mon, wed", "every 3rd friday", "every month on the
// 15th", "every last day", "every 3 months starting 1 jan", "every week until 1 jun", "every! 3 days" (counted from
// the day you complete it). Pure functions on 'YYYY-MM-DD' day keys, so a repeat means the same in any time zone and
// across daylight saving changes. The server uses them to move a task on when it's completed; the app has a Kotlin
// port checked against the same cases.
import { addDays, diffDays, dow, isKey, localKey, weekOf } from './habits.js';
import { MONTHS, MONTH_RE, WD_RE } from './countdowns.js';

const DAY_ABBR = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
const MAX_INTERVAL = 999;
const pad = (n) => String(n).padStart(2, '0');
const key = (y, m, d) => `${y}-${pad(m)}-${pad(d)}`;
const daysIn = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate();
const parts = (k) => [+k.slice(0, 4), +k.slice(5, 7), +k.slice(8, 10)];
const monthIdx = (k) => { const [y, m] = parts(k); return y * 12 + m - 1; };
const mod = (a, n) => ((a % n) + n) % n;
const dayIndex = (w) => DAY_ABBR.indexOf(w.slice(0, 3)) + 1;
const ordinal = (n) => `${n}${n % 100 >= 11 && n % 100 <= 13 ? 'th' : ['th', 'st', 'nd', 'rd'][n % 10] ?? 'th'}`;
const ORD_WORDS = { first: 1, second: 2, third: 3, fourth: 4, last: -1 };

// A date in month (y, m) on day d, or its last day when the month is shorter (31 in February).
const clamped = (y, m, d) => key(y, m, Math.min(d, daysIn(y, m)));

// ---------------------------------------------------------------- reading dates

// 'today', 'tomorrow', '2026-06-01', '1 jun', 'jun 1', '1 jun 2026', '1/6', '1/6/2026'. A date without a year is its
// next occurrence on or after `today`. Returns a day key or null.
export function parseDay(text, today = localKey()) {
  const t = String(text).trim().toLowerCase().replace(/\.$/, '');
  const withYear = (y, m, d, explicit) => {
    if (m < 1 || m > 12 || d < 1 || d > 31) return null;
    if (explicit) { const k = key(y, m, d); return isKey(k) ? k : null; }
    // No year: the next time that date comes round, which for 29 Feb can be a few years away.
    for (let yy = y; yy <= y + 8; yy++) { const k = key(yy, m, d); if (isKey(k) && k >= today) return k; }
    return null;
  };
  const year = Number(today.slice(0, 4));
  const mon = (w) => MONTHS.indexOf(w.slice(0, 3)) + 1;
  let m;
  if (t === 'today') return today;
  if (t === 'tomorrow' || t === 'tom') return addDays(today, 1);
  if ((m = t.match(/^(\d{4})-(\d{2})-(\d{2})$/))) return withYear(+m[1], +m[2], +m[3], true);
  if ((m = t.match(new RegExp(`^(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?${MONTH_RE}(?:,?\\s+(\\d{4}))?$`)))) return withYear(m[3] ? +m[3] : year, mon(m[2]), +m[1], Boolean(m[3]));
  if ((m = t.match(new RegExp(`^${MONTH_RE}\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?$`)))) return withYear(m[3] ? +m[3] : year, mon(m[1]), +m[2], Boolean(m[3]));
  if ((m = t.match(/^(\d{1,2})[/.](\d{1,2})(?:[/.](\d{4}))?$/))) return withYear(m[3] ? +m[3] : year, +m[2], +m[1], Boolean(m[3]));
  return null;
}

const monthEdge = (y, m, last) => (last ? key(y, m, new Date(Date.UTC(y, m, 0)).getUTCDate()) : key(y, m, 1));
const dateText = (k) => { const [y, m, d] = parts(k); return `${d} ${MONTHS[m - 1]} ${y}`; };

// ---------------------------------------------------------------- reading a repeat

const ALIASES = {
  daily: 'every day', weekly: 'every week', monthly: 'every month', yearly: 'every year', annually: 'every year',
  weekdays: 'every weekday', workdays: 'every weekday', weekends: 'every weekend',
};
const DAYS_LIST = new RegExp(`^${WD_RE}(?:\\s*-\\s*${WD_RE}|(?:\\s*(?:,|and|&)\\s*|\\s+)${WD_RE})*$`);
const HELP = 'I couldn\'t read that repeat. Try "every day", "every 2 weeks" or "every mon, wed".';

function daysOf(text) {
  const t = text.replace(/\bon\s+/g, '').trim();
  if (!DAYS_LIST.test(t)) return null;
  const days = new Set();
  const range = t.match(new RegExp(`^${WD_RE}\\s*-\\s*${WD_RE}$`));
  if (range) {
    for (let d = dayIndex(range[1]); ; d = d % 7 + 1) { days.add(d); if (d === dayIndex(range[2])) break; }
  } else {
    for (const w of t.match(new RegExp(WD_RE, 'g'))) days.add(dayIndex(w));
  }
  return [...days].sort((a, b) => a - b);
}

// Reads a repeat. Returns { ok: true, rule } or { ok: false, error }. `today` resolves dates without a year (in
// "starting 1 jan" or "until 1 jun"). With `due` (the task's due date), "every month" that starts on the 29th to
// 31st (or "every year" from 29 Feb) keeps that day, so it comes back to the 31st instead of drifting to the 28th.
export function parseRecurrence(input, { today = localKey(), due = null } = {}) {
  const text = String(input ?? '').trim().toLowerCase().replace(/[.]+$/, '').replace(/\s+/g, ' ');
  if (!text) return { ok: false, error: HELP };
  const [headRaw, ...clauses] = text.split(/\s+(until|till|ending|through|starting|beginning|from)\s+/);
  const head = ALIASES[headRaw] ?? headRaw;
  const rule = { unit: null, interval: 1, days: null, nth: null, monthDay: null, month: null, fromCompletion: false, start: null, until: null };

  for (let i = 0; i < clauses.length; i += 2) {
    const until = /^(until|till|ending|through)$/.test(clauses[i]);
    // "dec 2026": the last day of that month for "until", the first for "starting".
    const my = (clauses[i + 1] ?? '').match(new RegExp(`^${MONTH_RE}\\.?,?\\s+(\\d{4})$`));
    const k = my ? monthEdge(+my[2], MONTHS.indexOf(my[1].slice(0, 3)) + 1, until) : parseDay(clauses[i + 1] ?? '', today);
    if (!k) return { ok: false, error: `I couldn't read the date "${clauses[i + 1] ?? ''}".` };
    if (until) rule.until = k; else rule.start = k;
  }
  if (rule.start && rule.until && rule.until < rule.start) return { ok: false, error: 'The end date is before the start date.' };

  const m0 = head.match(/^(?:every(!?)|each)\s*(.*)$/);
  if (!m0) return { ok: false, error: HELP };
  rule.fromCompletion = m0[1] === '!';
  const body = m0[2].replace(/\bthe\s+/g, '').replace(/\s*,\s*/g, ', ').trim();
  const bad = { ok: false, error: HELP };
  let m;

  const interval = (w) => {
    if (w === undefined || w === '') return 1;
    if (w === 'other') return 2;
    const n = Number(w);
    return Number.isInteger(n) && n >= 1 && n <= MAX_INTERVAL ? n : null;
  };

  if ((m = body.match(/^(?:(other|\d+)\s+)?(day|week|month|year)s?(?:\s+(?:on\s+)?(.+))?$/))) {
    const n = interval(m[1]);
    if (n === null) return { ok: false, error: `Repeat every 1 to ${MAX_INTERVAL}.` };
    rule.unit = m[2]; rule.interval = n;
    const rest = m[3];
    if (rest) {
      if (rule.unit === 'week') { rule.days = daysOf(rest); if (!rule.days) return bad; }
      else if (rule.unit === 'month') { if (!monthSpec(rule, rest)) return bad; }
      else if (rule.unit === 'year') {
        const k = /\d{4}/.test(rest) ? null : parseDay(rest, '2000-01-01');
        if (!k) return bad;
        [, rule.month, rule.monthDay] = parts(k);
      } else return bad;
    }
  } else if (/^(weekday|workday)s?$/.test(body)) { rule.unit = 'week'; rule.days = [1, 2, 3, 4, 5]; }
  else if (/^weekends?$/.test(body)) { rule.unit = 'week'; rule.days = [6, 7]; }
  else if ((rule.days = daysOf(body))) rule.unit = 'week';
  else if (monthSpec(rule, body)) rule.unit = 'month';
  else if (new RegExp(`^(?:\\d{1,2}(?:st|nd|rd|th)?\\s+${MONTH_RE}|${MONTH_RE}\\s+\\d{1,2}(?:st|nd|rd|th)?)$`).test(body) && (m = parseDay(body, '2000-01-01'))) {
    rule.unit = 'year'; [, rule.month, rule.monthDay] = parts(m);
  } else return bad;

  if (rule.unit === 'week' && rule.interval === 1 && rule.days?.length === 7) rule.days = null;
  // A repeat that would drift when a short month clamps its day (31 -> 28 -> 28...) keeps the day it started on.
  const from = rule.start ?? due;
  if (from && !rule.fromCompletion) {
    const [, fm, fd] = parts(from);
    if (rule.unit === 'month' && rule.monthDay === null && !rule.nth && fd > 28) rule.monthDay = fd;
    if (rule.unit === 'year' && rule.month === null && fm === 2 && fd === 29) { rule.month = 2; rule.monthDay = 29; }
  }
  return { ok: true, rule };
}

// "15th", "last day", "last friday", "3rd friday", with an optional "of the month".
function monthSpec(rule, text) {
  const t = text.replace(/\s+of\s+(?:every\s+)?month$/, '').trim();
  let m;
  if (t === 'last day') { rule.monthDay = -1; return true; }
  if ((m = t.match(new RegExp(`^(\\d+(?:st|nd|rd|th)|first|second|third|fourth|last)\\s+${WD_RE}$`)))) {
    const n = ORD_WORDS[m[1]] ?? parseInt(m[1], 10);
    if (!(n === -1 || (n >= 1 && n <= 4))) return false;
    rule.nth = { n, dow: dayIndex(m[2]) };
    return true;
  }
  if ((m = t.match(/^(\d{1,2})(?:st|nd|rd|th)$/)) && +m[1] >= 1 && +m[1] <= 31) { rule.monthDay = +m[1]; return true; }
  return false;
}

// ---------------------------------------------------------------- writing a repeat

const dayList = (days) => days.map((d) => DAY_ABBR[d - 1]).join(', ');

// The canonical text of a rule. parseRecurrence(formatRecurrence(rule)) gives the same rule back.
export function formatRecurrence(rule) {
  const every = rule.fromCompletion ? 'every!' : 'every';
  const n = rule.interval;
  const per = (unit) => (n === 1 ? unit : n === 2 ? `other ${unit}` : `${n} ${unit}s`);
  let s;
  if (rule.unit === 'day') s = `${every} ${per('day')}`;
  else if (rule.unit === 'week') {
    const days = rule.days?.join();
    if (!rule.days) s = `${every} ${per('week')}`;
    else if (n === 1 && days === '1,2,3,4,5') s = `${every} weekday`;
    else if (n === 1 && days === '6,7') s = `${every} weekend`;
    else if (n === 1) s = `${every} ${dayList(rule.days)}`;
    else s = `${every} ${per('week')} on ${dayList(rule.days)}`;
  } else if (rule.unit === 'month') {
    const spec = rule.nth ? `${rule.nth.n === -1 ? 'last' : ordinal(rule.nth.n)} ${DAY_ABBR[rule.nth.dow - 1]}`
      : rule.monthDay === -1 ? 'last day' : rule.monthDay ? `the ${ordinal(rule.monthDay)}` : null;
    if (!spec) s = `${every} ${per('month')}`;
    else if (n === 1 && (rule.nth || rule.monthDay === -1)) s = `${every} ${spec}`;
    else s = `${every} ${per('month')} on ${spec}`;
  } else {
    const date = rule.month ? `${rule.monthDay} ${MONTHS[rule.month - 1]}` : null;
    s = !date ? `${every} ${per('year')}` : n === 1 ? `${every} ${date}` : `${every} ${per('year')} on ${date}`;
  }
  if (rule.start) s += ` starting ${dateText(rule.start)}`;
  if (rule.until) s += ` until ${dateText(rule.until)}`;
  return s;
}

// ---------------------------------------------------------------- stepping

const isPlain = (r) => r.unit === 'day' || (r.unit === 'week' && !r.days) || (r.unit === 'month' && !r.nth && r.monthDay === null)
  || (r.unit === 'year' && r.month === null);

function addMonthsKey(k, n) {
  const [y, m, d] = parts(k);
  const idx = y * 12 + m - 1 + n;
  return clamped(Math.floor(idx / 12), mod(idx, 12) + 1, d);
}

// The first date after `base` the rule lands on, counting intervals from `anchor`.
function after(rule, anchor, base) {
  const n = rule.interval;
  if (rule.unit === 'day') {
    if (base < anchor) return anchor;
    return addDays(anchor, (Math.floor(diffDays(base, anchor) / n) + 1) * n);
  }
  if (rule.unit === 'week') {
    const days = rule.days ?? [dow(anchor)];
    const aWeek = weekOf(anchor);
    for (let d = addDays(base, 1), i = 0; i < 7 * n + 14; d = addDays(d, 1), i++) {
      if (d >= anchor && days.includes(dow(d)) && mod(diffDays(weekOf(d), aWeek) / 7, n) === 0) return d;
    }
    return null;
  }
  if (rule.unit === 'month') {
    const [by, bm] = parts(base);
    for (let idx = by * 12 + bm - 1, i = 0; i <= 12 * n + 12; idx++, i++) {
      if (mod(idx - monthIdx(anchor), n) !== 0) continue;
      const y = Math.floor(idx / 12), m = mod(idx, 12) + 1;
      let cand;
      if (rule.nth) {
        const last = daysIn(y, m);
        const day = rule.nth.n === -1
          ? last - mod(dow(key(y, m, last)) - rule.nth.dow, 7)
          : 1 + mod(rule.nth.dow - dow(key(y, m, 1)), 7) + (rule.nth.n - 1) * 7;
        if (day < 1 || day > last) continue;
        cand = key(y, m, day);
      } else if (rule.monthDay === -1) cand = key(y, m, daysIn(y, m));
      else cand = clamped(y, m, rule.monthDay ?? parts(anchor)[2]);
      if (cand > base && cand >= anchor) return cand;
    }
    return null;
  }
  const [ay, am, ad] = parts(anchor);
  for (let y = parts(base)[0]; y <= parts(base)[0] + n + 1; y++) {
    if (mod(y - ay, n) !== 0) continue;
    const cand = clamped(y, rule.month ?? am, rule.monthDay ?? ad);
    if (cand > base && cand >= anchor) return cand;
  }
  return null;
}

// The next due date when a task due on `due` is completed on `done`, or null when the series has ended.
// An overdue task moves on from the day it was completed, not from its old date; a task done early moves on from its
// due date. "every!" always counts from the day it was completed.
export function nextOccurrence(rule, { due, done = due }) {
  let base = rule.fromCompletion || done > due ? done : due;
  if (rule.start && base < addDays(rule.start, -1)) base = addDays(rule.start, -1);
  let next;
  if (rule.fromCompletion && isPlain(rule)) {
    next = rule.unit === 'day' ? addDays(base, rule.interval) : rule.unit === 'week' ? addDays(base, 7 * rule.interval)
      : addMonthsKey(base, rule.interval * (rule.unit === 'year' ? 12 : 1));
  } else next = after(rule, rule.start ?? due, base);
  if (!next || (rule.until && next > rule.until)) return null;
  return next;
}

// For the server: the canonical text of a repeat, or an error to show.
export function cleanRecurrence(input, opts) {
  const r = parseRecurrence(input, opts);
  return r.ok ? { ok: true, text: formatRecurrence(r.rule), rule: r.rule } : r;
}
