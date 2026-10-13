CREATE TABLE homework (
  id             INTEGER PRIMARY KEY,
  user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  course_id      INTEGER REFERENCES courses(id) ON DELETE SET NULL,
  title          TEXT NOT NULL,
  notes          TEXT NOT NULL DEFAULT '',
  due_date       TEXT NOT NULL,               -- YYYY-MM-DD in the user's time zone
  due_time       TEXT,                        -- HH:MM, NULL = all day
  tz             TEXT NOT NULL,               -- IANA zone the due date/time is expressed in
  due_at         INTEGER NOT NULL,            -- instant (ms) used for sorting / overdue
  remind_minutes INTEGER,                     -- NULL = no reminder
  done           INTEGER NOT NULL DEFAULT 0,
  gcal_event_id  TEXT,
  sync_error     TEXT,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);
CREATE INDEX homework_user_due ON homework(user_id, due_at);

-- Secret token for the per-user .ics subscription feed.
ALTER TABLE users ADD COLUMN feed_token TEXT;
CREATE UNIQUE INDEX users_feed_token ON users(feed_token);

-- Google Calendar push sync (one link per user).
CREATE TABLE gcal_links (
  user_id        INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  email          TEXT,
  refresh_token  TEXT NOT NULL,               -- AES-GCM encrypted with TOKEN_KEY
  access_token   TEXT,
  access_expires INTEGER NOT NULL DEFAULT 0,
  calendar_id    TEXT,
  last_error     TEXT,
  connected_at   INTEGER NOT NULL
);
