-- One row per Zeus fetch (connect, "Update now" or the daily cron), so failures are visible instead of silent.
-- Pruned to the last 50 per user.
CREATE TABLE calendar_syncs (
  id           INTEGER PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  at           INTEGER NOT NULL,
  source       TEXT NOT NULL,       -- connect | manual | cron
  ok           INTEGER NOT NULL,
  http_status  INTEGER,             -- Zeus's HTTP status; NULL if it didn't answer
  duration_ms  INTEGER NOT NULL,
  sessions     INTEGER,
  added        INTEGER,
  removed      INTEGER,
  error        TEXT
);
CREATE INDEX calendar_syncs_user ON calendar_syncs(user_id, at);

-- One row per cron run that had a link to refresh. finished_at stays NULL if the run died (e.g. hit the
-- CPU limit), which shows up here even when no user row was written. Pruned to the last 200.
CREATE TABLE cron_runs (
  id           INTEGER PRIMARY KEY,
  started_at   INTEGER NOT NULL,
  finished_at  INTEGER,
  due          INTEGER,
  ok           INTEGER,
  failed       INTEGER
);
