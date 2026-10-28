// Habits (see public/habits.js): list, create, edit, delete, and tick off a day.
import { HttpError } from './http.js';
import { isValidTimeZone } from './homework.js';
import { MAX_HABITS, NAME_LEN, ALL_DAYS } from '../public/habits.js';

const CHECK_DAYS = 400; // the browser shows a year; older checks stay in the database

const DAY_RE = /^\d{4}-\d{2}-\d{2}$/;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

function clean(b, old = {}) {
  const h = { ...old };
  if ('name' in b || !old.name) {
    const name = typeof b.name === 'string' ? b.name.trim().replace(/\s+/g, ' ').slice(0, NAME_LEN) : '';
    if (!name) throw new HttpError(400, 'Give the habit a name.');
    h.name = name;
  }
  if ('days' in b || old.days == null) {
    const days = b.days ?? ALL_DAYS;
    if (!Number.isInteger(days) || days < 1 || days > ALL_DAYS) throw new HttpError(400, 'Pick at least one day.');
    h.days = days;
  }
  if ('remind_at' in b) {
    if (b.remind_at !== null && !(typeof b.remind_at === 'string' && TIME_RE.test(b.remind_at))) throw new HttpError(400, 'Invalid reminder time.');
    h.remind_at = b.remind_at;
  }
  if ('tz' in b || !old.tz) {
    if (!(typeof b.tz === 'string' && isValidTimeZone(b.tz))) throw new HttpError(400, 'Invalid time zone.');
    h.tz = b.tz;
  }
  return h;
}

export async function listHabits(env, user, now = Date.now()) {
  const [habits, checks] = await env.DB.batch([
    env.DB.prepare('SELECT id, name, days, remind_at, tz, created_at FROM habits WHERE user_id = ? ORDER BY created_at, id').bind(user.id),
    env.DB.prepare(`SELECT c.habit_id, c.day FROM habit_checks c JOIN habits h ON h.id = c.habit_id
      WHERE h.user_id = ? AND c.day >= ? ORDER BY c.day`).bind(user.id, new Date(now - CHECK_DAYS * 86400000).toISOString().slice(0, 10)),
  ]);
  const byHabit = new Map();
  for (const c of checks.results) {
    if (!byHabit.has(c.habit_id)) byHabit.set(c.habit_id, []);
    byHabit.get(c.habit_id).push(c.day);
  }
  return habits.results.map((h) => ({ ...h, checks: byHabit.get(h.id) || [] }));
}

export async function createHabit(env, user, b, now = Date.now()) {
  const h = clean({ remind_at: null, ...b });
  const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM habits WHERE user_id = ?').bind(user.id).first();
  if (n >= MAX_HABITS) throw new HttpError(400, `You can have up to ${MAX_HABITS} habits.`);
  const row = await env.DB.prepare(`INSERT INTO habits (user_id, name, days, remind_at, tz, created_at) VALUES (?, ?, ?, ?, ?, ?)
    RETURNING id, name, days, remind_at, tz, created_at`).bind(user.id, h.name, h.days, h.remind_at, h.tz, now).first();
  return { ...row, checks: [] };
}

async function own(env, user, id) {
  const h = await env.DB.prepare('SELECT id, name, days, remind_at, tz, created_at FROM habits WHERE id = ? AND user_id = ?').bind(id, user.id).first();
  if (!h) throw new HttpError(404, 'Habit not found.');
  return h;
}

export async function updateHabit(env, user, id, b) {
  const h = clean(b, await own(env, user, id));
  await env.DB.prepare('UPDATE habits SET name = ?, days = ?, remind_at = ?, tz = ? WHERE id = ? AND user_id = ?')
    .bind(h.name, h.days, h.remind_at, h.tz, id, user.id).run();
  return h;
}

export async function deleteHabit(env, user, id) {
  const r = await env.DB.prepare('DELETE FROM habits WHERE id = ? AND user_id = ?').bind(id, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, 'Habit not found.');
  return { ok: true };
}

// Ticks a day off (done = true) or clears it. Days up to a day ahead are accepted, for time zones east of the server.
export async function checkHabit(env, user, id, day, done, now = Date.now()) {
  if (!DAY_RE.test(day) || Number.isNaN(Date.parse(`${day}T00:00:00Z`))) throw new HttpError(400, 'Invalid day.');
  if (Date.parse(`${day}T00:00:00Z`) > now + 2 * 86400000) throw new HttpError(400, 'That day hasn\'t come yet.');
  await own(env, user, id);
  await env.DB.prepare(done ? 'INSERT INTO habit_checks (habit_id, day) VALUES (?, ?) ON CONFLICT DO NOTHING'
    : 'DELETE FROM habit_checks WHERE habit_id = ? AND day = ?').bind(id, day).run();
  return { ok: true };
}
