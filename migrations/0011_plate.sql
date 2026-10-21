-- Calendars that don't count attendance (e.g. a Tampere University Sisu timetable): their courses show in the
-- timetable and the day plan but never in the attendance maths. Courses take the setting of the calendar that
-- created them, and follow it when it changes.
ALTER TABLE calendar_sources ADD COLUMN attendance INTEGER NOT NULL DEFAULT 1;
ALTER TABLE courses ADD COLUMN attendance INTEGER NOT NULL DEFAULT 1;
-- Where the course's lecture recordings are (Panopto, Moodle, YouTube…), copied into each catch-up.
ALTER TABLE courses ADD COLUMN recording_url TEXT;

-- Catch-ups: "rewatch the lecture you missed" items. They live with homework (same list, reminders and feeds)
-- and point at the session they replace; one per session.
ALTER TABLE homework ADD COLUMN kind TEXT NOT NULL DEFAULT 'homework';   -- homework | catchup
ALTER TABLE homework ADD COLUMN event_id INTEGER REFERENCES events(id) ON DELETE SET NULL;
-- Rough time the item needs, used to fit it into free time between classes. NULL = the default for its kind.
ALTER TABLE homework ADD COLUMN effort_minutes INTEGER;
CREATE UNIQUE INDEX homework_catchup ON homework(user_id, event_id) WHERE kind = 'catchup' AND event_id IS NOT NULL;

-- Personal access tokens, for scripts such as a Claude Code repo. Only a SHA-256 of the token is stored.
CREATE TABLE api_tokens (
  id            INTEGER PRIMARY KEY,
  user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  token_hash    TEXT NOT NULL UNIQUE,
  scope         TEXT NOT NULL,             -- read | write
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER
);
CREATE INDEX api_tokens_user ON api_tokens(user_id);
