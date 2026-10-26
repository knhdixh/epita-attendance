-- Focus (Pomodoro) sessions: one row per focus phase that ran at least a minute; breaks aren't stored.
-- client_id is the timer's random run id, so a session finished by two tabs, or retried after being offline,
-- is recorded once. The label and course are copied in, so the stats keep their names when homework is deleted.
CREATE TABLE focus_sessions (
  id               INTEGER PRIMARY KEY,
  user_id          INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_id        TEXT NOT NULL,
  homework_id      INTEGER REFERENCES homework(id) ON DELETE SET NULL,
  course_id        INTEGER REFERENCES courses(id) ON DELETE SET NULL,
  label            TEXT,
  started_at       INTEGER NOT NULL,
  ended_at         INTEGER NOT NULL,
  planned_seconds  INTEGER NOT NULL,
  focus_seconds    INTEGER NOT NULL,   -- time focused, without pauses
  completed        INTEGER NOT NULL DEFAULT 0,  -- 1 = ran to the end, 0 = stopped or skipped early
  created_at       INTEGER NOT NULL,
  UNIQUE (user_id, client_id)
);
CREATE INDEX focus_sessions_user ON focus_sessions(user_id, started_at);
-- For ON DELETE SET NULL: deleting homework or courses looks up their sessions here instead of scanning the table.
CREATE INDEX focus_sessions_homework ON focus_sessions(homework_id) WHERE homework_id IS NOT NULL;
CREATE INDEX focus_sessions_course ON focus_sessions(course_id) WHERE course_id IS NOT NULL;

-- Timer settings (JSON, see DEFAULT_SETTINGS in public/focus.js), so they follow the account across devices.
ALTER TABLE users ADD COLUMN focus_settings TEXT;
