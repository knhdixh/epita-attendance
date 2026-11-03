// Projects, sections, labels, filters, comments and reminders: the parts of a task that aren't columns of
// `homework`. Every create accepts `client_id` so an offline client can retry safely: the same id again returns
// the row made the first time.
import { HttpError } from './http.js';
import { COLORS } from '../public/habits.js';
import { CLIENT_ID, taskRow } from './homework-columns.js';
import { projectTree } from './placement.js';
import { parseFilter } from '../public/filters.js';
import { deleteGcalEvent } from './gcal.js';

const MAX = { projects: 300, sections: 100, labels: 500, filters: 150, comments: 500, reminders: 10 };
const MAX_NAME = 120;
const MAX_QUERY = 500;
const MAX_COMMENT = 4000;
const MAX_BEFORE = 28 * 1440;   // reminders count back at most four weeks

const flag = (v, what) => {
  if (v !== true && v !== false && v !== 0 && v !== 1) throw new HttpError(400, `${what} must be true or false.`);
  return v ? 1 : 0;
};
const name = (v, what = 'Name') => {
  const s = typeof v === 'string' ? v.trim() : '';
  if (!s || s.length > MAX_NAME) throw new HttpError(400, `${what} is required (max ${MAX_NAME} characters).`);
  return s;
};
const color = (v) => {
  if (!COLORS.includes(v)) throw new HttpError(400, 'Invalid colour.');
  return v;
};
const position = (v) => {
  if (!(Number.isInteger(v) && v >= 0 && v < 1e6)) throw new HttpError(400, 'Invalid position.');
  return v;
};
function clientId(b) {
  const c = b.client_id ?? null;
  if (c !== null && !(typeof c === 'string' && CLIENT_ID.test(c))) throw new HttpError(400, 'Invalid client_id.');
  return c;
}

// ---------------------------------------------------------------- generic rows

// Each spec says which columns the API shows, how to read the fields of a request, and how many a user may have.
// clean(raw, partial) returns { column: value } for the fields present (all of them, with defaults, unless partial).
const SPECS = {
  projects: {
    label: 'Project', noun: 'projects', columns: 'id, parent_id, name, color, view, position, favorite, archived, client_id, created_at, updated_at',
    scope: () => ({ sql: '1 = 1', args: [] }),
    async clean(env, user, b, partial, id) {
      const out = {};
      const has = (k) => !partial || k in b;
      if (has('name')) out.name = name(b.name);
      if (has('color')) out.color = b.color === undefined ? 'gray' : color(b.color);
      if (has('view')) {
        const v = b.view ?? 'list';
        if (v !== 'list' && v !== 'board') throw new HttpError(400, 'A project\'s view is list or board.');
        out.view = v;
      }
      if (has('favorite')) out.favorite = flag(b.favorite ?? 0, 'favorite');
      if (has('archived')) out.archived = flag(b.archived ?? 0, 'archived');
      if (has('parent_id')) {
        const parent = b.parent_id ?? null;
        if (parent !== null) {
          if (!Number.isSafeInteger(parent)) throw new HttpError(400, 'Invalid parent project.');
          if (!(await env.DB.prepare('SELECT 1 FROM projects WHERE id = ? AND user_id = ?').bind(parent, user.id).first())) throw new HttpError(400, 'Unknown parent project.');
          if (id != null && (await projectTree(env, user, id)).includes(parent)) throw new HttpError(400, 'A project can\'t go inside itself.');
        }
        out.parent_id = parent;
      }
      return out;
    },
  },
  sections: {
    label: 'Section', noun: 'sections', columns: 'id, project_id, name, position, client_id, created_at, updated_at',
    scope: (o) => ({ sql: 'project_id = ?', args: [o.project_id] }),
    async clean(env, user, b, partial) {
      const out = {};
      if (!partial) {
        const p = b.project_id;
        if (!Number.isSafeInteger(p) || !(await env.DB.prepare('SELECT 1 FROM projects WHERE id = ? AND user_id = ?').bind(p, user.id).first())) throw new HttpError(400, 'Unknown project.');
        out.project_id = p;
      }
      if (!partial || 'name' in b) out.name = name(b.name);
      return out;
    },
  },
  labels: {
    label: 'Label', noun: 'labels', columns: 'id, name, color, position, favorite, client_id, created_at, updated_at',
    scope: () => ({ sql: '1 = 1', args: [] }),
    async clean(env, user, b, partial, id) {
      const out = {};
      if (!partial || 'name' in b) {
        const n = name(b.name, 'Label name').replace(/^@/, '');
        if (/[\s@#]/.test(n) || !n) throw new HttpError(400, 'A label name can\'t have spaces, @ or #.');
        const clash = await env.DB.prepare('SELECT id FROM labels WHERE user_id = ? AND name = ? COLLATE NOCASE').bind(user.id, n).first();
        if (clash && clash.id !== id) throw new HttpError(400, 'You already have a label with that name.');
        out.name = n;
      }
      if (!partial || 'color' in b) out.color = b.color === undefined ? 'gray' : color(b.color);
      if (!partial || 'favorite' in b) out.favorite = flag(b.favorite ?? 0, 'favorite');
      return out;
    },
  },
  filters: {
    label: 'Filter', noun: 'filters', columns: 'id, name, query, color, position, favorite, client_id, created_at, updated_at',
    scope: () => ({ sql: '1 = 1', args: [] }),
    async clean(env, user, b, partial) {
      const out = {};
      if (!partial || 'name' in b) out.name = name(b.name);
      if (!partial || 'query' in b) {
        const q = typeof b.query === 'string' ? b.query.trim() : '';
        if (!q || q.length > MAX_QUERY) throw new HttpError(400, `A filter needs a query (max ${MAX_QUERY} characters).`);
        const parsed = parseFilter(q, { today: new Date().toISOString().slice(0, 10) });
        if (!parsed.ok) throw new HttpError(400, parsed.error);
        out.query = q;
      }
      if (!partial || 'color' in b) out.color = b.color === undefined ? 'gray' : color(b.color);
      if (!partial || 'favorite' in b) out.favorite = flag(b.favorite ?? 0, 'favorite');
      return out;
    },
  },
};

const isUnique = (e) => /UNIQUE/i.test(String(e?.message));

export async function listRows(env, user, table) {
  const spec = SPECS[table];
  const order = table === 'sections' ? 'project_id, position, id' : 'position, id';
  return (await env.DB.prepare(`SELECT ${spec.columns} FROM ${table} WHERE user_id = ? ORDER BY ${order}`).bind(user.id).all()).results;
}

export async function createRow(env, user, table, b, now = Date.now()) {
  const spec = SPECS[table];
  const cid = clientId(b);
  const again = () => env.DB.prepare(`SELECT ${spec.columns} FROM ${table} WHERE user_id = ? AND client_id = ?`).bind(user.id, cid).first();
  if (cid) { const row = await again(); if (row) return row; }
  const fields = await spec.clean(env, user, b, false);
  const { n } = await env.DB.prepare(`SELECT COUNT(*) AS n FROM ${table} WHERE user_id = ?`).bind(user.id).first();
  if (n >= MAX[table]) throw new HttpError(400, `You can have up to ${MAX[table]} ${spec.noun}. Delete one first.`);
  const w = spec.scope(fields);
  const pos = 'position' in b ? position(b.position)
    : (await env.DB.prepare(`SELECT COALESCE(MAX(position) + 1, 0) AS n FROM ${table} WHERE user_id = ? AND ${w.sql}`).bind(user.id, ...w.args).first()).n;
  const keys = Object.keys(fields);
  try {
    return await env.DB.prepare(`INSERT INTO ${table} (user_id, ${keys.join(', ')}, position, client_id, created_at, updated_at)
      VALUES (?, ${keys.map(() => '?').join(', ')}, ?, ?, ?, ?) RETURNING ${spec.columns}`)
      .bind(user.id, ...keys.map((k) => fields[k]), pos, cid, now, now).first();
  } catch (e) {
    if (cid && isUnique(e)) { const row = await again(); if (row) return row; }
    throw e;
  }
}

export async function updateRow(env, user, table, id, b, now = Date.now()) {
  const spec = SPECS[table];
  const fields = await spec.clean(env, user, b, true, id);
  if ('position' in b) fields.position = position(b.position);
  const keys = Object.keys(fields);
  if (!keys.length) {
    const row = await env.DB.prepare(`SELECT ${spec.columns} FROM ${table} WHERE id = ? AND user_id = ?`).bind(id, user.id).first();
    if (!row) throw new HttpError(404, `${spec.label} not found.`);
    return row;
  }
  const row = await env.DB.prepare(`UPDATE ${table} SET ${keys.map((k) => `${k} = ?`).join(', ')}, updated_at = ? WHERE id = ? AND user_id = ?
    RETURNING ${spec.columns}`).bind(...keys.map((k) => fields[k]), now, id, user.id).first();
  if (!row) throw new HttpError(404, `${spec.label} not found.`);
  return row;
}

// Deleting a project or section deletes its tasks (the foreign keys); their Google Calendar events go with them.
export async function deleteRow(env, user, table, id) {
  const spec = SPECS[table];
  let events = [];
  if (table === 'projects' || table === 'sections') {
    const ids = table === 'projects' ? await projectTree(env, user, id) : [id];
    if (ids.length) {
      const col = table === 'projects' ? 'project_id' : 'section_id';
      events = (await env.DB.prepare(`SELECT gcal_event_id FROM homework WHERE user_id = ? AND gcal_event_id IS NOT NULL
        AND ${col} IN (${ids.map(() => '?').join(', ')})`).bind(user.id, ...ids).all()).results;
    }
  }
  const r = await env.DB.prepare(`DELETE FROM ${table} WHERE id = ? AND user_id = ?`).bind(id, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, `${spec.label} not found.`);
  for (const e of events) await deleteGcalEvent(env, user.id, e.gcal_event_id);
  return { ok: true };
}

// New order from a list of ids (rows left out keep their place after these). Sections stay inside their project.
export async function reorderRows(env, user, table, b) {
  const ids = Array.isArray(b?.ids) ? b.ids.filter(Number.isSafeInteger).slice(0, MAX[table]) : null;
  if (!ids?.length) throw new HttpError(400, `ids must be a list of ${SPECS[table].noun} ids.`);
  await env.DB.batch(ids.map((id, i) => env.DB.prepare(`UPDATE ${table} SET position = ? WHERE id = ? AND user_id = ? AND position != ?`).bind(i, id, user.id, i)));
  return { ok: true };
}

// ---------------------------------------------------------------- comments

const COMMENT_COLUMNS = 'id, homework_id, body, client_id, created_at, updated_at';

async function ownTask(env, user, id) {
  const t = await env.DB.prepare('SELECT id, due_date FROM homework WHERE id = ? AND user_id = ?').bind(id, user.id).first();
  if (!t) throw new HttpError(404, 'Task not found.');
  return t;
}

const cleanBody = (v) => {
  const s = typeof v === 'string' ? v.trim() : '';
  if (!s || s.length > MAX_COMMENT) throw new HttpError(400, `A comment is 1 to ${MAX_COMMENT} characters.`);
  return s;
};

export async function listComments(env, user, taskId) {
  await ownTask(env, user, taskId);
  return { comments: (await env.DB.prepare(`SELECT ${COMMENT_COLUMNS} FROM comments WHERE user_id = ? AND homework_id = ? ORDER BY created_at, id`).bind(user.id, taskId).all()).results };
}

export async function createComment(env, user, taskId, b, now = Date.now()) {
  const cid = clientId(b);
  const again = () => env.DB.prepare(`SELECT ${COMMENT_COLUMNS} FROM comments WHERE user_id = ? AND client_id = ?`).bind(user.id, cid).first();
  if (cid) { const row = await again(); if (row) return row; }
  await ownTask(env, user, taskId);
  const body = cleanBody(b.body);
  const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM comments WHERE user_id = ? AND homework_id = ?').bind(user.id, taskId).first();
  if (n >= MAX.comments) throw new HttpError(400, `A task can have up to ${MAX.comments} comments.`);
  try {
    return await env.DB.prepare(`INSERT INTO comments (user_id, homework_id, body, client_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)
      RETURNING ${COMMENT_COLUMNS}`).bind(user.id, taskId, body, cid, now, now).first();
  } catch (e) {
    if (cid && isUnique(e)) { const row = await again(); if (row) return row; }
    throw e;
  }
}

export async function updateComment(env, user, id, b, now = Date.now()) {
  const row = await env.DB.prepare(`UPDATE comments SET body = ?, updated_at = ? WHERE id = ? AND user_id = ? RETURNING ${COMMENT_COLUMNS}`)
    .bind(cleanBody(b.body), now, id, user.id).first();
  if (!row) throw new HttpError(404, 'Comment not found.');
  return row;
}

export async function deleteComment(env, user, id) {
  const r = await env.DB.prepare('DELETE FROM comments WHERE id = ? AND user_id = ?').bind(id, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, 'Comment not found.');
  return { ok: true };
}

// ---------------------------------------------------------------- reminders

const REMINDER_COLUMNS = 'id, homework_id, at, before_minutes, client_id, created_at';

// { at: <ms> } or { before_minutes: <n> } (relative to the due time, so the task needs a date).
function cleanReminder(b, task) {
  const hasAt = b.at != null, hasBefore = b.before_minutes != null;
  if (hasAt === hasBefore) throw new HttpError(400, 'A reminder is either a time (at) or minutes before the due time (before_minutes).');
  if (hasAt) {
    if (!(Number.isSafeInteger(b.at) && b.at > 0)) throw new HttpError(400, 'Invalid reminder time.');
    return { at: b.at, before_minutes: null };
  }
  if (!(Number.isInteger(b.before_minutes) && b.before_minutes >= 0 && b.before_minutes <= MAX_BEFORE)) throw new HttpError(400, 'A reminder can be up to four weeks before.');
  if (task.due_date === '9999-12-31') throw new HttpError(400, 'Set a date before adding a reminder relative to it.');
  return { at: null, before_minutes: b.before_minutes };
}

export async function createReminder(env, user, taskId, b, now = Date.now()) {
  const cid = clientId(b);
  const again = () => env.DB.prepare(`SELECT ${REMINDER_COLUMNS} FROM task_reminders WHERE user_id = ? AND client_id = ?`).bind(user.id, cid).first();
  if (cid) { const row = await again(); if (row) return row; }
  const task = await ownTask(env, user, taskId);
  const r = cleanReminder(b, task);
  const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM task_reminders WHERE user_id = ? AND homework_id = ?').bind(user.id, taskId).first();
  if (n >= MAX.reminders) throw new HttpError(400, `A task can have up to ${MAX.reminders} reminders.`);
  try {
    return await env.DB.prepare(`INSERT INTO task_reminders (user_id, homework_id, at, before_minutes, client_id, created_at) VALUES (?, ?, ?, ?, ?, ?)
      RETURNING ${REMINDER_COLUMNS}`).bind(user.id, taskId, r.at, r.before_minutes, cid, now).first();
  } catch (e) {
    if (cid && isUnique(e)) { const row = await again(); if (row) return row; }
    throw e;
  }
}

export async function updateReminder(env, user, id, b) {
  const cur = await env.DB.prepare(`SELECT ${REMINDER_COLUMNS} FROM task_reminders WHERE id = ? AND user_id = ?`).bind(id, user.id).first();
  if (!cur) throw new HttpError(404, 'Reminder not found.');
  const task = await ownTask(env, user, cur.homework_id);
  const r = cleanReminder(b, task);
  return env.DB.prepare(`UPDATE task_reminders SET at = ?, before_minutes = ? WHERE id = ? AND user_id = ? RETURNING ${REMINDER_COLUMNS}`)
    .bind(r.at, r.before_minutes, id, user.id).first();
}

export async function deleteReminder(env, user, id) {
  const r = await env.DB.prepare('DELETE FROM task_reminders WHERE id = ? AND user_id = ?').bind(id, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, 'Reminder not found.');
  return { ok: true };
}

// ---------------------------------------------------------------- what /api/data adds

// One statement per list; each is an index lookup on user_id (checked in test/tasks.test.js with EXPLAIN QUERY PLAN).
export const TASK_DATA_SQL = [
  `SELECT ${SPECS.projects.columns} FROM projects WHERE user_id = ? ORDER BY position, id`,
  `SELECT ${SPECS.sections.columns} FROM sections WHERE user_id = ? ORDER BY project_id, position, id`,
  `SELECT ${SPECS.labels.columns} FROM labels WHERE user_id = ? ORDER BY position, id`,
  `SELECT ${SPECS.filters.columns} FROM filters WHERE user_id = ? ORDER BY position, id`,
  // homework first (via homework_user_due), then each one's labels by primary key.
  'SELECT tl.homework_id, tl.label_id FROM homework h CROSS JOIN task_labels tl ON tl.homework_id = h.id WHERE h.user_id = ?',
  'SELECT homework_id, COUNT(*) AS n FROM comments WHERE user_id = ? GROUP BY homework_id',
  'SELECT id, homework_id, at, before_minutes FROM task_reminders WHERE user_id = ? ORDER BY homework_id, id',
];

export const taskDataQueries = (env, user) => TASK_DATA_SQL.map((sql) => env.DB.prepare(sql).bind(user.id));

// Adds labels (ids), comment_count and reminders to task rows from the results of taskDataQueries, and maps
// undated tasks. `dated` = leave undated tasks out (what the website and older apps get).
export function hydrate(rows, [, , , , labels, counts, reminders], { dated = false } = {}) {
  const l = new Map(), c = new Map(counts.results.map((r) => [r.homework_id, r.n])), rem = new Map();
  for (const r of labels.results) (l.get(r.homework_id) ?? l.set(r.homework_id, []).get(r.homework_id)).push(r.label_id);
  for (const r of reminders.results) (rem.get(r.homework_id) ?? rem.set(r.homework_id, []).get(r.homework_id)).push({ id: r.id, at: r.at, before_minutes: r.before_minutes });
  return rows.filter((r) => !dated || r.due_date !== '9999-12-31')
    .map((r) => ({ ...taskRow(r), labels: l.get(r.id) ?? [], comment_count: c.get(r.id) ?? 0, reminders: rem.get(r.id) ?? [] }));
}

// The same for a few rows that were just written.
export async function hydrateRows(env, user, rows) {
  const ids = rows.map((r) => r.id);
  if (!ids.length) return [];
  const IN = ids.map(() => '?').join(', ');
  const [labels, counts, reminders] = await env.DB.batch([
    env.DB.prepare(`SELECT homework_id, label_id FROM task_labels WHERE homework_id IN (${IN})`).bind(...ids),
    env.DB.prepare(`SELECT homework_id, COUNT(*) AS n FROM comments WHERE user_id = ? AND homework_id IN (${IN}) GROUP BY homework_id`).bind(user.id, ...ids),
    env.DB.prepare(`SELECT id, homework_id, at, before_minutes FROM task_reminders WHERE user_id = ? AND homework_id IN (${IN}) ORDER BY homework_id, id`).bind(user.id, ...ids),
  ]);
  return hydrate(rows, [null, null, null, null, labels, counts, reminders]);
}

// Completed tasks of recurring series: GET /api/completions?since=<ms>.
export async function listCompletions(env, user, since) {
  return { completions: (await env.DB.prepare('SELECT id, homework_id, completed_at, due_date FROM completions WHERE user_id = ? AND completed_at >= ? ORDER BY completed_at')
    .bind(user.id, since).all()).results };
}
