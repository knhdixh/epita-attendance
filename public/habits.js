// Habits: pure functions shared by the browser (public/habits-ui.js) and the Worker (src/habits.js, src/notify.js),
// tested in node. A habit is { id, name, days, remind_at, tz, checks }: `days` is a bit mask (bit 0 = Monday …
// bit 6 = Sunday), `remind_at` 'HH:MM' or null, and `checks` the 'YYYY-MM-DD' days it was done, in its time zone.

export const ALL_DAYS = 127;
export const WEEKDAYS = 31;
export const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
export const MAX_HABITS = 30, NAME_LEN = 80;

const parts = new Map();
function formatter(tz) {
  if (!parts.has(tz)) {
    parts.set(tz, new Intl.DateTimeFormat('en-US', {
      timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', weekday: 'short',
    }));
  }
  return parts.get(tz);
}
const WD = { Mon: 0, Tue: 1, Wed: 2, Thu: 3, Fri: 4, Sat: 5, Sun: 6 };

// The day ('YYYY-MM-DD'), weekday (0 = Monday) and minute of the day of an instant in a time zone.
export function localTime(ms, tz) {
  const p = Object.fromEntries(formatter(tz).formatToParts(ms).map((x) => [x.type, x.value]));
  return { day: `${p.year}-${p.month}-${p.day}`, weekday: WD[p.weekday], minutes: Number(p.hour) * 60 + Number(p.minute) };
}

// Calendar arithmetic on 'YYYY-MM-DD' (no time zone involved).
export function addDay(day, n) {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}
export const weekdayOf = (day) => (new Date(`${day}T00:00:00Z`).getUTCDay() + 6) % 7;
export const isDue = (habit, day) => Boolean(habit.days & (1 << weekdayOf(day)));
export const toMinutes = (hhmm) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5));

// Consecutive scheduled days done, counting back from `today`. Today only counts once it's done, so an
// unchecked today doesn't break the streak until the day is over. Days the habit isn't scheduled are skipped.
export function streak(habit, today) {
  const done = new Set(habit.checks);
  let n = 0, day = today;
  if (!done.has(today)) day = addDay(today, -1);
  for (let i = 0; i < 400; i++, day = addDay(day, -1)) {
    if (!isDue(habit, day)) continue;
    if (!done.has(day)) break;
    n++;
  }
  return n;
}

// "Every day", "Weekdays", "Weekends", or the day names.
export function daysLabel(days) {
  if (days === ALL_DAYS) return 'Every day';
  if (days === WEEKDAYS) return 'Weekdays';
  if (days === 96) return 'Weekends';
  return DAY_NAMES.filter((_, i) => days & (1 << i)).join(', ');
}

// A habit's reminder is due in the cron run at `now` if its time passed less than `window` ms ago, on a day it's
// scheduled and not done yet. Returns the day it's for, or null.
export function reminderDay(habit, now, window = 5 * 60000) {
  if (!habit.remind_at) return null;
  const { day, minutes } = localTime(now, habit.tz);
  const late = minutes - toMinutes(habit.remind_at);
  if (late < 0 || late * 60000 >= window) return null;
  if (!isDue(habit, day) || habit.checks.includes(day)) return null;
  return day;
}
