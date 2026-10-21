// Catch-ups: "rewatch the session you missed" items. They are homework rows (kind 'catchup'), so they share the
// homework list, reminders, Google Calendar sync and feeds. One per session; asking again returns the same one.
import { HttpError } from './http.js';
import { HOMEWORK_COLUMNS, courseName } from './homework-store.js';
import { isValidTimeZone, dueInstant } from './homework.js';
import { syncHomework } from './gcal.js';

const DAY = 86400000;

const pad = (n) => String(n).padStart(2, '0');
function local(ms, tz) {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  return { date: `${p.year}-${p.month}-${p.day}`, time: `${pad(p.hour)}:${p.minute}` };
}

// Due when the course meets next (so you're caught up before it), or a week after the missed session.
export async function createCatchup(env, user, body, appUrl, now = Date.now()) {
  const eventId = Number(body.event_id);
  const tz = isValidTimeZone(body.tz) ? body.tz : 'Europe/Paris';
  const ev = await env.DB.prepare(`SELECT e.id, e.course_id, e.summary, e.location, e.start_at, e.end_at, c.recording_url
    FROM events e JOIN courses c ON c.id = e.course_id WHERE e.id = ? AND e.user_id = ?`).bind(eventId, user.id).first();
  if (!ev) throw new HttpError(404, 'Session not found.');
  const existing = await env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE user_id = ? AND kind = 'catchup' AND event_id = ?`)
    .bind(user.id, eventId).first();
  if (existing) return existing;

  const next = await env.DB.prepare(`SELECT start_at FROM events WHERE user_id = ? AND course_id = ? AND start_at > ?
    ORDER BY start_at LIMIT 1`).bind(user.id, ev.course_id, Math.max(ev.end_at, now)).first();
  const due = next ? local(next.start_at, tz) : local(ev.end_at + 7 * DAY, tz);
  const when = local(ev.start_at, tz);
  const notes = [ev.recording_url && `Recording: ${ev.recording_url}`,
    `Missed session: ${when.date} ${when.time}${ev.location ? `, ${ev.location}` : ''}`].filter(Boolean).join('\n');
  const t = Date.now();
  const hw = await env.DB.prepare(`INSERT INTO homework (user_id, course_id, title, notes, due_date, due_time, tz, due_at,
      remind_minutes, done, created_at, updated_at, kind, event_id, effort_minutes)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, ?, ?, 'catchup', ?, ?)
    ON CONFLICT DO NOTHING RETURNING ${HOMEWORK_COLUMNS}`)
    .bind(user.id, ev.course_id, `Catch up: ${ev.summary}`.slice(0, 200), notes, due.date, next ? due.time : null, tz,
      next ? next.start_at : dueInstant(due.date, null, tz), t, t, eventId, Math.max(15, Math.round((ev.end_at - ev.start_at) / 60000))).first();
  // Lost a race with another request for the same session: return that one.
  if (!hw) return env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE user_id = ? AND kind = 'catchup' AND event_id = ?`)
    .bind(user.id, eventId).first();
  return syncHomework(env, user.id, hw, await courseName(env, user, hw.course_id), appUrl);
}

// A course's recording link (Panopto, Moodle, YouTube…), copied into its new catch-ups. Empty removes it.
export async function setRecordingUrl(env, user, courseId, value) {
  let url = null;
  if (value != null && String(value).trim()) {
    try { url = new URL(String(value).trim()); } catch { url = null; }
    if (!url || !['https:', 'http:'].includes(url.protocol) || String(value).length > 2000) {
      throw new HttpError(400, 'Paste the address of the recordings page (https://…).');
    }
    url = url.toString();
  }
  const r = await env.DB.prepare('UPDATE courses SET recording_url = ? WHERE id = ? AND user_id = ?').bind(url, courseId, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, 'Course not found.');
  return { recording_url: url };
}
