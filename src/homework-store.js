// Homework persistence shared by the REST API and the AI assistant.
import { HttpError } from './http.js';
import { REMIND_OPTIONS, isValidTimeZone, dueInstant } from './homework.js';
import { syncHomework } from './gcal.js';

export const HOMEWORK_COLUMNS = `id, course_id, title, notes, due_date, due_time, tz, due_at, remind_minutes, done,
  gcal_event_id, sync_error, created_at, updated_at, kind, event_id, effort_minutes, planned_at, planned_minutes`;
const MAX_EFFORT = 24 * 60;
const MAX_PLANNED = 12 * 60;
const PLAN_FIELDS = ['planned_at', 'planned_minutes'];

// The time block on the Calendar page: { planned_at, planned_minutes }, both null when the item isn't planned.
export function planInput(b) {
  const at = b.planned_at ?? null;
  if (at !== null && !(Number.isSafeInteger(at) && at > 0)) throw new HttpError(400, 'Invalid planned time.');
  const minutes = at === null ? null : b.planned_minutes ?? null;
  if (minutes !== null && !(Number.isInteger(minutes) && minutes >= 5 && minutes <= MAX_PLANNED)) {
    throw new HttpError(400, 'A planned block is 5 minutes to 12 hours long.');
  }
  return { planned_at: at, planned_minutes: minutes };
}

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
  const effort = b.effort_minutes ?? null;
  if (effort !== null && !(Number.isInteger(effort) && effort > 0 && effort <= MAX_EFFORT)) {
    throw new HttpError(400, 'Effort is a number of minutes (24 h at most).');
  }
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
    due_at: dueAt, remind_minutes: remind, done: b.done ? 1 : 0, effort_minutes: effort, ...planInput(b),
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
      remind_minutes, done, created_at, updated_at, effort_minutes, planned_at, planned_minutes)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?) RETURNING ${HOMEWORK_COLUMNS}`)
    .bind(user.id, input.course_id, input.title, input.notes, input.due_date, input.due_time, input.tz, input.due_at,
      input.remind_minutes, now, now, input.effort_minutes, input.planned_at, input.planned_minutes).first();
  return syncHomework(env, user.id, hw, await courseName(env, user, hw.course_id), appUrl);
}

// Partial update: fields in `body` are merged over the stored row.
export async function updateHomework(env, user, id, body, appUrl) {
  const current = await env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE id = ? AND user_id = ?`).bind(id, user.id).first();
  if (!current) throw new HttpError(404, 'Homework not found.');
  const input = await homeworkInput(env, user, { ...current, ...body, done: body.done ?? current.done });
  const hw = await env.DB.prepare(`UPDATE homework SET course_id = ?, title = ?, notes = ?, due_date = ?, due_time = ?, tz = ?,
      due_at = ?, remind_minutes = ?, done = ?, effort_minutes = ?, planned_at = ?, planned_minutes = ?, updated_at = ?
    WHERE id = ? AND user_id = ? RETURNING ${HOMEWORK_COLUMNS}`)
    .bind(input.course_id, input.title, input.notes, input.due_date, input.due_time, input.tz, input.due_at,
      input.remind_minutes, input.done, input.effort_minutes, input.planned_at, input.planned_minutes, Date.now(), id, user.id).first();
  // Moving a time block changes nothing Google Calendar shows (the deadline), so it isn't pushed again.
  if (Object.keys(body).every((k) => PLAN_FIELDS.includes(k))) return hw;
  return syncHomework(env, user.id, hw, await courseName(env, user, hw.course_id), appUrl);
}

// Several time blocks at once (the Calendar's "Plan my week"): items [{ id, planned_at, planned_minutes }].
export async function planHomework(env, user, body) {
  const items = Array.isArray(body.items) ? body.items : null;
  if (!items || !items.length || items.length > 50) throw new HttpError(400, 'items: 1 to 50 { id, planned_at, planned_minutes }.');
  const now = Date.now();
  const rows = items.map((it) => {
    if (!Number.isSafeInteger(it?.id)) throw new HttpError(400, 'Each item needs an id.');
    return { id: it.id, ...planInput(it) };
  });
  const out = await env.DB.batch(rows.map((r) => env.DB.prepare(`UPDATE homework SET planned_at = ?, planned_minutes = ?, updated_at = ?
    WHERE id = ? AND user_id = ? RETURNING ${HOMEWORK_COLUMNS}`).bind(r.planned_at, r.planned_minutes, now, r.id, user.id)));
  return { homework: out.flatMap((r) => r.results ?? []) };
}
