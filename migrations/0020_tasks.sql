-- Todoist-style tasks. A task is a homework row: the website, /api/plan, the feeds, Google Calendar sync and the
-- plate CLI keep working on the same table. A task with a course_id is school work; one without is a plain task.
-- New tables first, because the new homework columns refer to them.

CREATE TABLE projects (
  id         INTEGER PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  parent_id  INTEGER REFERENCES projects(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  color      TEXT NOT NULL DEFAULT 'gray',
  view       TEXT NOT NULL DEFAULT 'list' CHECK (view IN ('list', 'board')),
  position   INTEGER NOT NULL DEFAULT 0,
  favorite   INTEGER NOT NULL DEFAULT 0,
  archived   INTEGER NOT NULL DEFAULT 0,
  client_id  TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX projects_user ON projects(user_id, position);
CREATE INDEX projects_parent ON projects(parent_id) WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX projects_client ON projects(user_id, client_id) WHERE client_id IS NOT NULL;

CREATE TABLE sections (
  id         INTEGER PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  position   INTEGER NOT NULL DEFAULT 0,
  client_id  TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX sections_user ON sections(user_id, project_id, position);
CREATE INDEX sections_project ON sections(project_id);
CREATE UNIQUE INDEX sections_client ON sections(user_id, client_id) WHERE client_id IS NOT NULL;

CREATE TABLE labels (
  id         INTEGER PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  color      TEXT NOT NULL DEFAULT 'gray',
  position   INTEGER NOT NULL DEFAULT 0,
  favorite   INTEGER NOT NULL DEFAULT 0,
  client_id  TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
-- A label name is unique per user, whatever the case ("@Lab" and "@lab" are one label).
CREATE UNIQUE INDEX labels_name ON labels(user_id, name COLLATE NOCASE);
CREATE UNIQUE INDEX labels_client ON labels(user_id, client_id) WHERE client_id IS NOT NULL;

CREATE TABLE filters (
  id         INTEGER PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  query      TEXT NOT NULL,                 -- the filter language of public/filters.js
  color      TEXT NOT NULL DEFAULT 'gray',
  position   INTEGER NOT NULL DEFAULT 0,
  favorite   INTEGER NOT NULL DEFAULT 0,
  client_id  TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX filters_user ON filters(user_id, position);
CREATE UNIQUE INDEX filters_client ON filters(user_id, client_id) WHERE client_id IS NOT NULL;

-- Homework becomes the task table. Deleting a project, section or parent task deletes its tasks (as Todoist does).
-- Tasks without a date store due_date '9999-12-31' (the table needs one); the API shows that as null and the old
-- screens, feeds, /api/plan and Google Calendar skip such rows.
ALTER TABLE homework ADD COLUMN project_id INTEGER REFERENCES projects(id) ON DELETE CASCADE;      -- NULL = Inbox
ALTER TABLE homework ADD COLUMN section_id INTEGER REFERENCES sections(id) ON DELETE CASCADE;
ALTER TABLE homework ADD COLUMN parent_id INTEGER REFERENCES homework(id) ON DELETE CASCADE;
ALTER TABLE homework ADD COLUMN priority INTEGER NOT NULL DEFAULT 1 CHECK (priority BETWEEN 1 AND 4);  -- 4 = p1, as in Todoist's API
ALTER TABLE homework ADD COLUMN position INTEGER NOT NULL DEFAULT 0;
ALTER TABLE homework ADD COLUMN recurrence TEXT;              -- Todoist-style text, see public/recurrence.js
ALTER TABLE homework ADD COLUMN deadline_date TEXT;           -- YYYY-MM-DD, separate from the due date
ALTER TABLE homework ADD COLUMN completed_at INTEGER;
ALTER TABLE homework ADD COLUMN client_id TEXT;
CREATE INDEX homework_project ON homework(project_id, section_id, position) WHERE project_id IS NOT NULL;
CREATE INDEX homework_section ON homework(section_id) WHERE section_id IS NOT NULL;
CREATE INDEX homework_parent ON homework(parent_id) WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX homework_client ON homework(user_id, client_id) WHERE client_id IS NOT NULL;

CREATE TABLE task_labels (
  homework_id INTEGER NOT NULL REFERENCES homework(id) ON DELETE CASCADE,
  label_id    INTEGER NOT NULL REFERENCES labels(id) ON DELETE CASCADE,
  PRIMARY KEY (homework_id, label_id)
) WITHOUT ROWID;
CREATE INDEX task_labels_label ON task_labels(label_id);

CREATE TABLE comments (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  homework_id INTEGER NOT NULL REFERENCES homework(id) ON DELETE CASCADE,
  body        TEXT NOT NULL,
  client_id   TEXT,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX comments_task ON comments(user_id, homework_id, created_at);
CREATE INDEX comments_homework ON comments(homework_id);
CREATE UNIQUE INDEX comments_client ON comments(user_id, client_id) WHERE client_id IS NOT NULL;

-- A reminder is an instant (at, ms) or minutes before the task's due time (before_minutes); exactly one is set.
CREATE TABLE task_reminders (
  id             INTEGER PRIMARY KEY,
  user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  homework_id    INTEGER NOT NULL REFERENCES homework(id) ON DELETE CASCADE,
  at             INTEGER,
  before_minutes INTEGER,
  client_id      TEXT,
  created_at     INTEGER NOT NULL,
  CHECK ((at IS NULL) != (before_minutes IS NULL))
);
CREATE INDEX task_reminders_task ON task_reminders(user_id, homework_id);
CREATE INDEX task_reminders_homework ON task_reminders(homework_id);
CREATE UNIQUE INDEX task_reminders_client ON task_reminders(user_id, client_id) WHERE client_id IS NOT NULL;

-- One row each time a recurring task is completed (the task itself moves to its next date), so history and
-- stats still see it. due_date is the occurrence that was completed, kept so Undo can put it back.
CREATE TABLE completions (
  id           INTEGER PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  homework_id  INTEGER NOT NULL REFERENCES homework(id) ON DELETE CASCADE,
  completed_at INTEGER NOT NULL,
  due_date     TEXT
);
CREATE INDEX completions_user ON completions(user_id, completed_at);
CREATE INDEX completions_homework ON completions(homework_id, completed_at);
