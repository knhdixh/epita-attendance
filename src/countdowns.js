// Countdowns and count-ups. Stored as given; how they're shown is worked out in the browser (public/countdowns.js).
import { HttpError } from './http.js';
import { cleanCountdown, MAX_COUNTDOWNS } from '../public/countdowns.js';

export const COUNTDOWN_COLUMNS = 'id, title, at, all_day, repeat, units, after, start_at, color, pinned, note, event_id, homework_id, created_at';

// A linked session or homework item must be the user's own; anything else is dropped.
async function checkLinks(env, user, c) {
  if (c.event_id != null && !(await env.DB.prepare('SELECT 1 FROM events WHERE id = ? AND user_id = ?').bind(c.event_id, user.id).first())) c.event_id = null;
  if (c.homework_id != null && !(await env.DB.prepare('SELECT 1 FROM homework WHERE id = ? AND user_id = ?').bind(c.homework_id, user.id).first())) c.homework_id = null;
}

export async function createCountdown(env, user, body, now = Date.now()) {
  const r = cleanCountdown(body);
  if (!r.ok) throw new HttpError(400, r.error);
  const c = r.value;
  await checkLinks(env, user, c);
  const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM countdowns WHERE user_id = ?').bind(user.id).first();
  if (n >= MAX_COUNTDOWNS) throw new HttpError(400, `You can have up to ${MAX_COUNTDOWNS} countdowns. Delete one first.`);
  return env.DB.prepare(`INSERT INTO countdowns (user_id, title, at, all_day, repeat, units, after, start_at, color, pinned, note,
      event_id, homework_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING ${COUNTDOWN_COLUMNS}`)
    .bind(user.id, c.title, c.at, c.all_day, c.repeat, c.units, c.after, c.start_at ?? null, c.color, c.pinned ?? 0, c.note ?? null,
      c.event_id ?? null, c.homework_id ?? null, now, now).first();
}

export async function updateCountdown(env, user, id, body, now = Date.now()) {
  const r = cleanCountdown(body, { partial: true });
  if (!r.ok) throw new HttpError(400, r.error);
  await checkLinks(env, user, r.value);
  const fields = Object.entries(r.value);
  const row = await env.DB.prepare(`UPDATE countdowns SET ${fields.map(([k]) => `${k} = ?, `).join('')}updated_at = ?
    WHERE id = ? AND user_id = ? RETURNING ${COUNTDOWN_COLUMNS}`).bind(...fields.map(([, v]) => v), now, id, user.id).first();
  if (!row) throw new HttpError(404, 'Countdown not found.');
  return row;
}

export async function deleteCountdown(env, user, id) {
  const r = await env.DB.prepare('DELETE FROM countdowns WHERE id = ? AND user_id = ?').bind(id, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, 'Countdown not found.');
  return { ok: true };
}
