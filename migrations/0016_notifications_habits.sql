-- 0015 is left for the native app's sign-in tables (branch native-android).

-- Habits: something to do on some days of the week ("Read 20 pages"), ticked off per day, with an optional daily
-- reminder. Days and the reminder time are in the habit's time zone (the browser's when it was saved).
CREATE TABLE habits (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  days        INTEGER NOT NULL DEFAULT 127,   -- bit 0 = Monday … bit 6 = Sunday
  remind_at   TEXT,                           -- 'HH:MM', NULL = no reminder
  tz          TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX habits_user ON habits(user_id);
CREATE INDEX habits_remind ON habits(remind_at) WHERE remind_at IS NOT NULL;

CREATE TABLE habit_checks (
  habit_id    INTEGER NOT NULL REFERENCES habits(id) ON DELETE CASCADE,
  day         TEXT NOT NULL,                  -- 'YYYY-MM-DD' in the habit's time zone
  PRIMARY KEY (habit_id, day)
);

-- Web Push subscriptions: one per browser, or per install of the Android app (app = 1, which rings for the focus
-- timer itself, so it gets no focus pushes).
CREATE TABLE push_subscriptions (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  endpoint    TEXT NOT NULL UNIQUE,
  p256dh      TEXT NOT NULL,
  auth        TEXT NOT NULL,
  app         INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL
);
CREATE INDEX push_subscriptions_user ON push_subscriptions(user_id);

-- Class and habit reminders already sent, so a late or repeated cron run doesn't send them twice.
CREATE TABLE push_sent (
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  key         TEXT NOT NULL,
  at          INTEGER NOT NULL,
  PRIMARY KEY (user_id, key)
);
CREATE INDEX push_sent_at ON push_sent(at);

-- What to be notified about (JSON, see cleanNotify in src/notify.js).
ALTER TABLE users ADD COLUMN notify_settings TEXT;
-- The end of the running focus-timer phase and what to say then ({ title, body }). The page keeps it up to date;
-- the cron sends it if no open page has moved the timer on by then.
ALTER TABLE users ADD COLUMN focus_push_at INTEGER;
ALTER TABLE users ADD COLUMN focus_push TEXT;
CREATE INDEX users_focus_push ON users(focus_push_at) WHERE focus_push_at IS NOT NULL;
