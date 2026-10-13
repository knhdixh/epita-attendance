-- Per-user daily AI assistant usage, to keep within the Workers AI free allocation.
CREATE TABLE ai_usage (
  user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day       TEXT NOT NULL,                 -- UTC date YYYY-MM-DD
  messages  INTEGER NOT NULL DEFAULT 0,
  neurons   REAL NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, day)
);
