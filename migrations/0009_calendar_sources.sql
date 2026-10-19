-- Several calendars per user: links (Zeus or any .ics URL) refreshed on a schedule, and uploaded .ics files.
-- Every session belongs to the calendar that last imported it, so re-importing one calendar never removes
-- another calendar's sessions. Removing a calendar removes its sessions (and their sync log) by cascade.
CREATE TABLE calendar_sources (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL,              -- link | file
  name        TEXT NOT NULL,
  url         TEXT,                       -- link: normalised https URL (holds a private token, never sent back)
  from_date   TEXT,                       -- link: YYYY-MM-DD sent as Zeus's startDate, so past classes stay
  file_name   TEXT,                       -- file: name of the last uploaded file
  synced_at   INTEGER,                    -- link: last attempt, stamped before fetching so a failing link can't block the queue
  ok_at       INTEGER,                    -- last successful import
  error       TEXT,
  created_at  INTEGER NOT NULL
);
CREATE INDEX calendar_sources_user ON calendar_sources(user_id);
CREATE UNIQUE INDEX calendar_sources_url ON calendar_sources(user_id, url) WHERE url IS NOT NULL;
CREATE INDEX calendar_sources_due ON calendar_sources(synced_at) WHERE url IS NOT NULL;

ALTER TABLE events ADD COLUMN source_id INTEGER REFERENCES calendar_sources(id) ON DELETE CASCADE;
ALTER TABLE calendar_syncs ADD COLUMN source_id INTEGER REFERENCES calendar_sources(id) ON DELETE CASCADE;

-- Existing data: a user's Zeus link, or else their uploaded calendar, becomes their first calendar.
INSERT INTO calendar_sources (user_id, kind, name, url, from_date, synced_at, ok_at, error, created_at)
  SELECT id, 'link', 'Zeus timetable', ics_url, ics_from, ics_synced_at, ics_ok_at, ics_error,
    COALESCE(ics_ok_at, ics_synced_at, created_at)
  FROM users WHERE ics_url IS NOT NULL;
INSERT INTO calendar_sources (user_id, kind, name, ok_at, created_at)
  SELECT u.id, 'file', 'Imported calendar', MAX(e.seen_at), MAX(e.seen_at)
  FROM users u JOIN events e ON e.user_id = u.id
  WHERE u.ics_url IS NULL
  GROUP BY u.id;
UPDATE events SET source_id = (SELECT s.id FROM calendar_sources s WHERE s.user_id = events.user_id);
UPDATE calendar_syncs SET source_id = (SELECT s.id FROM calendar_sources s WHERE s.user_id = calendar_syncs.user_id AND s.kind = 'link');
-- Log rows of a link that was already turned off have no calendar to belong to.
DELETE FROM calendar_syncs WHERE source_id IS NULL;

DROP INDEX users_ics_due;
ALTER TABLE users DROP COLUMN ics_url;
ALTER TABLE users DROP COLUMN ics_from;
ALTER TABLE users DROP COLUMN ics_synced_at;
ALTER TABLE users DROP COLUMN ics_ok_at;
ALTER TABLE users DROP COLUMN ics_error;

-- Foreign keys that had no index: deleting a course (after every import), a calendar or a user scanned the
-- whole table to find the rows to update.
CREATE INDEX events_course ON events(course_id);
CREATE INDEX events_source ON events(source_id);
CREATE INDEX homework_course ON homework(course_id) WHERE course_id IS NOT NULL;
CREATE INDEX calendar_syncs_source ON calendar_syncs(source_id);
