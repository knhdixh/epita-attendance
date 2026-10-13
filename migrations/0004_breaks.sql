-- Planned breaks: a date range whose sessions were marked as planned absences.
-- `changes` stores [{ id, from }] so removing the break restores each session's previous status.
CREATE TABLE breaks (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  label       TEXT NOT NULL DEFAULT '',
  start_date  TEXT NOT NULL,   -- YYYY-MM-DD, user's time zone
  end_date    TEXT NOT NULL,
  changes     TEXT NOT NULL DEFAULT '[]',
  created_at  INTEGER NOT NULL
);
CREATE INDEX breaks_user ON breaks(user_id, start_date);
