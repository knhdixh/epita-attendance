// Homework persistence shared by the REST API and the AI assistant.
import { HttpError } from './http.js';
import { REMIND_OPTIONS, isValidTimeZone, dueInstant } from './homework.js';
import { syncHomework } from './gcal.js';

export const HOMEWORK_COLUMNS = `id, course_id, title, notes, due_date, due_time, tz, due_at, remind_minutes, done,
  gcal_event_id, sync_error, created_at, updated_at`;

export async function homeworkInput(env, user, b) {
  const title = typeof b.title === 'string' ? b.title.trim() : '';
  if (!title || title.length > 200) throw new HttpError(400, 'Title is required (max 200 characters).');
  const notes = typeof b.notes === 'string' ? b.notes : '';
  if (notes.length > 4000) throw new HttpError(400, 'Notes are too long.');
  const parsed = Date.parse((b.due_date || '') + 'T00:00:00Z');
  const realDate = /^\d{4}-\d{2}-\d{2}$/.test(b.due_date || '') && !Number.isNaN(parsed)
    && new Date(parsed).toISOString().startsWith(b.due_date); // rejects e.g. 2026-13-45 and 2026-02-30
  if (!realDate) throw new HttpError(400, 'Invalid due date.');
  const dueTime = b.due_time || null;
  if (dueTime !== null && !/^([01]\d|2[0-3]):[0-5]\d$/.test(dueTime)) throw new HttpError(400, 'Invalid due time.');
  if (!isValidTimeZone(b.tz)) throw new HttpError(400, 'Invalid time zone.');
  const remind = b.remind_minutes ?? null;
  if (remind !== null && !REMIND_OPTIONS.includes(remind)) throw new HttpError(400, 'Invalid reminder.');
  let courseId = b.course_id ?? null;
  if (courseId !== null) {
    courseId = Number(courseId);
    const owned = await env.DB.prepare('SELECT 1 FROM courses WHERE id = ? AND user_id = ?').bind(courseId, user.id).first();
    if (!owned) throw new HttpError(400, 'Unknown course.');
  }
  const dueAt = dueInstant(b.due_date, dueTime, b.tz);
  if (!Number.isFinite(dueAt)) throw new HttpError(400, 'Invalid due date.');
  return {
    title, notes, course_id: courseId, due_date: b.due_date, due_time: dueTime, tz: b.tz,
    due_at: dueAt, remind_minutes: remind, done: b.done ? 1 : 0,
  };
}

export async function courseName(env, user, courseId) {
  if (courseId == null) return null;
  return (await env.DB.prepare('SELECT name FROM courses WHERE id = ? AND user_id = ?').bind(courseId, user.id).first())?.name ?? null;
}

export async function createHomework(env, user, body, appUrl) {
  const input = await homeworkInput(env, user, body);
  const now = Date.now();
  const hw = await env.DB.prepare(`INSERT INTO homework (user_id, course_id, title, notes, due_date, due_time, tz, due_at,
      remind_minutes, done, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
    RETURNING ${HOMEWORK_COLUMNS}`)
    .bind(user.id, input.course_id, input.title, input.notes, input.due_date, input.due_time, input.tz, input.due_at,
      input.remind_minutes, now, now).first();
  return syncHomework(env, user.id, hw, await courseName(env, user, hw.course_id), appUrl);
}

// Partial update: fields in `body` are merged over the stored row.
export async function updateHomework(env, user, id, body, appUrl) {
  const current = await env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE id = ? AND user_id = ?`).bind(id, user.id).first();
  if (!current) throw new HttpError(404, 'Homework not found.');
  const input = await homeworkInput(env, user, { ...current, ...body, done: body.done ?? current.done });
  const hw = await env.DB.prepare(`UPDATE homework SET course_id = ?, title = ?, notes = ?, due_date = ?, due_time = ?, tz = ?,
      due_at = ?, remind_minutes = ?, done = ?, updated_at = ? WHERE id = ? AND user_id = ? RETURNING ${HOMEWORK_COLUMNS}`)
    .bind(input.course_id, input.title, input.notes, input.due_date, input.due_time, input.tz, input.due_at,
      input.remind_minutes, input.done, Date.now(), id, user.id).first();
  return syncHomework(env, user.id, hw, await courseName(env, user, hw.course_id), appUrl);
}
