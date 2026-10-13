CREATE TABLE users (
  id          INTEGER PRIMARY KEY,
  google_sub  TEXT NOT NULL UNIQUE,
  email       TEXT,
  name        TEXT,
  picture     TEXT,
  threshold   REAL NOT NULL DEFAULT 0.8,
  unit        TEXT NOT NULL DEFAULT 'sessions',  -- sessions | hours
  created_at  INTEGER NOT NULL
);

CREATE TABLE sessions (
  id          TEXT PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);

CREATE TABLE courses (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  key         TEXT NOT NULL,
  name        TEXT NOT NULL,
  tracked     INTEGER NOT NULL DEFAULT 1,
  tag         TEXT NOT NULL DEFAULT 'normal',     -- normal | mandatory | exam | optional
  UNIQUE (user_id, key)
);

CREATE TABLE events (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  course_id   INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
  uid         TEXT NOT NULL,
  summary     TEXT NOT NULL,
  location    TEXT NOT NULL DEFAULT '',
  description TEXT NOT NULL DEFAULT '',
  start_at    INTEGER NOT NULL,
  end_at      INTEGER NOT NULL,
  status      TEXT,                               -- NULL | present | absent | excused
  tag         TEXT,                               -- NULL = inherit from course
  note        TEXT NOT NULL DEFAULT '',
  seen_at     INTEGER NOT NULL DEFAULT 0,         -- last import that contained this event
  UNIQUE (user_id, uid)
);
CREATE INDEX events_user_start ON events(user_id, start_at);
