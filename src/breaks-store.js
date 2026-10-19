// Saving/removing breaks: shared by the REST API and the AI assistant.
import { HttpError } from './http.js';
import { breakModel, simulateBreak, isDate } from '../public/breaks.js';
import { isValidTimeZone } from './homework.js';

export async function loadPlanningData(env, user) {
  const [courses, events] = await env.DB.batch([
    env.DB.prepare('SELECT id, name, tracked, tag FROM courses WHERE user_id = ?').bind(user.id),
    env.DB.prepare('SELECT id, course_id, summary, start_at, end_at, status, tag FROM events WHERE user_id = ? ORDER BY start_at').bind(user.id),
  ]);
  return { courses: courses.results, events: events.results };
}

export function planningModel(data, user, tz, now = Date.now()) {
  return breakModel({ ...data, threshold: user.threshold, unit: user.unit, tz, now });
}

// Marks every future, counted session in the range as a planned absence and records the break.
export async function saveBreak(env, user, { from, to, label = '', tz, now = Date.now() }) {
  if (!isDate(from) || !isDate(to) || to < from) throw new HttpError(400, 'Invalid break dates.');
  if (!isValidTimeZone(tz)) throw new HttpError(400, 'Invalid time zone.');
  if (typeof label !== 'string' || label.length > 100) throw new HttpError(400, 'Label is too long.');
  const sim = simulateBreak(planningModel(await loadPlanningData(env, user), user, tz, now), from, to);
  const changes = sim.sessions.map((e) => ({ id: e.id, from: e.status }));
  const statements = sim.sessions.map((e) =>
    env.DB.prepare(`UPDATE events SET status = 'absent' WHERE id = ? AND user_id = ?`).bind(e.id, user.id));
  statements.push(env.DB.prepare(`INSERT INTO breaks (user_id, label, start_date, end_date, changes, created_at)
    VALUES (?, ?, ?, ?, ?, ?) RETURNING id, label, start_date, end_date, changes, created_at`)
    .bind(user.id, label.trim(), sim.from, to, JSON.stringify(changes), now));
  const results = await env.DB.batch(statements);
  return { break: results.at(-1).results[0], simulation: sim };
}

// Restores sessions the break marked absent (only those still marked absent).
export async function removeBreak(env, user, id) {
  const row = await env.DB.prepare('SELECT * FROM breaks WHERE id = ? AND user_id = ?').bind(id, user.id).first();
  if (!row) throw new HttpError(404, 'Break not found.');
  const changes = JSON.parse(row.changes || '[]');
  await env.DB.batch([
    ...changes.map((c) => env.DB.prepare(`UPDATE events SET status = ? WHERE id = ? AND user_id = ? AND status = 'absent'`)
      .bind(c.from, c.id, user.id)),
    env.DB.prepare('DELETE FROM breaks WHERE id = ? AND user_id = ?').bind(id, user.id),
  ]);
  return { restored: changes.length };
}
