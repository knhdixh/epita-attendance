// Focus (Pomodoro) sessions and timer settings. The timer runs in the browser (public/focus-ui.js); the server
// records finished focus phases and serves them back for the stats. Recording is idempotent on the timer's run id.
import { HttpError } from './http.js';
import { cleanSettings } from '../public/focus.js';

export const FOCUS_COLUMNS = 'id, client_id, homework_id, course_id, label, started_at, ended_at, planned_seconds, focus_seconds, completed';
const MIN = 60000;
const MAX_FOCUS_S = 4 * 3600;
const MAX_AGE = 30 * 86400000;   // sessions queued offline are accepted for a month
const MAX_PER_DAY = 150;         // keeps a runaway client from using up the D1 write allowance

export const focusSettings = (user) => {
  try { return cleanSettings(JSON.parse(user.focus_settings || 'null')); } catch { return cleanSettings(null); }
};

export async function saveFocusSettings(env, user, body) {
  const settings = cleanSettings({ ...focusSettings(user), ...body });
  await env.DB.prepare('UPDATE users SET focus_settings = ? WHERE id = ?').bind(JSON.stringify(settings), user.id).run();
  return settings;
}

const int = (v) => (Number.isSafeInteger(v) ? v : NaN);

export async function logFocus(env, user, b, now = Date.now()) {
  const clientId = typeof b.client_id === 'string' && /^[A-Za-z0-9_-]{8,64}$/.test(b.client_id) ? b.client_id : null;
  if (!clientId) throw new HttpError(400, 'Invalid client_id.');
  const started = int(b.started_at), ended = int(b.ended_at), focus = int(b.focus_seconds), planned = int(b.planned_seconds);
  if (!(started > now - MAX_AGE && ended >= started && ended <= now + 5 * MIN)) throw new HttpError(400, 'Invalid start or end time.');
  if (!(focus >= 60 && focus <= MAX_FOCUS_S && focus * 1000 <= ended - started + 5000)) throw new HttpError(400, 'Invalid focus time.');
  if (!(planned >= 60 && planned <= MAX_FOCUS_S)) throw new HttpError(400, 'Invalid planned time.');

  // Homework can be deleted while a session waits offline: the session is then kept without it.
  let homeworkId = null, courseId = null, label = typeof b.label === 'string' ? b.label.trim().slice(0, 200) || null : null;
  if (b.homework_id != null) {
    const hw = await env.DB.prepare('SELECT id, course_id, title FROM homework WHERE id = ? AND user_id = ?').bind(Number(b.homework_id), user.id).first();
    if (hw) { homeworkId = hw.id; courseId = hw.course_id; label = label || hw.title; }
  }
  if (courseId == null && b.course_id != null) {
    const c = await env.DB.prepare('SELECT id FROM courses WHERE id = ? AND user_id = ?').bind(Number(b.course_id), user.id).first();
    courseId = c?.id ?? null;
  }

  const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM focus_sessions WHERE user_id = ? AND started_at > ?')
    .bind(user.id, now - 86400000).first();
  if (n >= MAX_PER_DAY) throw new HttpError(429, 'That\'s a lot of focus sessions for one day. Try again tomorrow.');

  const row = await env.DB.prepare(`INSERT INTO focus_sessions (user_id, client_id, homework_id, course_id, label, started_at, ended_at,
      planned_seconds, focus_seconds, completed, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (user_id, client_id) DO NOTHING RETURNING ${FOCUS_COLUMNS}`)
    .bind(user.id, clientId, homeworkId, courseId, label, started, ended, planned, focus, b.completed ? 1 : 0, now).first();
  return row ?? env.DB.prepare(`SELECT ${FOCUS_COLUMNS} FROM focus_sessions WHERE user_id = ? AND client_id = ?`).bind(user.id, clientId).first();
}

export async function listFocus(env, user, since) {
  const r = await env.DB.prepare(`SELECT ${FOCUS_COLUMNS} FROM focus_sessions WHERE user_id = ? AND started_at >= ? ORDER BY started_at`)
    .bind(user.id, since).all();
  return r.results;
}

export async function deleteFocus(env, user, id) {
  const r = await env.DB.prepare('DELETE FROM focus_sessions WHERE id = ? AND user_id = ?').bind(id, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, 'Focus session not found.');
  return { ok: true };
}
