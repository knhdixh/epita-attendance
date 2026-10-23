// A user's calendars: links (Zeus or any .ics URL) that the cron re-imports, and uploaded .ics files.
// A session (matched by UID) is stored once per user and can be in several calendars, e.g. a lecture shared by
// groups SE1, SE2 and SE3: event_sources records which calendars contain it. Importing a calendar only updates
// its own memberships; a session is deleted once no calendar contains it (with the exceptions in importIcs).
import { parseICS } from './ics.js';
import { splitGroup } from '../public/stats.js';
import { HttpError } from './http.js';
import { parseCalendarLink, fetchCalendar, validDate } from './calendar-link.js';

export const MAX_ICS_BYTES = 5 * 1024 * 1024;
export const MAX_CALENDARS = 10;
const NAME_MAX = 60;
const NON_CLASS = /\b(vacances|holidays?|jour f[ée]ri[ée]|f[ée]ri[ée]|cong[ée]s?)\b/i;
// The cron runs every minute and refreshes ONE link per run: a cold import of a semester calendar costs
// ~10 ms of CPU, the free plan's per-invocation limit. Links are refreshed every hour (a little less, so one
// that ran a few minutes late isn't pushed back a whole cycle); a failed one waits 6 hours, so a revoked link
// isn't hammered. That's 60 links an hour, so up to ~55 links stay on the hourly cycle, and beyond that it
// stretches evenly.
const SYNC_EVERY_MS = 55 * 60 * 1000;
const RETRY_FAILED_MS = 6 * 3600 * 1000;
const SYNC_BATCH = 1;
const SYNC_LOG_KEEP = 50;
const CRON_LOG_KEEP = 200;

export const courseKey = (summary) => summary.toLowerCase().replace(/\s+/g, ' ').trim();

// Sisu (Tampere University and other Finnish universities) titles each session
// "COMP.CS.410, Operating Systems and Concurrency, Lectures 24.8.–5.12.2026 - Common - Lecture: Luennot 2 - TAU Festia …":
// code, course name, teaching implementation, then " - "-separated parts, one of them the kind ("Lecture:",
// "Group 1: Viikkoharjoitukset"). Older entries are just "DATA.DB.100 Q&A". The course is the code, so every
// lecture, exercise group and year of a course lands in one course.
export const isSisu = (text) => /^PRODID:[^\r\n]*SISU/im.test(text.slice(0, 2000));
const SISU_TITLE = /^(?:CANCELLED! )?([A-Z][A-Z0-9-]*(?:\.[A-Z0-9-]+)*\.\d[\dA-Z-]*)(?:, ([^,]+), (.*)| (.*))?$/;

export function sisuSession(summary) {
  const m = SISU_TITLE.exec(summary);
  if (!m) return null;
  const [, code, name, rest, short] = m;
  const kind = rest
    ? (rest.split(' - ').find((p) => /^[^:]{1,40}:/.test(p)) || '').replace(/:\s*$/, '').replace(/:\s+/, ': ').trim()
    : (short || '').trim();
  const course = name?.trim() || code;
  return { key: `sisu:${code.toLowerCase()}`, course, summary: kind ? `${course} · ${kind}` : course };
}

// Calendars that don't count attendance only need recent sessions (for catch-ups); older ones are dropped.
const NO_ATTENDANCE_HISTORY_DAYS = 60;
const ymd = (ms) => new Date(ms).toISOString().slice(0, 10).replace(/-/g, '');
const sha256 = async (text) => btoa(String.fromCharCode(...new Uint8Array(
  await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))));

// ---------------------------------------------------------------- import

// Imports (or re-imports) one calendar's .ics text. Sessions are matched by UID, so marks survive an updated
// calendar. A session this calendar no longer has leaves it, and is deleted if no other calendar has it, except:
// - past ones the user marked, tagged or annotated (they're the attendance record);
// - future ones with a note. A future session that was only marked (e.g. a planned skip) or tagged is removed,
//   since a cancelled class must not keep counting as an absence.
// A kept session stays in this calendar, so it still shows (and counts) under its group.
// Courses a calendar creates take its attendance setting.
export async function importIcs(env, userId, sourceId, text, now = Date.now()) {
  const src = await env.DB.prepare('SELECT attendance, import_hash FROM calendar_sources WHERE id = ?').bind(sourceId).first();
  const attendance = src ? src.attendance : 1;
  const sisu = isSisu(text);
  const { events, recurring } = parseICS(text, { since: attendance ? null : ymd(now - NO_ATTENDANCE_HISTORY_DAYS * 86400000) });
  const seen = new Map();
  let cancelled = 0;
  for (const ev of events) {
    if (ev.cancelled) { cancelled++; continue; }
    if (!seen.has(ev.uid)) seen.set(ev.uid, ev);
  }
  if (!seen.size) throw new HttpError(400, 'No events found in this calendar.');

  // New courses start untracked when they're holidays, another group's sessions (group-split courses), or only
  // all-day events (public holidays, term dates or birthdays from a general calendar; classes have times).
  const courses = new Map();
  const rows = [];
  for (const ev of seen.values()) {
    const s = (sisu && sisuSession(ev.summary)) || { key: courseKey(ev.summary), course: ev.summary, summary: ev.summary };
    const { key } = s;
    if (!courses.has(key)) {
      const tracked = NON_CLASS.test(s.course) || (!sisu && splitGroup(s.course)) ? 0 : 1;
      courses.set(key, { key, name: s.course, tracked, attendance, allDayOnly: true });
    }
    if (!ev.allDay) courses.get(key).allDayOnly = false;
    rows.push({ key, uid: ev.uid, summary: s.summary, location: ev.location,
      description: ev.description, start: ev.start, end: ev.end });
  }

  const coursesJson = JSON.stringify([...courses.values()].map((c) => ({ ...c, tracked: c.allDayOnly ? 0 : c.tracked })));
  const rowsJson = JSON.stringify(rows);
  // Same sessions as the last import today: nothing to write (see migrations/0012). The day is part of the hash so
  // sessions kept only for a mark or note are still reconsidered daily once the mark goes.
  const hash = await sha256(`${ymd(now)}\n${coursesJson}\n${rowsJson}`);
  if (src?.import_hash === hash) {
    return { total: rows.length, added: 0, updated: 0, removed: 0, cancelled, recurringIgnored: recurring, unchanged: true };
  }

  const existing = await env.DB.prepare('SELECT uid FROM events WHERE user_id = ?').bind(userId).all();
  const existingUids = new Set(existing.results.map((r) => r.uid));
  // Imports are told apart by this stamp; make it unique even for two imports in the same millisecond.
  const stamp = now * 1000 + Math.floor(Math.random() * 1000);

  // json_each keeps this to a handful of queries regardless of calendar size. CROSS JOIN keeps json_each the outer
  // loop: otherwise SQLite scans all the user's events and rescans the JSON for each (events x rows reads per sync).
  const results = await env.DB.batch([
    env.DB.prepare(`INSERT INTO courses (user_id, key, name, tracked, tag, attendance)
      SELECT ?1, json_extract(value, '$.key'), json_extract(value, '$.name'), json_extract(value, '$.tracked'), 'normal',
        json_extract(value, '$.attendance')
      FROM json_each(?2) WHERE true
      ON CONFLICT (user_id, key) DO NOTHING`)
      .bind(userId, coursesJson),
    env.DB.prepare(`INSERT INTO events (user_id, course_id, uid, summary, location, description, start_at, end_at)
      SELECT ?1, c.id, json_extract(e.value, '$.uid'), json_extract(e.value, '$.summary'),
        json_extract(e.value, '$.location'), json_extract(e.value, '$.description'),
        json_extract(e.value, '$.start'), json_extract(e.value, '$.end')
      FROM json_each(?2) e CROSS JOIN courses c ON c.user_id = ?1 AND c.key = json_extract(e.value, '$.key')
      WHERE true
      ON CONFLICT (user_id, uid) DO UPDATE SET course_id = excluded.course_id,
        summary = excluded.summary, location = excluded.location, description = excluded.description,
        start_at = excluded.start_at, end_at = excluded.end_at`).bind(userId, rowsJson),
    env.DB.prepare(`INSERT INTO event_sources (event_id, source_id, seen_at)
      SELECT ev.id, ?3, ?4 FROM json_each(?2) j CROSS JOIN events ev ON ev.user_id = ?1 AND ev.uid = json_extract(j.value, '$.uid')
      WHERE true
      ON CONFLICT (event_id, source_id) DO UPDATE SET seen_at = excluded.seen_at`)
      .bind(userId, JSON.stringify(rows.map((r) => ({ uid: r.uid }))), sourceId, stamp),
    // Leave this calendar: sessions another calendar still has, and sessions that may go (see above).
    env.DB.prepare(`DELETE FROM event_sources WHERE source_id = ?1 AND seen_at <> ?2 AND (
        EXISTS (SELECT 1 FROM event_sources o WHERE o.event_id = event_sources.event_id AND o.source_id <> ?1)
        OR event_id IN (SELECT id FROM events WHERE user_id = ?3 AND note = '' AND (start_at > ?4 OR (status IS NULL AND tag IS NULL))))`)
      .bind(sourceId, stamp, userId, now),
    deleteOrphans(env, userId),
    pruneCourses(env, userId),
    env.DB.prepare('UPDATE calendar_sources SET import_hash = ? WHERE id = ?').bind(hash, sourceId),
  ]);

  const added = rows.filter((r) => !existingUids.has(r.uid)).length;
  return {
    total: rows.length, added, updated: rows.length - added,
    removed: results[4].meta.changes, cancelled, recurringIgnored: recurring,
  };
}

// Sessions no calendar contains any more (after an import, or removing a calendar), with their marks.
const deleteOrphans = (env, userId) => env.DB.prepare(`DELETE FROM events WHERE user_id = ?
  AND NOT EXISTS (SELECT 1 FROM event_sources WHERE event_id = events.id)`).bind(userId);

// Courses left without sessions (after an import or removing a calendar). Their homework keeps its title
// and loses the course link (ON DELETE SET NULL).
const pruneCourses = (env, userId) => env.DB.prepare(`DELETE FROM courses WHERE user_id = ?
  AND NOT EXISTS (SELECT 1 FROM events WHERE events.course_id = courses.id)`).bind(userId);

// Runs an import for one calendar and records it in calendar_syncs whatever happens.
// `trigger` is connect | manual | cron | upload.
async function loggedImport(env, userId, sourceId, trigger, load) {
  const started = Date.now();
  let status = null, result = null, error = null;
  try {
    const { text, status: s } = await load();
    status = s;
    result = await importIcs(env, userId, sourceId, text);
    return result;
  } catch (e) {
    status = e.upstream ?? status;
    error = e instanceof HttpError ? e : new HttpError(502, 'Couldn\'t update the calendar. It will be retried automatically.');
    if (!(e instanceof HttpError)) console.error(e);
    throw error;
  } finally {
    // The calendar may have been removed meanwhile; then there is nothing to log against.
    await env.DB.batch([
      env.DB.prepare(`INSERT INTO calendar_syncs (user_id, source_id, at, source, ok, http_status, duration_ms, sessions, added, removed, error)
        SELECT ?, id, ?, ?, ?, ?, ?, ?, ?, ?, ? FROM calendar_sources WHERE id = ?`)
        .bind(userId, started, trigger, error ? 0 : 1, status, Date.now() - started,
          result?.total ?? null, result?.added ?? null, result?.removed ?? null, error?.message ?? null, sourceId),
      env.DB.prepare(`DELETE FROM calendar_syncs WHERE user_id = ?1 AND id NOT IN
        (SELECT id FROM calendar_syncs WHERE user_id = ?1 ORDER BY id DESC LIMIT ?2)`).bind(userId, SYNC_LOG_KEEP),
    ]);
  }
}

// ---------------------------------------------------------------- calendars

const SOURCE_COLUMNS = 'id, user_id, kind, name, url, from_date, file_name, synced_at, ok_at, error, created_at, attendance';

async function ownSource(env, user, id) {
  const s = await env.DB.prepare(`SELECT ${SOURCE_COLUMNS} FROM calendar_sources WHERE id = ? AND user_id = ?`).bind(id, user.id).first();
  if (!s) throw new HttpError(404, 'Calendar not found.');
  return s;
}

function cleanName(name, fallback) {
  const n = typeof name === 'string' ? name.trim().replace(/\s+/g, ' ') : '';
  if (n.length > NAME_MAX) throw new HttpError(400, `Names are ${NAME_MAX} characters at most.`);
  return n || fallback;
}

// Where a link points, without its private token: "zeus.ionis-it.com · group 1234" or the host.
function linkSite(url) {
  const u = new URL(url);
  const m = u.pathname.match(/^\/api\/(group|teacher|room)\/(\d+)\//);
  return m ? `${u.hostname} · ${m[1]} ${m[2]}` : u.hostname;
}

async function roomFor(env, user) {
  const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM calendar_sources WHERE user_id = ?').bind(user.id).first();
  if (n >= MAX_CALENDARS) throw new HttpError(400, `You can add up to ${MAX_CALENDARS} calendars. Remove one first.`);
}

// What the app shows: never the link itself, since it holds a private token.
export async function listCalendars(env, userId) {
  const [sources, counts] = await env.DB.batch([
    env.DB.prepare(`SELECT ${SOURCE_COLUMNS} FROM calendar_sources WHERE user_id = ? ORDER BY created_at, id`).bind(userId),
    env.DB.prepare(`SELECT es.source_id, COUNT(*) AS n FROM event_sources es JOIN calendar_sources s ON s.id = es.source_id
      WHERE s.user_id = ? GROUP BY es.source_id`).bind(userId),
  ]);
  const n = new Map(counts.results.map((r) => [r.source_id, r.n]));
  return sources.results.map((s) => ({
    id: s.id, kind: s.kind, name: s.name, site: s.url ? linkSite(s.url) : null, from: s.from_date, fileName: s.file_name,
    checkedAt: s.synced_at, okAt: s.ok_at, error: s.error, sessions: n.get(s.id) || 0, createdAt: s.created_at,
    attendance: Boolean(s.attendance),
  }));
}

// Paris date of the user's first imported class, so a new Zeus link doesn't drop past sessions.
async function firstClassDate(env, userId) {
  const r = await env.DB.prepare('SELECT MIN(start_at) AS t FROM events WHERE user_id = ?').bind(userId).first();
  return r?.t == null ? null : new Date(r.t).toLocaleDateString('en-CA', { timeZone: 'Europe/Paris' });
}

// Adds a link and imports it right away, so a wrong link is reported now instead of failing silently later.
export async function addLink(env, user, body) {
  const link = parseCalendarLink(body.url);
  if (body.from != null && body.from !== '' && !validDate(body.from)) throw new HttpError(400, 'Invalid start date.');
  const name = cleanName(body.name, link.name);
  await roomFor(env, user);
  if (await env.DB.prepare('SELECT 1 FROM calendar_sources WHERE user_id = ? AND url = ?').bind(user.id, link.url).first()) {
    throw new HttpError(409, 'This calendar is already added.');
  }
  const from = body.from || link.from || (link.zeus ? await firstClassDate(env, user.id) : null);
  const attendance = attendanceFor(body, link.sisu);
  const now = Date.now();
  const { id } = await env.DB.prepare(`INSERT INTO calendar_sources (user_id, kind, name, url, from_date, synced_at, created_at, attendance)
    VALUES (?, 'link', ?, ?, ?, ?, ?, ?) RETURNING id`).bind(user.id, name, link.url, from, now, now, attendance).first();
  try {
    const result = await loggedImport(env, user.id, id, 'connect', () => fetchCalendar(link.url, from, MAX_ICS_BYTES));
    await env.DB.prepare('UPDATE calendar_sources SET ok_at = ? WHERE id = ?').bind(Date.now(), id).run();
    return { calendar: id, ...result };
  } catch (e) {
    await removeById(env, user.id, id);
    throw e;
  }
}

// Attendance counts unless the request says otherwise, or the calendar is a Sisu one (Finnish universities don't
// take attendance the way EPITA does).
function attendanceFor(body, sisu) {
  if (typeof body.attendance === 'boolean') return body.attendance ? 1 : 0;
  return sisu ? 0 : 1;
}

function icsText(body) {
  if (typeof body.ics !== 'string') throw new HttpError(400, 'Missing "ics" text.');
  if (!body.ics.includes('BEGIN:VCALENDAR')) throw new HttpError(400, 'That does not look like an .ics calendar file.');
  return body.ics;
}
const fileName = (body) => (typeof body.fileName === 'string' ? body.fileName.trim().slice(0, 200) : '') || null;

// Adds an uploaded .ics file as a new calendar.
export async function addFile(env, user, body) {
  const text = icsText(body);
  const file = fileName(body);
  const name = cleanName(body.name, file?.replace(/\.ics$/i, '') || 'Calendar');
  await roomFor(env, user);
  const now = Date.now();
  const { id } = await env.DB.prepare(`INSERT INTO calendar_sources (user_id, kind, name, file_name, created_at, attendance)
    VALUES (?, 'file', ?, ?, ?, ?) RETURNING id`).bind(user.id, name, file, now, attendanceFor(body, isSisu(text))).first();
  try {
    const result = await loggedImport(env, user.id, id, 'upload', async () => ({ text, status: null }));
    await env.DB.prepare('UPDATE calendar_sources SET ok_at = ? WHERE id = ?').bind(Date.now(), id).run();
    return { calendar: id, ...result };
  } catch (e) {
    await removeById(env, user.id, id);
    throw e;
  }
}

// Uploads a newer version of a file calendar. Sessions are matched by UID, so marks are kept.
export async function replaceFile(env, user, id, body) {
  const s = await ownSource(env, user, id);
  if (s.kind !== 'file') throw new HttpError(400, 'Only uploaded calendars can be replaced with a file.');
  const text = icsText(body);
  const result = await loggedImport(env, user.id, id, 'upload', async () => ({ text, status: null }));
  await env.DB.prepare('UPDATE calendar_sources SET ok_at = ?, error = NULL, file_name = COALESCE(?, file_name) WHERE id = ?')
    .bind(Date.now(), fileName(body), id).run();
  return { calendar: id, ...result };
}

// Re-imports a link. The attempt is stamped first, so a link that keeps failing (or a run that dies mid-way)
// moves to the back of the cron queue instead of blocking everyone behind it.
export async function syncLink(env, source, trigger) {
  if (source.kind !== 'link') throw new HttpError(400, 'Only linked calendars can be updated. Upload a new file instead.');
  await env.DB.prepare('UPDATE calendar_sources SET synced_at = ? WHERE id = ?').bind(Date.now(), source.id).run();
  try {
    const result = await loggedImport(env, source.user_id, source.id, trigger,
      () => fetchCalendar(source.url, source.from_date, MAX_ICS_BYTES));
    await env.DB.prepare('UPDATE calendar_sources SET ok_at = ?, error = NULL WHERE id = ?').bind(Date.now(), source.id).run();
    return { calendar: source.id, ...result };
  } catch (e) {
    await env.DB.prepare('UPDATE calendar_sources SET error = ? WHERE id = ?').bind(e.message, source.id).run();
    throw e;
  }
}

export async function syncCalendar(env, user, id) {
  return syncLink(env, await ownSource(env, user, id), 'manual');
}

// Rename, turn attendance on or off, or (links) change the first day imported. A new first day is applied right away.
// Attendance applies to the courses with sessions in this calendar.
export async function updateCalendar(env, user, id, body) {
  const s = await ownSource(env, user, id);
  const name = 'name' in body ? cleanName(body.name, s.name) : s.name;
  if ('attendance' in body) {
    if (typeof body.attendance !== 'boolean') throw new HttpError(400, 'attendance must be true or false.');
    const a = body.attendance ? 1 : 0;
    await env.DB.batch([
      env.DB.prepare('UPDATE calendar_sources SET attendance = ? WHERE id = ?').bind(a, id),
      env.DB.prepare(`UPDATE courses SET attendance = ?1 WHERE user_id = ?2 AND id IN
        (SELECT e.course_id FROM events e JOIN event_sources es ON es.event_id = e.id WHERE es.source_id = ?3)`).bind(a, user.id, id),
    ]);
    // Turned on: fetch the history the calendar left out while it didn't count attendance.
    if (a && !s.attendance && s.kind === 'link') return { calendar: id, ...await syncLink(env, { ...s, attendance: a }, 'manual') };
  }
  let from = s.from_date;
  if ('from' in body) {
    if (s.kind !== 'link') throw new HttpError(400, 'Only linked calendars have a first day.');
    if (body.from !== null && body.from !== '' && !validDate(body.from)) throw new HttpError(400, 'Invalid start date.');
    from = body.from || null;
  }
  await env.DB.prepare('UPDATE calendar_sources SET name = ?, from_date = ? WHERE id = ?').bind(name, from, id).run();
  if (from !== s.from_date) return { calendar: id, ...await syncLink(env, { ...s, from_date: from }, 'manual') };
  return { calendar: id };
}

// Removes the calendar (its memberships and sync log go by cascade) and the sessions only it had, with their marks.
async function removeById(env, userId, id) {
  const [, removed] = await env.DB.batch([
    env.DB.prepare('DELETE FROM calendar_sources WHERE id = ? AND user_id = ?').bind(id, userId),
    deleteOrphans(env, userId),
    pruneCourses(env, userId),
  ]);
  return { removed: removed.meta.changes };
}

export async function removeCalendar(env, user, id) {
  await ownSource(env, user, id);
  return removeById(env, user.id, id);
}

// Cron: refresh the most overdue link, if any.
export async function syncDueCalendars(env, now = Date.now()) {
  const due = await env.DB.prepare(`SELECT ${SOURCE_COLUMNS} FROM calendar_sources
    WHERE url IS NOT NULL AND (synced_at IS NULL OR synced_at < (CASE WHEN error IS NULL THEN ?1 ELSE ?2 END))
    ORDER BY synced_at LIMIT ?3`).bind(now - SYNC_EVERY_MS, now - RETRY_FAILED_MS, SYNC_BATCH).all();
  // Runs with nothing to do (most of them) aren't logged, so cron_runs holds the runs that did work.
  if (!due.results.length) return { due: 0, ok: 0, failed: 0 };
  const run = await env.DB.prepare('INSERT INTO cron_runs (started_at) VALUES (?) RETURNING id').bind(now).first();
  let ok = 0;
  for (const s of due.results) {
    try { await syncLink(env, s, 'cron'); ok++; } catch { /* logged in calendar_syncs */ }
  }
  const summary = { due: due.results.length, ok, failed: due.results.length - ok };
  await env.DB.batch([
    env.DB.prepare('UPDATE cron_runs SET finished_at = ?, due = ?, ok = ?, failed = ? WHERE id = ?')
      .bind(Date.now(), summary.due, summary.ok, summary.failed, run.id),
    env.DB.prepare('DELETE FROM cron_runs WHERE id NOT IN (SELECT id FROM cron_runs ORDER BY id DESC LIMIT ?)').bind(CRON_LOG_KEEP),
  ]);
  return summary;
}

// Removes every calendar, session, course and break (homework is kept).
export async function wipeCalendars(env, userId) {
  await env.DB.batch([
    env.DB.prepare('DELETE FROM breaks WHERE user_id = ?').bind(userId),
    env.DB.prepare('DELETE FROM events WHERE user_id = ?').bind(userId),
    env.DB.prepare('DELETE FROM calendar_syncs WHERE user_id = ?').bind(userId),
    env.DB.prepare('DELETE FROM calendar_sources WHERE user_id = ?').bind(userId),
    env.DB.prepare('DELETE FROM courses WHERE user_id = ?').bind(userId),
  ]);
}
