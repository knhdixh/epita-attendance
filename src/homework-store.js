// Homework persistence, used by the REST API and catch-ups.
import { HttpError } from './http.js';
import { REMIND_OPTIONS, NO_DATE, isValidTimeZone, dueInstant } from './homework.js';
import { syncHomework, deleteGcalEvent } from './gcal.js';
import { resolvePlacement, nextPosition, moveTasks, withDescendants } from './placement.js';
import { HOMEWORK_COLUMNS, CLIENT_ID, taskRow } from './homework-columns.js';

export { HOMEWORK_COLUMNS, taskRow };

const MAX_EFFORT = 24 * 60;
const MAX_PLANNED = 12 * 60;
const PLAN_FIELDS = ['planned_at', 'planned_minutes'];
const PLACEMENT_FIELDS = ['project_id', 'section_id', 'parent_id', 'position'];
const MAX_RECURRENCE = 200;

const realDate = (d) => /^\d{4}-\d{2}-\d{2}$/.test(d || '') && !Number.isNaN(Date.parse(d + 'T00:00:00Z'))
  && new Date(d + 'T00:00:00Z').toISOString().startsWith(d); // rejects e.g. 2026-13-45 and 2026-02-30

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
  // An explicit null (or the stored placeholder) means "no date"; a missing due date is still an error.
  const undated = b.due_date === null || b.due_date === NO_DATE;
  const dueDate = undated ? NO_DATE : b.due_date;
  if (!undated && !realDate(dueDate)) throw new HttpError(400, 'Invalid due date.');
  const dueTime = undated ? null : b.due_time || null;
  if (dueTime !== null && !/^([01]\d|2[0-3]):[0-5]\d$/.test(dueTime)) throw new HttpError(400, 'Invalid due time.');
  const tz = undated && !b.tz ? 'UTC' : b.tz;
  if (!isValidTimeZone(tz)) throw new HttpError(400, 'Invalid time zone.');
  const remind = undated ? null : b.remind_minutes ?? null;
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
  const priority = b.priority ?? 1;
  if (!(Number.isInteger(priority) && priority >= 1 && priority <= 4)) throw new HttpError(400, 'Priority is 1 to 4 (4 is the most urgent).');
  const deadline = b.deadline_date ?? null;
  if (deadline !== null && !realDate(deadline)) throw new HttpError(400, 'Invalid deadline.');
  const recurrence = typeof b.recurrence === 'string' && b.recurrence.trim() ? b.recurrence.trim() : null;
  if (b.recurrence != null && typeof b.recurrence !== 'string') throw new HttpError(400, 'Invalid repeat.');
  if (recurrence && recurrence.length > MAX_RECURRENCE) throw new HttpError(400, 'That repeat is too long.');
  if (recurrence && undated) throw new HttpError(400, 'A repeating task needs a date.');
  const dueAt = dueInstant(dueDate, dueTime, tz);
  if (!Number.isFinite(dueAt)) throw new HttpError(400, 'Invalid due date.');
  return {
    title, notes, course_id: courseId, due_date: dueDate, due_time: dueTime, tz,
    due_at: dueAt, remind_minutes: remind, done: b.done ? 1 : 0, effort_minutes: effort, ...planInput(b),
    priority, deadline_date: deadline, recurrence,
  };
}

// Label ids for a task, or null when the request doesn't mention labels. They must be the user's own.
export async function labelIds(env, user, b) {
  if (b.label_ids === undefined) return null;
  if (!Array.isArray(b.label_ids) || b.label_ids.length > 50 || !b.label_ids.every(Number.isSafeInteger)) throw new HttpError(400, 'label_ids must be a list of label ids.');
  const ids = [...new Set(b.label_ids)];
  if (ids.length) {
    const { n } = await env.DB.prepare(`SELECT COUNT(*) AS n FROM labels WHERE user_id = ? AND id IN (${ids.map(() => '?').join(', ')})`).bind(user.id, ...ids).first();
    if (n !== ids.length) throw new HttpError(400, 'Unknown label.');
  }
  return ids;
}

const setLabels = (env, hwId, ids) => [
  env.DB.prepare('DELETE FROM task_labels WHERE homework_id = ?').bind(hwId),
  ...ids.map((l) => env.DB.prepare('INSERT INTO task_labels (homework_id, label_id) VALUES (?, ?)').bind(hwId, l)),
];

export async function courseName(env, user, courseId) {
  if (courseId == null) return null;
  return (await env.DB.prepare('SELECT name FROM courses WHERE id = ? AND user_id = ?').bind(courseId, user.id).first())?.name ?? null;
}

// Google Calendar knows dated tasks only. A task that lost its date loses its event too.
export async function syncTask(env, user, hw, appUrl) {
  if (hw.due_date === NO_DATE) {
    if (hw.gcal_event_id) {
      await deleteGcalEvent(env, user.id, hw.gcal_event_id);
      await env.DB.prepare('UPDATE homework SET gcal_event_id = NULL, sync_error = NULL WHERE id = ? AND user_id = ?').bind(hw.id, user.id).run();
      return { ...hw, gcal_event_id: null, sync_error: null };
    }
    return hw;
  }
  return syncHomework(env, user.id, hw, await courseName(env, user, hw.course_id), appUrl);
}

// New tasks accept `client_id`: sending the same one again returns the task made the first time.
export async function createHomework(env, user, body, appUrl) {
  const clientId = body.client_id ?? null;
  if (clientId !== null && !(typeof clientId === 'string' && CLIENT_ID.test(clientId))) throw new HttpError(400, 'Invalid client_id.');
  if (clientId) {
    const again = await env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE user_id = ? AND client_id = ?`).bind(user.id, clientId).first();
    if (again) return again;
  }
  const input = await homeworkInput(env, user, body);
  const place = await resolvePlacement(env, user, body);
  const labels = await labelIds(env, user, body);
  const position = Number.isInteger(body.position) && body.position >= 0 ? body.position : await nextPosition(env, user, place);
  const now = Date.now();
  const hw = await env.DB.prepare(`INSERT INTO homework (user_id, course_id, title, notes, due_date, due_time, tz, due_at,
      remind_minutes, done, created_at, updated_at, effort_minutes, planned_at, planned_minutes,
      project_id, section_id, parent_id, priority, position, recurrence, deadline_date, client_id)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING ${HOMEWORK_COLUMNS}`)
    .bind(user.id, input.course_id, input.title, input.notes, input.due_date, input.due_time, input.tz, input.due_at,
      input.remind_minutes, now, now, input.effort_minutes, input.planned_at, input.planned_minutes,
      place.project_id, place.section_id, place.parent_id, input.priority, position, input.recurrence, input.deadline_date, clientId).first();
  if (labels?.length) await env.DB.batch(setLabels(env, hw.id, labels));
  return syncTask(env, user, hw, appUrl);
}

// Partial update: fields in `body` are merged over the stored row. project_id, section_id, parent_id and position
// go through the same code as POST /api/homework/move.
export async function updateHomework(env, user, id, body, appUrl) {
  const current = await env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE id = ? AND user_id = ?`).bind(id, user.id).first();
  if (!current) throw new HttpError(404, 'Homework not found.');
  if (body.done && !current.done && current.recurrence) return completeTask(env, user, id, appUrl);
  const input = await homeworkInput(env, user, { ...current, ...body, done: body.done ?? current.done });
  const labels = await labelIds(env, user, body);
  const now = Date.now();
  const completedAt = input.done ? (current.done ? current.completed_at : now) : null;
  const stmts = [env.DB.prepare(`UPDATE homework SET course_id = ?, title = ?, notes = ?, due_date = ?, due_time = ?, tz = ?,
      due_at = ?, remind_minutes = ?, done = ?, effort_minutes = ?, planned_at = ?, planned_minutes = ?, priority = ?,
      deadline_date = ?, recurrence = ?, completed_at = ?, updated_at = ?
    WHERE id = ? AND user_id = ? RETURNING ${HOMEWORK_COLUMNS}`)
    .bind(input.course_id, input.title, input.notes, input.due_date, input.due_time, input.tz, input.due_at,
      input.remind_minutes, input.done, input.effort_minutes, input.planned_at, input.planned_minutes, input.priority,
      input.deadline_date, input.recurrence, completedAt, now, id, user.id)];
  if (labels) stmts.push(...setLabels(env, id, labels));
  let hw = (await env.DB.batch(stmts))[0].results[0];
  if (PLACEMENT_FIELDS.some((k) => k in body)) {
    const moved = await moveTasks(env, user, { ids: [id], ...Object.fromEntries(PLACEMENT_FIELDS.filter((k) => k in body).map((k) => [k, body[k]])) });
    hw = moved.homework[0];
  }
  // Moving a time block or a task between projects changes nothing Google Calendar shows, so it isn't pushed again.
  if (Object.keys(body).every((k) => PLAN_FIELDS.includes(k) || PLACEMENT_FIELDS.includes(k) || k === 'label_ids')) return hw;
  return syncTask(env, user, hw, appUrl);
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

// Completing a task completes the open tasks below it too. (Repeating tasks move to their next date instead:
// see completeRecurring.)
export async function completeTask(env, user, id, appUrl, now = Date.now()) {
  const current = await env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE id = ? AND user_id = ?`).bind(id, user.id).first();
  if (!current) throw new HttpError(404, 'Task not found.');
  if (current.done) return current;
  const ids = await withDescendants(env, user, [id]);
  const rows = (await env.DB.prepare(`UPDATE homework SET done = 1, completed_at = ?, updated_at = ?
    WHERE user_id = ? AND done = 0 AND id IN (${ids.map(() => '?').join(', ')}) RETURNING ${HOMEWORK_COLUMNS}`)
    .bind(now, now, user.id, ...ids).all()).results;
  let out = null;
  for (const hw of rows) {
    const synced = await syncTask(env, user, hw, appUrl);
    if (hw.id === id) out = synced;
  }
  return out;
}

export async function uncompleteTask(env, user, id, appUrl) {
  const current = await env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE id = ? AND user_id = ?`).bind(id, user.id).first();
  if (!current) throw new HttpError(404, 'Task not found.');
  if (!current.done) return current;
  const hw = await env.DB.prepare(`UPDATE homework SET done = 0, completed_at = NULL, updated_at = ? WHERE id = ? AND user_id = ?
    RETURNING ${HOMEWORK_COLUMNS}`).bind(Date.now(), id, user.id).first();
  return syncTask(env, user, hw, appUrl);
}

// Deleting a task deletes its subtasks (the foreign key), so their Google Calendar events go too.
export async function deleteTask(env, user, id) {
  const ids = await withDescendants(env, user, [id]);
  if (!ids.length) throw new HttpError(404, 'Homework not found.');
  const events = (await env.DB.prepare(`SELECT gcal_event_id FROM homework WHERE user_id = ? AND gcal_event_id IS NOT NULL
    AND id IN (${ids.map(() => '?').join(', ')})`).bind(user.id, ...ids).all()).results;
  await env.DB.prepare('DELETE FROM homework WHERE id = ? AND user_id = ?').bind(id, user.id).run();
  for (const e of events) await deleteGcalEvent(env, user.id, e.gcal_event_id);
  return { ok: true };
}
