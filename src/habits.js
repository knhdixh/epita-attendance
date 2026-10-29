// Habits and their check-ins. The browser decides which local day a check-in belongs to and sends it as a
// 'YYYY-MM-DD' key; streaks and stats are computed from the logs by public/habits.js, in the browser and for /api/plan.
import { HttpError } from './http.js';
import { cleanHabit, isKey, MAX_HABITS } from '../public/habits.js';

const COLUMNS = 'id, name, icon, color, schedule, target, unit, remind_at, position, archived, created_at';
const MAX_COUNT = 999;
export const LOG_DAYS = 400;   // how far back /api/data and /api/plan load check-ins

export const habitRow = (r) => r && { ...r, schedule: JSON.parse(r.schedule), archived: Boolean(r.archived) };

export function habitQueries(env, user, sinceKey) {
  const DB = env.DB;
  return [
    DB.prepare(`SELECT ${COLUMNS} FROM habits WHERE user_id = ? ORDER BY position, id`).bind(user.id),
    // habits first (a handful of rows, via habits_user), then each one's logs by primary key.
    DB.prepare(`SELECT l.habit_id, l.day, l.count FROM habits h CROSS JOIN habit_logs l ON l.habit_id = h.id
      WHERE h.user_id = ? AND l.day >= ? ORDER BY l.day`).bind(user.id, sinceKey),
  ];
}

async function own(env, user, id) {
  const row = await env.DB.prepare(`SELECT ${COLUMNS} FROM habits WHERE id = ? AND user_id = ?`).bind(id, user.id).first();
  if (!row) throw new HttpError(404, 'Habit not found.');
  return row;
}

export async function createHabit(env, user, body, now = Date.now()) {
  const r = cleanHabit(body);
  if (!r.ok) throw new HttpError(400, r.error);
  const h = r.value;
  const { n, top } = await env.DB.prepare('SELECT COUNT(*) AS n, MAX(position) AS top FROM habits WHERE user_id = ?').bind(user.id).first();
  if (n >= MAX_HABITS) throw new HttpError(400, `You can have up to ${MAX_HABITS} habits. Archive or delete one first.`);
  return habitRow(await env.DB.prepare(`INSERT INTO habits (user_id, name, icon, color, schedule, target, unit, remind_at, position, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING ${COLUMNS}`)
    .bind(user.id, h.name, h.icon ?? null, h.color, JSON.stringify(h.schedule), h.target, h.unit ?? null, h.remind_at ?? null, (top ?? -1) + 1, now, now).first());
}

export async function updateHabit(env, user, id, body, now = Date.now()) {
  await own(env, user, id);
  const r = cleanHabit(body, { partial: true });
  if (!r.ok) throw new HttpError(400, r.error);
  const fields = Object.entries(r.value).map(([k, v]) => [k, k === 'schedule' ? JSON.stringify(v) : v]);
  if (!fields.length) return habitRow(await own(env, user, id));
  return habitRow(await env.DB.prepare(`UPDATE habits SET ${fields.map(([k]) => `${k} = ?`).join(', ')}, updated_at = ?
    WHERE id = ? AND user_id = ? RETURNING ${COLUMNS}`).bind(...fields.map(([, v]) => v), now, id, user.id).first());
}

export async function deleteHabit(env, user, id) {
  const r = await env.DB.prepare('DELETE FROM habits WHERE id = ? AND user_id = ?').bind(id, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, 'Habit not found.');
  return { ok: true };
}

// The check-ins of one day. 0 removes the day.
export async function logHabit(env, user, id, body, now = Date.now()) {
  const day = body?.day, count = body?.count;
  if (!isKey(day) || day > new Date(now + 2 * 86400000).toISOString().slice(0, 10) || day < '2000-01-01') throw new HttpError(400, 'Invalid day.');
  if (!Number.isInteger(count) || count < 0 || count > MAX_COUNT) throw new HttpError(400, 'Invalid count.');
  await own(env, user, id);
  if (count === 0) await env.DB.prepare('DELETE FROM habit_logs WHERE habit_id = ? AND day = ?').bind(id, day).run();
  else await env.DB.prepare('INSERT INTO habit_logs (habit_id, day, count) VALUES (?, ?, ?) ON CONFLICT (habit_id, day) DO UPDATE SET count = excluded.count')
    .bind(id, day, count).run();
  return { habit_id: id, day, count };
}

// New order, from a list of ids (habits left out keep their place after these).
export async function reorderHabits(env, user, body) {
  const ids = Array.isArray(body?.ids) ? body.ids.filter(Number.isSafeInteger).slice(0, MAX_HABITS) : null;
  if (!ids?.length) throw new HttpError(400, 'ids must be a list of habit ids.');
  await env.DB.batch(ids.map((id, i) => env.DB.prepare('UPDATE habits SET position = ? WHERE id = ? AND user_id = ? AND position != ?').bind(i, id, user.id, i)));
  return { ok: true };
}
