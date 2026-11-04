-- Timeline (public/timeline.js): what takes a share of your weeks besides classes and to-dos, and when you're available.

-- A commitment: work that needs hours every week for a while ("Thesis 10h/week until 15 dec"), or time away (whole
-- days you're not available). Linked to a project or course, its tasks' effort isn't counted again on top.
CREATE TABLE commitments (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title       TEXT NOT NULL,
  kind        TEXT NOT NULL DEFAULT 'work' CHECK (kind IN ('work', 'away')),
  hours       REAL,                       -- per week; NULL for time away
  start_date  TEXT NOT NULL,              -- YYYY-MM-DD, inclusive
  end_date    TEXT NOT NULL,              -- YYYY-MM-DD, inclusive
  days        TEXT,                       -- JSON weekdays (1 = Monday) the hours go on; NULL = any day you're available
  color       TEXT NOT NULL DEFAULT 'blue',
  project_id  INTEGER REFERENCES projects(id) ON DELETE SET NULL,
  course_id   INTEGER REFERENCES courses(id) ON DELETE SET NULL,
  note        TEXT,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX commitments_user ON commitments(user_id, start_date);
CREATE INDEX commitments_project ON commitments(project_id) WHERE project_id IS NOT NULL;
CREATE INDEX commitments_course ON commitments(course_id) WHERE course_id IS NOT NULL;

-- Working hours: JSON, seven days (Monday first) of [from, to] minute ranges. NULL = 8:00 to 22:00 every day.
ALTER TABLE users ADD COLUMN availability TEXT;
