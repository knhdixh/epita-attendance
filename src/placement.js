// Where a task lives: project, section, parent task and position. Shared by task creation, updates and moves.
import { HttpError } from './http.js';
import { HOMEWORK_COLUMNS } from './homework-columns.js';

const isId = (v) => v === null || Number.isSafeInteger(v);
const marks = (n) => Array.from({ length: n }, () => '?').join(', ');

// The ids given plus every task below them (subtasks, to any depth).
export async function withDescendants(env, user, ids) {
  if (!ids.length) return [];
  const r = await env.DB.prepare(`WITH RECURSIVE d(id) AS (
      SELECT id FROM homework WHERE user_id = ? AND id IN (${marks(ids.length)})
      UNION SELECT h.id FROM homework h JOIN d ON h.parent_id = d.id WHERE h.user_id = ?)
    SELECT id FROM d`).bind(user.id, ...ids, user.id).all();
  return r.results.map((x) => x.id);
}

// Projects below a project, itself included.
export async function projectTree(env, user, id) {
  const r = await env.DB.prepare(`WITH RECURSIVE d(id) AS (
      SELECT id FROM projects WHERE user_id = ? AND id = ?
      UNION SELECT p.id FROM projects p JOIN d ON p.parent_id = d.id WHERE p.user_id = ?)
    SELECT id FROM d`).bind(user.id, id, user.id).all();
  return r.results.map((x) => x.id);
}

// Checks { project_id, section_id, parent_id } (missing = null) and returns the consistent triple. A subtask lives
// in its parent's project and section; a section fixes the project. `selfIds` are tasks being moved, which can't
// go inside themselves.
export async function resolvePlacement(env, user, b, { selfIds = [] } = {}) {
  const projectId = b.project_id ?? null, sectionId = b.section_id ?? null, parentId = b.parent_id ?? null;
  if (![projectId, sectionId, parentId].every(isId)) throw new HttpError(400, 'Invalid project, section or parent task.');
  if (parentId !== null) {
    if ((await withDescendants(env, user, selfIds)).includes(parentId)) throw new HttpError(400, 'A task can\'t go inside itself.');
    const parent = await env.DB.prepare('SELECT project_id, section_id FROM homework WHERE id = ? AND user_id = ?').bind(parentId, user.id).first();
    if (!parent) throw new HttpError(400, 'Unknown parent task.');
    return { project_id: parent.project_id, section_id: parent.section_id, parent_id: parentId };
  }
  if (sectionId !== null) {
    const s = await env.DB.prepare('SELECT project_id FROM sections WHERE id = ? AND user_id = ?').bind(sectionId, user.id).first();
    if (!s) throw new HttpError(400, 'Unknown section.');
    if (projectId !== null && projectId !== s.project_id) throw new HttpError(400, 'That section belongs to another project.');
    return { project_id: s.project_id, section_id: sectionId, parent_id: null };
  }
  if (projectId !== null && !(await env.DB.prepare('SELECT 1 FROM projects WHERE id = ? AND user_id = ?').bind(projectId, user.id).first())) {
    throw new HttpError(400, 'Unknown project.');
  }
  return { project_id: projectId, section_id: null, parent_id: null };
}

// The tasks next to each other in one place, in order. A subtask's siblings share its parent; others share
// project and section (NULL = Inbox, no section).
export function siblingsWhere(p) {
  return p.parent_id != null
    ? { sql: 'parent_id = ?', args: [p.parent_id] }
    : { sql: 'parent_id IS NULL AND project_id IS ? AND section_id IS ?', args: [p.project_id, p.section_id] };
}

export async function nextPosition(env, user, p) {
  const w = siblingsWhere(p);
  const r = await env.DB.prepare(`SELECT COALESCE(MAX(position) + 1, 0) AS n FROM homework WHERE user_id = ? AND ${w.sql}`).bind(user.id, ...w.args).first();
  return r.n;
}

const MAX_MOVE = 100;

// POST /api/homework/move { ids, project_id, section_id, parent_id, position }. The destination is the project,
// section and parent given (leave them all out to reorder where the tasks already are; they must share one place).
// The tasks land at `position` among their new siblings, in the order of `ids`, and the siblings are renumbered.
export async function moveTasks(env, user, body) {
  const ids = Array.isArray(body?.ids) ? [...new Set(body.ids)] : null;
  if (!ids?.length || ids.length > MAX_MOVE || !ids.every(Number.isSafeInteger)) throw new HttpError(400, `ids: 1 to ${MAX_MOVE} task ids.`);
  const position = body.position ?? null;
  if (position !== null && !(Number.isInteger(position) && position >= 0)) throw new HttpError(400, 'Invalid position.');
  const rows = (await env.DB.prepare(`SELECT id, project_id, section_id, parent_id FROM homework WHERE user_id = ?
    AND id IN (${ids.map(() => '?').join(', ')})`).bind(user.id, ...ids).all()).results;
  if (rows.length !== ids.length) throw new HttpError(404, 'Task not found.');
  const named = ['project_id', 'section_id', 'parent_id'].some((k) => k in body);
  let target = body;
  if (!named) {
    const [first] = rows;
    if (rows.some((r) => r.project_id !== first.project_id || r.section_id !== first.section_id || r.parent_id !== first.parent_id)) {
      throw new HttpError(400, 'Those tasks are in different places. Say where to move them.');
    }
    target = first;
  }
  const place = await resolvePlacement(env, user, target, { selfIds: ids });
  const w = siblingsWhere(place);
  const siblings = (await env.DB.prepare(`SELECT id, position FROM homework WHERE user_id = ? AND ${w.sql}
    AND id NOT IN (${ids.map(() => '?').join(', ')}) ORDER BY position, id`).bind(user.id, ...w.args, ...ids).all()).results;
  const at = Math.min(position ?? siblings.length, siblings.length);
  const order = [...siblings.slice(0, at).map((r) => ({ id: r.id, was: r.position })), ...ids.map((id) => ({ id, was: null })),
    ...siblings.slice(at).map((r) => ({ id: r.id, was: r.position }))];
  const now = Date.now();
  const stmts = [];
  order.forEach((o, i) => {
    if (o.was === null) {
      stmts.push(env.DB.prepare('UPDATE homework SET project_id = ?, section_id = ?, parent_id = ?, position = ?, updated_at = ? WHERE id = ? AND user_id = ?')
        .bind(place.project_id, place.section_id, place.parent_id, i, now, o.id, user.id));
    } else if (o.was !== i) {
      stmts.push(env.DB.prepare('UPDATE homework SET position = ? WHERE id = ? AND user_id = ?').bind(i, o.id, user.id));
    }
  });
  // Subtasks follow their parent into its project and section.
  const below = (await withDescendants(env, user, ids)).filter((id) => !ids.includes(id));
  if (below.length) {
    stmts.push(env.DB.prepare(`UPDATE homework SET project_id = ?, section_id = ?, updated_at = ? WHERE user_id = ?
      AND id IN (${below.map(() => '?').join(', ')})`).bind(place.project_id, place.section_id, now, user.id, ...below));
  }
  await env.DB.batch(stmts);
  const moved = await env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE user_id = ? AND id IN (${ids.map(() => '?').join(', ')})`)
    .bind(user.id, ...ids).all();
  return { homework: ids.map((id) => moved.results.find((r) => r.id === id)) };
}
