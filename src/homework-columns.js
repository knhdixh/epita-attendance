// The homework columns every task query returns, and how a stored row is shown by the API.
import { NO_DATE } from './homework.js';

export const HOMEWORK_COLUMNS = `id, course_id, title, notes, due_date, due_time, tz, due_at, remind_minutes, done,
  gcal_event_id, sync_error, created_at, updated_at, kind, event_id, effort_minutes, planned_at, planned_minutes,
  project_id, section_id, parent_id, priority, position, recurrence, deadline_date, completed_at, client_id`;
export const CLIENT_ID = /^[A-Za-z0-9_-]{8,64}$/;

// A row as the API shows it: a task without a date has null for its due date and time.
export const taskRow = (r) => r && (r.due_date === NO_DATE ? { ...r, due_date: null, due_time: null, due_at: null } : r);

