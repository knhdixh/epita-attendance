-- Habits: things to do on a schedule ("Gym, Mon Wed Fri", "Read, 3 times a week", "Water ×8 a day").
-- schedule is JSON (see cleanSchedule in public/habits.js): {type:'daily'} | {type:'days', days:[1..7]} (ISO weekdays)
-- | {type:'weekly', n} | {type:'monthly', n} | {type:'interval', n} (every n days). target = check-ins that complete a day.
CREATE TABLE habits (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  icon        TEXT,                         -- an emoji, optional
  color       TEXT NOT NULL DEFAULT 'blue',
  schedule    TEXT NOT NULL,
  target      INTEGER NOT NULL DEFAULT 1,
  unit        TEXT,                         -- what the target counts ("glasses"), optional
  position    INTEGER NOT NULL DEFAULT 0,
  archived    INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX habits_user ON habits(user_id, position);

-- One row per habit and local day with at least one check-in. day is the user's local date, 'YYYY-MM-DD', so a
-- check-in belongs to the day it was made on wherever the server runs. Unchecking a day deletes its row.
CREATE TABLE habit_logs (
  habit_id  INTEGER NOT NULL REFERENCES habits(id) ON DELETE CASCADE,
  day       TEXT NOT NULL,
  count     INTEGER NOT NULL,
  PRIMARY KEY (habit_id, day)
) WITHOUT ROWID;

-- Countdowns (and count-ups): a date to count towards, or since. at is the instant; for all-day ones the client's
-- local midnight. units picks how the time left is shown (see UNITS in public/countdowns.js), start_at where the
-- progress bar starts (NULL = no bar), after what happens once it's reached: 'up' keeps counting up, 'past' files it
-- under Past. A countdown made from a session (an exam) or homework keeps a link to it.
CREATE TABLE countdowns (
  id           INTEGER PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title        TEXT NOT NULL,
  at           INTEGER NOT NULL,
  all_day      INTEGER NOT NULL DEFAULT 1,
  repeat       TEXT NOT NULL DEFAULT 'none' CHECK (repeat IN ('none', 'weekly', 'monthly', 'yearly')),
  units        TEXT NOT NULL DEFAULT 'auto' CHECK (units IN ('auto', 'days', 'weeks', 'months', 'hours', 'clock', 'workdays')),
  after        TEXT NOT NULL DEFAULT 'up' CHECK (after IN ('up', 'past')),
  start_at     INTEGER,
  color        TEXT NOT NULL DEFAULT 'blue',
  pinned       INTEGER NOT NULL DEFAULT 0,  -- shown on Today and in the sidebar
  note         TEXT,
  event_id     INTEGER REFERENCES events(id) ON DELETE SET NULL,
  homework_id  INTEGER REFERENCES homework(id) ON DELETE SET NULL,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX countdowns_user ON countdowns(user_id, at);
-- For ON DELETE SET NULL: calendar re-imports delete events, which then look their countdowns up here.
CREATE INDEX countdowns_event ON countdowns(event_id) WHERE event_id IS NOT NULL;
CREATE INDEX countdowns_homework ON countdowns(homework_id) WHERE homework_id IS NOT NULL;
