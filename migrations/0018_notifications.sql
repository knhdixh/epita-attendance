-- Notifications by Web Push (src/notify.js): class reminders, habit reminders and the end of a focus phase.

-- A habit's daily reminder, 'HH:MM' in the user's time zone (notify_settings.tz), NULL = none. It only goes on days
-- the habit is due and not done yet (see reminderDue in public/habits.js).
ALTER TABLE habits ADD COLUMN remind_at TEXT;
CREATE INDEX habits_remind ON habits(remind_at) WHERE remind_at IS NOT NULL;

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
