// Quick add, Todoist style: "Pay rent every month on the 1st #Home @bills p1 !1h for 15m {fri}" becomes a task. Returns the
// task's fields and the ranges of the text each one was read from, so the app can highlight them while you type.
// Pure functions on day keys ('YYYY-MM-DD'): pass `today` to read "tomorrow" in the user's time zone.
//
//   date       today, tomorrow, tmr, tom, yesterday, mon, next fri, next week (Monday), next month (the 1st), in 3 days,
//              jan 27, 27 jan, 27/1, 27/1/2027, 2027-01-27 ("on" and "by" in front are part of it). A weekday is the next one
//              after today, as in countdowns.js. A date without a year is its next occurrence.
//   time       at 9am, 9am, 9:30, 9.30pm, at 18, 18h30
//   repeat     every day, every mon, wed, every 3rd friday, every! 3 days ... (see recurrence.js)
//   #project   /section   @label (several)   p1 to p4 (p1 is the most urgent)
//   !30m !2h !1d   a reminder that long before it's due
//   for 45m, for 2h, for 1h30m   how long it takes
//   {friday}   the deadline, apart from the due date
import { addDays, dow, localKey, weekOf } from './habits.js';
import { MONTH_RE, WD_RE } from './countdowns.js';
import { parseDay, parseRecurrence, formatRecurrence, nextOccurrence } from './recurrence.js';

const WEEKDAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
const END = '(?=$|[\\s,.;!?])';
const START = '(?<!\\S)';
const MAX_MINUTES = 24 * 60;
const MAX_REMINDER = 28 * 1440;
const pad = (n) => String(n).padStart(2, '0');

// ---------------------------------------------------------------- dates

const WORD_NUMS = { a: 1, an: 1, one: 1, two: 2, three: 3 };
const lower = (s) => s.toLowerCase();

// Each pattern reads one kind of date phrase; `key(match, today)` turns it into a day key or null.
const DATE_PATTERNS = [
  [/(\d{4})-(\d{2})-(\d{2})/, (m, today) => parseDay(m[0], today)],
  [new RegExp(`(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+${MONTH_RE}\\.?(?:,?\\s+(\\d{4}))?`), (m, today) => parseDay(`${m[1]} ${m[2]}${m[3] ? ` ${m[3]}` : ''}`, today)],
  [new RegExp(`${MONTH_RE}\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?`), (m, today) => parseDay(`${m[1]} ${m[2]}${m[3] ? ` ${m[3]}` : ''}`, today)],
  [/(\d{1,2})[/.](\d{1,2})(?:[/.](\d{2,4}))?/, (m, today) => {
    const y = m[3] ? (m[3].length === 2 ? 2000 + Number(m[3]) : Number(m[3])) : null;
    if (m[3] && m[3].length === 3) return null;
    return parseDay(`${m[1]}/${m[2]}${y ? `/${y}` : ''}`, today);
  }],
  [/in\s+(\d{1,3}|a|an|one|two|three)\s+(day|week|month|year)s?/, (m, today) => {
    const n = WORD_NUMS[lower(m[1])] ?? Number(m[1]);
    const unit = lower(m[2]);
    if (unit === 'day') return addDays(today, n);
    if (unit === 'week') return addDays(today, 7 * n);
    return addMonthsKeep(today, unit === 'month' ? n : 12 * n);
  }],
  [/next\s+week/, (_, today) => addDays(weekOf(today), 7)],
  [/next\s+month/, (_, today) => addMonthsKeep(today.slice(0, 8) + '01', 1)],
  [/(today|tonight|tomorrow|tmr|tom|yesterday)/, (m, today) => {
    const w = m[1];
    if (/^tom$/i.test(w) && w !== 'tom') return null;     // "Tom" is a name, "tom" is tomorrow
    return addDays(today, /^(tomorrow|tmr|tom)$/i.test(w) ? 1 : /^yesterday$/i.test(w) ? -1 : 0);
  }],
  [/(?:this\s+)?weekend/, (_, today) => addDays(today, (6 - dow(today) + 7) % 7)],
  [new RegExp(`(?:(?:next|this)\\s+)?${WD_RE}`), (m, today) => {
    const wd = WEEKDAYS.indexOf(lower(m[1]).slice(0, 3)) + 1;
    return addDays(today, (wd - dow(today) + 7) % 7 || 7);
  }],
];

// Month arithmetic on a day key: same day next month, clamped to the end of a short month.
function addMonthsKeep(k, n) {
  const idx = Number(k.slice(0, 4)) * 12 + Number(k.slice(5, 7)) - 1 + n;
  const y = Math.floor(idx / 12), m = idx % 12 + 1;
  return `${y}-${pad(m)}-${pad(Math.min(Number(k.slice(8, 10)), new Date(Date.UTC(y, m, 0)).getUTCDate()))}`;
}

const datePrefix = '(?:(?:on|by|due)\\s+)?';

// A whole date phrase as a day key (what goes in braces), or null.
export function dayFromPhrase(text, today = localKey()) {
  const t = String(text).trim();
  for (const [re, key] of DATE_PATTERNS) {
    const m = new RegExp(`^${datePrefix}${re.source}$`, 'i').exec(t);
    const k = m && key(m, today);
    if (k) return k;
  }
  return null;
}

// ---------------------------------------------------------------- times and durations

// '09:30' from the pieces of a time, or null when they aren't a time.
function clock(h, min, ampm) {
  let hour = Number(h);
  const minute = min === undefined || min === '' ? 0 : Number(min);
  if (minute > 59) return null;
  if (ampm) {
    if (hour < 1 || hour > 12) return null;
    hour = hour % 12 + (lower(ampm) === 'pm' ? 12 : 0);
  }
  return hour > 23 ? null : `${pad(hour)}:${pad(minute)}`;
}

const TIME_PATTERNS = [
  [/(?:at\s+)?(\d{1,2}):(\d{2})\s*(am|pm)?/, (m) => clock(m[1], m[2], m[3])],
  [/(?:at\s+)?(\d{1,2})\.(\d{2})\s*(am|pm)/, (m) => clock(m[1], m[2], m[3])],
  [/(?:at\s+)?(\d{1,2})\s*(am|pm)/, (m) => clock(m[1], 0, m[2])],
  [/(?:at\s+)?(\d{1,2})h(\d{2})?/, (m) => clock(m[1], m[2])],
  [/at\s+(\d{1,2})/, (m) => clock(m[1], 0)],
];

// "45m", "2h", "1h30m", "1.5h", "90 min", "1 hour 30 min" in minutes, or null.
export function minutesFrom(text) {
  const t = String(text).trim().toLowerCase();
  if (!/^\d+(?:[.,]\d+)?\s*(?:h(?:ours?|rs?)?|m(?:in(?:ute)?s?)?|d(?:ays?)?)(?:\s*\d+\s*m(?:in(?:ute)?s?)?)?$/.test(t)) return null;
  let total = 0;
  for (const [, n, unit] of t.matchAll(/(\d+(?:[.,]\d+)?)\s*(h|m|d)/g)) {
    total += Number(n.replace(',', '.')) * (unit === 'h' ? 60 : unit === 'd' ? 1440 : 1);
  }
  total = Math.round(total);
  return total >= 1 ? total : null;
}

const UNIT_RE = '(?:h(?:ours?|rs?)?|m(?:in(?:ute)?s?)?)';
const DURATION = new RegExp(`${START}for\\s+(\\d+(?:[.,]\\d+)?\\s*${UNIT_RE}(?:\\s*\\d+\\s*m(?:in(?:ute)?s?)?)?)${END}`, 'i');
const REMINDER = new RegExp(`${START}!(\\d+(?:[.,]\\d+)?\\s*(?:${UNIT_RE}(?:\\s*\\d+\\s*m(?:in(?:ute)?s?)?)?|d(?:ays?)?))${END}`, 'i');

// ---------------------------------------------------------------- the parser

// `projects`, `sections` and `labels` are the names that exist. After #, / or @ the longest of them that fits is taken (so
// "#School admin" and "/Week 1" work); with none, or none that fits, it's one word as before. The value is the existing name.
export function parseQuickAdd(input, { today = localKey(), projects = [], sections = [], labels = [] } = {}) {
  const text = String(input ?? '');
  let w = text;                  // what's left to read; matched text is blanked so indices stay those of `text`
  const ranges = [];
  const claim = (kind, start, end, value) => {
    ranges.push({ kind, start, end, text: text.slice(start, end), value });
    w = w.slice(0, start) + ' '.repeat(end - start) + w.slice(end);
  };
  // Finds the first match of `re` in what's left that `read` accepts, and claims it.
  const take = (kind, re, read) => {
    const g = new RegExp(re.source, `g${re.flags.replace('g', '')}`);
    for (let m = g.exec(w); m; m = g.exec(w)) {
      const value = read(m);
      if (value !== null && value !== undefined) { claim(kind, m.index, m.index + m[0].length, value); return value; }
    }
    return null;
  };
  const name = (s) => s.replace(/^"|"$/g, '').replace(/[,.;:!?]+$/, '');
  // Like `take`, for a name after a sigil: the longest known name at a word boundary, else what `re` reads.
  const takeNamed = (kind, sigil, known, re) => {
    const names = [...new Set((known ?? []).map(String).filter(Boolean))].sort((a, b) => b.length - a.length);
    const fallback = (m) => name(m[1]) || null;
    if (!names.length) return take(kind, re, fallback);
    const g = new RegExp(`${START}${sigil.replace(/[/\\]/g, '\\$&')}`, 'gu');
    for (let m = g.exec(w); m; m = g.exec(w)) {
      const from = m.index + m[0].length;
      const hit = names.find((n) => lower(w.slice(from, from + n.length)) === lower(n) && new RegExp(`^${END}`).test(w.slice(from + n.length)));
      if (hit) { claim(kind, m.index, from + hit.length, hit); return hit; }
    }
    return take(kind, re, fallback);
  };

  const out = {
    title: '', due_date: null, due_time: null, recurrence: null, project: null, section: null, labels: [], priority: 1,
    deadline: null, reminder: null, duration: null, ranges,
  };

  // {deadline}
  out.deadline = take('deadline', /\{([^{}]+)\}/, (m) => dayFromPhrase(m[1], today));

  // every ...: the longest run of words after "every" that reads as a repeat.
  let repeatText = null;
  {
    const g = /(?<!\S)every!?(?=\s)/gi;
    for (let m = g.exec(w); m && !repeatText; m = g.exec(w)) {
      const words = [...w.slice(m.index).matchAll(/\S+/g)];
      for (let n = Math.min(words.length, 16); n >= 2; n--) {
        const end = m.index + words[n - 1].index + words[n - 1][0].length;
        const phrase = w.slice(m.index, end).replace(/[,;.]+$/, '');
        if (parseRecurrence(phrase, { today }).ok) {
          repeatText = phrase;
          claim('recurrence', m.index, m.index + phrase.length, null);
          break;
        }
      }
    }
  }

  out.project = takeNamed('project', '#', projects, /(?<!\S)#("[^"]+"|[^\s#]+)/u);
  out.section = takeNamed('section', '/', sections, /(?<!\S)\/("[^"]+"|\p{L}[^\s/]*)/u);
  for (let label = takeNamed('label', '@', labels, /(?<!\S)@([^\s@#]+)/); label; label = takeNamed('label', '@', labels, /(?<!\S)@([^\s@#]+)/)) {
    if (!out.labels.some((l) => lower(l) === lower(label))) out.labels.push(label);
  }
  const p = take('priority', new RegExp(`${START}p([1-4])${END}`, 'i'), (m) => Number(m[1]));
  if (p) out.priority = 5 - p;     // p1 is the most urgent, and 4 in our data
  out.reminder = take('reminder', REMINDER, (m) => {
    const mins = minutesFrom(m[1]);
    return mins !== null && mins <= MAX_REMINDER ? mins : null;
  });
  out.duration = take('duration', DURATION, (m) => {
    const mins = minutesFrom(m[1]);
    return mins !== null && mins <= MAX_MINUTES ? mins : null;
  });

  // Time first (like countdowns.js), so "9.30pm" isn't read as a date.
  for (const [re, read] of TIME_PATTERNS) {
    if (out.due_time) break;
    out.due_time = take('time', new RegExp(`${START}${re.source}${END}`, 'i'), read);
  }
  for (const [re, key] of DATE_PATTERNS) {
    if (out.due_date) break;
    out.due_date = take('date', new RegExp(`${START}${datePrefix}${re.source}${END}`, 'i'), (m) => key(m, today));
  }

  if (repeatText) {
    const first = parseRecurrence(repeatText, { today });
    const r = first.rule;
    const plain = !r.days && !r.nth && r.monthDay === null && !(r.unit === 'year' && r.month);
    if (!out.due_date) {
      out.due_date = plain && !r.start ? today : nextOccurrence(r, { due: addDays(today, -1) }) ?? today;
    }
    const final = parseRecurrence(repeatText, { today, due: out.due_date });
    out.recurrence = formatRecurrence(final.rule);
    const range = ranges.find((x) => x.kind === 'recurrence');
    range.value = out.recurrence;
  }
  if (out.due_time && !out.due_date) out.due_date = today;

  out.title = w.replace(/\s+(?:on|at|by|due|for)\s*$/i, ' ').replace(/\s+/g, ' ').replace(/^[\s,–—:-]+|[\s,–—:-]+$/g, '').trim();
  ranges.sort((a, b) => a.start - b.start);
  return out;
}

