import { test } from 'node:test';
import assert from 'node:assert/strict';
import { d1 } from './d1-shim.js';
import worker from '../src/worker.js';
import { TASK_DATA_SQL } from '../src/tasks.js';
import { createToken } from '../src/tokens.js';

function setup() {
  const env = { DB: d1(), ASSETS: { fetch: async () => new Response('') } };
  env.DB.raw.exec(`INSERT INTO users (id, google_sub, created_at, threshold, unit) VALUES (1, 'a', 0, 0.8, 'sessions'), (2, 'b', 0, 0.8, 'sessions');
    INSERT INTO sessions VALUES ('s1', 1, 9e15), ('s2', 2, 9e15);`);
  const api = (sid = 's1') => async (method, path, body) => {
    const res = await worker.fetch(new Request(`https://app.test${path}`, {
      method, headers: { Cookie: `sid=${sid}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
    }), env);
    return Object.assign(await res.json(), { status: res.status });
  };
  return { env, db: env.DB.raw, me: api('s1'), other: api('s2') };
}
const HW = { notes: '', due_date: '2030-01-01', due_time: null, tz: 'Europe/Paris' };
const hw = (me, extra) => me('POST', '/api/homework', { ...HW, title: 'T', ...extra });
const ok = (r) => { assert.equal(r.status, 200, JSON.stringify(r)); return r; };
const bad = (r, text) => { assert.equal(r.status, 400, JSON.stringify(r)); if (text) assert.match(r.error, text); };

test('the homework endpoints behave as before', async () => {
  const { me } = setup();
  bad(await me('POST', '/api/homework', { title: 'x', tz: 'UTC' }), /Invalid due date/);
  bad(await me('POST', '/api/homework', { ...HW, title: '' }), /Title is required/);
  bad(await me('POST', '/api/homework', { ...HW, title: 'x', due_date: '2030-02-30' }), /Invalid due date/);
  const a = ok(await hw(me, { title: 'A', remind_minutes: 60 }));
  assert.deepEqual([a.title, a.due_date, a.priority, a.project_id, a.parent_id, a.recurrence, a.labels, a.comment_count, a.reminders],
    ['A', '2030-01-01', 1, null, null, null, [], 0, []]);
  const p = ok(await me('PATCH', `/api/homework/${a.id}`, { done: true }));
  assert.equal(p.done, 1);
  assert.ok(p.completed_at > 0);
  assert.equal(ok(await me('PATCH', `/api/homework/${a.id}`, { done: false })).completed_at, null);
  assert.equal(ok(await me('DELETE', `/api/homework/${a.id}`)).ok, true);
  assert.equal((await me('DELETE', `/api/homework/${a.id}`)).status, 404);
});

test('new task fields are validated', async () => {
  const { me } = setup();
  bad(await hw(me, { priority: 5 }), /Priority/);
  bad(await hw(me, { priority: 0 }), /Priority/);
  bad(await hw(me, { priority: '2' }), /Priority/);
  bad(await hw(me, { deadline_date: '2030-13-01' }), /deadline/);
  bad(await hw(me, { recurrence: 7 }), /repeat/);
  bad(await hw(me, { recurrence: 'x'.repeat(201) }), /too long/);
  bad(await hw(me, { client_id: 'short' }), /client_id/);
  bad(await hw(me, { project_id: 99 }), /Unknown project/);
  bad(await hw(me, { section_id: 99 }), /Unknown section/);
  bad(await hw(me, { parent_id: 99 }), /Unknown parent/);
  bad(await hw(me, { label_ids: [99] }), /Unknown label/);
  bad(await hw(me, { label_ids: 'a' }), /label_ids/);
  const t = ok(await hw(me, { priority: 4, deadline_date: '2030-01-05', recurrence: 'every day' }));
  assert.deepEqual([t.priority, t.deadline_date, t.recurrence], [4, '2030-01-05', 'every day']);
});

test('tasks without a date: stored with a placeholder, shown as null, hidden from the old lists', async () => {
  const { me, env } = setup();
  const u = ok(await hw(me, { title: 'Someday', due_date: null, due_time: '10:00', remind_minutes: 60 }));
  assert.deepEqual([u.due_date, u.due_time, u.due_at, u.remind_minutes], [null, null, null, null]);
  assert.equal(env.DB.raw.prepare('SELECT due_date FROM homework WHERE id = ?').get(u.id).due_date, '9999-12-31');
  bad(await hw(me, { due_date: null, recurrence: 'every day' }), /needs a date/);
  const dated = ok(await hw(me, { title: 'Dated' }));
  const old = await me('GET', '/api/data');
  assert.deepEqual(old.homework.map((h) => h.title), ['Dated']);
  const fresh = await me('GET', '/api/data?tasks=1');
  assert.deepEqual(fresh.homework.map((h) => h.title).sort(), ['Dated', 'Someday']);
  assert.equal(fresh.homework.find((h) => h.title === 'Someday').due_date, null);
  const plan = await me('GET', '/api/plan?tz=UTC');
  assert.deepEqual(plan.homework.map((h) => h.title), ['Dated']);
  // Giving it a date later, and taking it away again.
  const d = ok(await me('PATCH', `/api/homework/${u.id}`, { due_date: '2030-03-03' }));
  assert.equal(d.due_date, '2030-03-03');
  assert.equal(ok(await me('PATCH', `/api/homework/${d.id}`, { due_date: null })).due_date, null);
  assert.ok(dated.id);
});

test('projects: create, update, validate, nest, delete with their tasks', async () => {
  const { me, other, db } = setup();
  bad(await me('POST', '/api/projects', {}), /Name is required/);
  bad(await me('POST', '/api/projects', { name: 'x', color: 'mauve' }), /colour/);
  bad(await me('POST', '/api/projects', { name: 'x', view: 'grid' }), /list or board/);
  bad(await me('POST', '/api/projects', { name: 'x', favorite: 'yes' }), /favorite/);
  bad(await me('POST', '/api/projects', { name: 'x', parent_id: 99 }), /Unknown parent/);
  const a = ok(await me('POST', '/api/projects', { name: ' Thesis ', color: 'blue', view: 'board' }));
  assert.deepEqual([a.name, a.color, a.view, a.position, a.favorite, a.archived, a.parent_id], ['Thesis', 'blue', 'board', 0, 0, 0, null]);
  const b = ok(await me('POST', '/api/projects', { name: 'Chapter 1', parent_id: a.id }));
  assert.equal(b.position, 1);
  const c = ok(await me('PATCH', `/api/projects/${a.id}`, { favorite: true, archived: 1, name: 'Thesis 2' }));
  assert.deepEqual([c.name, c.favorite, c.archived], ['Thesis 2', 1, 1]);
  bad(await me('PATCH', `/api/projects/${a.id}`, { parent_id: b.id }), /inside itself/);
  bad(await me('PATCH', `/api/projects/${a.id}`, { parent_id: a.id }), /inside itself/);
  assert.equal((await me('PATCH', '/api/projects/9999', { name: 'x' })).status, 404);
  assert.equal((await other('PATCH', `/api/projects/${a.id}`, { name: 'mine now' })).status, 404);
  bad(await other('POST', '/api/projects', { name: 'x', parent_id: a.id }), /Unknown parent/);

  const t1 = ok(await hw(me, { project_id: a.id }));
  const t2 = ok(await hw(me, { project_id: b.id }));
  const t3 = ok(await hw(me, {}));
  const data = await me('GET', '/api/data');
  assert.deepEqual(data.projects.map((p) => [p.name, p.favorite, p.archived]), [['Thesis 2', true, true], ['Chapter 1', false, false]]);
  // Deleting a project deletes the projects and tasks below it.
  ok(await me('DELETE', `/api/projects/${a.id}`));
  assert.deepEqual(db.prepare('SELECT id FROM homework ORDER BY id').all().map((r) => r.id), [t3.id]);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM projects').get().n, 0);
  assert.ok(t1.id && t2.id);
  assert.equal((await me('DELETE', `/api/projects/${a.id}`)).status, 404);
});

test('sections: need a project of yours, keep tasks in their project, cascade', async () => {
  const { me, other, db } = setup();
  const p = ok(await me('POST', '/api/projects', { name: 'P' }));
  const q = ok(await me('POST', '/api/projects', { name: 'Q' }));
  bad(await me('POST', '/api/sections', { name: 'S' }), /Unknown project/);
  bad(await other('POST', '/api/sections', { name: 'S', project_id: p.id }), /Unknown project/);
  bad(await me('POST', '/api/sections', { name: '', project_id: p.id }), /Name is required/);
  const s1 = ok(await me('POST', '/api/sections', { name: 'To do', project_id: p.id }));
  const s2 = ok(await me('POST', '/api/sections', { name: 'Done', project_id: p.id }));
  const s3 = ok(await me('POST', '/api/sections', { name: 'Other', project_id: q.id }));
  assert.deepEqual([s1.position, s2.position, s3.position], [0, 1, 0]);
  assert.equal(ok(await me('PATCH', `/api/sections/${s1.id}`, { name: 'Next' })).name, 'Next');
  ok(await me('POST', '/api/sections/reorder', { ids: [s2.id, s1.id] }));
  assert.deepEqual((await me('GET', '/api/data')).sections.filter((s) => s.project_id === p.id).map((s) => s.name), ['Done', 'Next']);
  const t = ok(await hw(me, { section_id: s1.id }));
  assert.equal(t.project_id, p.id, 'a section fixes the project');
  bad(await hw(me, { section_id: s1.id, project_id: q.id }), /another project/);
  ok(await me('DELETE', `/api/sections/${s1.id}`));
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM homework WHERE id = ?').get(t.id).n, 0);
  ok(await me('DELETE', `/api/projects/${p.id}`));
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM sections').get().n, 1);
});

test('labels: unique per user whatever the case, no spaces, attach to tasks', async () => {
  const { me, other } = setup();
  bad(await me('POST', '/api/labels', { name: 'two words' }), /spaces/);
  bad(await me('POST', '/api/labels', { name: '@' }), /spaces/);
  const a = ok(await me('POST', '/api/labels', { name: '@Lab', color: 'red' }));
  assert.equal(a.name, 'Lab', 'a leading @ is dropped');
  bad(await me('POST', '/api/labels', { name: 'lab' }), /already have/);
  ok(await other('POST', '/api/labels', { name: 'lab' }));
  const b = ok(await me('POST', '/api/labels', { name: 'home', favorite: true }));
  bad(await me('PATCH', `/api/labels/${b.id}`, { name: 'LAB' }), /already have/);
  assert.equal(ok(await me('PATCH', `/api/labels/${b.id}`, { name: 'Home' })).name, 'Home');
  const t = ok(await hw(me, { label_ids: [a.id, b.id, a.id] }));
  assert.deepEqual(t.labels, [a.id, b.id]);
  bad(await other('POST', '/api/homework', { ...HW, title: 'x', label_ids: [a.id] }), /Unknown label/);
  assert.deepEqual(ok(await me('PATCH', `/api/homework/${t.id}`, { label_ids: [b.id] })).labels, [b.id]);
  assert.deepEqual(ok(await me('PATCH', `/api/homework/${t.id}`, { title: 'again' })).labels, [b.id], 'other edits keep the labels');
  assert.deepEqual(ok(await me('PATCH', `/api/homework/${t.id}`, { label_ids: [] })).labels, []);
  ok(await me('PATCH', `/api/homework/${t.id}`, { label_ids: [a.id] }));
  ok(await me('DELETE', `/api/labels/${a.id}`));
  assert.deepEqual((await me('GET', '/api/data')).homework[0].labels, []);
});

test('filters: create, update, validate', async () => {
  const { me } = setup();
  bad(await me('POST', '/api/filters', { name: 'x' }), /query/);
  bad(await me('POST', '/api/filters', { name: 'x', query: 'y'.repeat(501) }), /query/);
  const f = ok(await me('POST', '/api/filters', { name: 'Soon', query: 'today | overdue', color: 'orange', favorite: true }));
  assert.deepEqual([f.query, f.favorite], ['today | overdue', 1]);
  assert.equal(ok(await me('PATCH', `/api/filters/${f.id}`, { query: 'p1' })).query, 'p1');
  assert.deepEqual((await me('GET', '/api/data')).filters.map((x) => [x.name, x.query, x.favorite]), [['Soon', 'p1', true]]);
  ok(await me('DELETE', `/api/filters/${f.id}`));
});

test('comments and reminders', async () => {
  const { me, other } = setup();
  const t = ok(await hw(me, { due_time: '09:00' }));
  bad(await me('POST', `/api/homework/${t.id}/comments`, { body: '  ' }), /comment/);
  assert.equal((await other('POST', `/api/homework/${t.id}/comments`, { body: 'hi' })).status, 404);
  const c = ok(await me('POST', `/api/homework/${t.id}/comments`, { body: ' Ask Dr Smith ' }));
  assert.equal(c.body, 'Ask Dr Smith');
  ok(await me('POST', `/api/homework/${t.id}/comments`, { body: 'Second' }));
  assert.equal((await me('GET', `/api/homework/${t.id}/comments`)).comments.length, 2);
  assert.equal(ok(await me('PATCH', `/api/comments/${c.id}`, { body: 'Edited' })).body, 'Edited');
  assert.equal((await other('PATCH', `/api/comments/${c.id}`, { body: 'x' })).status, 404);
  assert.equal((await me('GET', '/api/data')).homework[0].comment_count, 2);
  ok(await me('DELETE', `/api/comments/${c.id}`));
  assert.equal((await me('GET', '/api/data')).homework[0].comment_count, 1);

  bad(await me('POST', `/api/homework/${t.id}/reminders`, {}), /either/);
  bad(await me('POST', `/api/homework/${t.id}/reminders`, { at: 5, before_minutes: 5 }), /either/);
  bad(await me('POST', `/api/homework/${t.id}/reminders`, { at: -1 }), /Invalid reminder time/);
  bad(await me('POST', `/api/homework/${t.id}/reminders`, { before_minutes: 99999 }), /four weeks/);
  const at = Date.now() + 86400000;
  const r1 = ok(await me('POST', `/api/homework/${t.id}/reminders`, { at }));
  const r2 = ok(await me('POST', `/api/homework/${t.id}/reminders`, { before_minutes: 30 }));
  assert.deepEqual([r1.at, r1.before_minutes, r2.at, r2.before_minutes], [at, null, null, 30]);
  assert.deepEqual((await me('GET', '/api/data')).homework[0].reminders.map((r) => [r.at, r.before_minutes]), [[at, null], [null, 30]]);
  assert.equal(ok(await me('PATCH', `/api/reminders/${r2.id}`, { before_minutes: 60 })).before_minutes, 60);
  ok(await me('DELETE', `/api/reminders/${r1.id}`));
  assert.equal((await other('DELETE', `/api/reminders/${r2.id}`)).status, 404);
  const u = ok(await hw(me, { due_date: null }));
  bad(await me('POST', `/api/homework/${u.id}/reminders`, { before_minutes: 10 }), /Set a date/);
  ok(await me('POST', `/api/homework/${u.id}/reminders`, { at }));
  for (let i = 0; i < 9; i++) ok(await me('POST', `/api/homework/${u.id}/reminders`, { at: at + i + 1 }));
  bad(await me('POST', `/api/homework/${u.id}/reminders`, { at: at + 99 }), /up to 10/);
  // Deleting the task deletes its comments and reminders.
  ok(await me('DELETE', `/api/homework/${t.id}`));
  assert.equal((await me('GET', '/api/data')).homework.length, 0);
});

test('client_id: sending a create again returns the first row, for every kind', async () => {
  const { me, db } = setup();
  const once = async (path, body) => {
    const a = ok(await me('POST', path, { ...body, client_id: 'retry-' + path.replace(/\W/g, '') }));
    const b = ok(await me('POST', path, { ...body, client_id: 'retry-' + path.replace(/\W/g, '') }));
    assert.equal(a.id, b.id, path);
    return a;
  };
  const p = await once('/api/projects', { name: 'P' });
  await once('/api/sections', { name: 'S', project_id: p.id });
  await once('/api/labels', { name: 'L' });
  await once('/api/filters', { name: 'F', query: 'today' });
  const t = await once('/api/homework', { ...HW, title: 'T' });
  await once(`/api/homework/${t.id}/comments`, { body: 'c' });
  await once(`/api/homework/${t.id}/reminders`, { at: Date.now() + 1000 });
  for (const table of ['projects', 'sections', 'labels', 'filters', 'homework', 'comments', 'task_reminders']) {
    assert.equal(db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n, 1, table);
  }
});

test('client_id is per user', async () => {
  const { me, other } = setup();
  const a = ok(await me('POST', '/api/homework', { ...HW, title: 'mine', client_id: 'same-id-123' }));
  const b = ok(await other('POST', '/api/homework', { ...HW, title: 'theirs', client_id: 'same-id-123' }));
  assert.notEqual(a.id, b.id);
  assert.equal(b.title, 'theirs');
});

test('subtasks inherit the parent project and cascade', async () => {
  const { me, db } = setup();
  const p = ok(await me('POST', '/api/projects', { name: 'P' }));
  const s = ok(await me('POST', '/api/sections', { name: 'S', project_id: p.id }));
  const parent = ok(await hw(me, { title: 'parent', section_id: s.id }));
  const child = ok(await hw(me, { title: 'child', parent_id: parent.id, project_id: null }));
  const grand = ok(await hw(me, { title: 'grand', parent_id: child.id }));
  assert.deepEqual([child.project_id, child.section_id, child.parent_id, grand.parent_id], [p.id, s.id, parent.id, child.id]);
  assert.deepEqual([child.position, (ok(await hw(me, { parent_id: parent.id }))).position], [0, 1]);
  ok(await me('DELETE', `/api/homework/${parent.id}`));
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM homework').get().n, 0);
});

test('move: reorder, change project, nest, and the checks', async () => {
  const { me, other } = setup();
  const p = ok(await me('POST', '/api/projects', { name: 'P' }));
  const s = ok(await me('POST', '/api/sections', { name: 'S', project_id: p.id }));
  const ids = [];
  for (const title of ['a', 'b', 'c', 'd']) ids.push(ok(await hw(me, { title })).id);
  const order = async (extra = '') => (await me('GET', '/api/data' + extra)).homework.filter((h) => h.parent_id === null && h.project_id === null)
    .sort((x, y) => x.position - y.position).map((h) => h.title).join('');
  assert.equal(await order(), 'abcd');
  ok(await me('POST', '/api/homework/move', { ids: [ids[3]], position: 0 }));
  assert.equal(await order(), 'dabc');
  ok(await me('POST', '/api/homework/move', { ids: [ids[0], ids[1]], position: 3 }));
  assert.equal(await order(), 'dcab');
  ok(await me('POST', '/api/homework/move', { ids: [ids[2]], position: 99 }));
  assert.equal(await order(), 'dabc');
  // To a project, then a section.
  const r = ok(await me('POST', '/api/homework/move', { ids: [ids[0], ids[1]], project_id: p.id }));
  assert.deepEqual(r.homework.map((h) => [h.title, h.project_id, h.section_id, h.position]), [['a', p.id, null, 0], ['b', p.id, null, 1]]);
  const r2 = ok(await me('POST', '/api/homework/move', { ids: [ids[2]], section_id: s.id }));
  assert.deepEqual([r2.homework[0].project_id, r2.homework[0].section_id], [p.id, s.id]);
  // Nest under a task: it follows the parent; moving the parent takes the subtask along.
  ok(await me('POST', '/api/homework/move', { ids: [ids[3]], parent_id: ids[2] }));
  const moved = ok(await me('POST', '/api/homework/move', { ids: [ids[2]], project_id: null }));
  assert.equal(moved.homework[0].project_id, null);
  const child = (await me('GET', '/api/data')).homework.find((h) => h.id === ids[3]);
  assert.deepEqual([child.project_id, child.section_id, child.parent_id], [null, null, ids[2]]);
  bad(await me('POST', '/api/homework/move', { ids: [ids[2]], parent_id: ids[3] }), /inside itself/);
  bad(await me('POST', '/api/homework/move', { ids: [ids[2]], parent_id: ids[2] }), /inside itself/);
  bad(await me('POST', '/api/homework/move', { ids: [] }), /ids/);
  bad(await me('POST', '/api/homework/move', { ids: ['x'] }), /ids/);
  bad(await me('POST', '/api/homework/move', { ids: [ids[0]], position: -1 }), /position/);
  bad(await me('POST', '/api/homework/move', { ids: [ids[0]], project_id: 999 }), /Unknown project/);
  bad(await me('POST', '/api/homework/move', { ids: [ids[0], ids[3]] }), /different places/);
  assert.equal((await me('POST', '/api/homework/move', { ids: [999] })).status, 404);
  assert.equal((await other('POST', '/api/homework/move', { ids: [ids[0]], project_id: null })).status, 404);
  // PATCH goes through the same code.
  const pat = ok(await me('PATCH', `/api/homework/${ids[1]}`, { project_id: null, position: 0 }));
  assert.deepEqual([pat.project_id, pat.position], [null, 0]);
  const pat2 = ok(await me('PATCH', `/api/homework/${ids[1]}`, { section_id: s.id }));
  assert.deepEqual([pat2.project_id, pat2.section_id], [p.id, s.id]);
});

test('complete and uncomplete: subtasks follow, repeat calls are harmless, others\' tasks are off limits', async () => {
  const { me, other } = setup();
  const parent = ok(await hw(me, { title: 'parent' }));
  const child = ok(await hw(me, { title: 'child', parent_id: parent.id }));
  const done = ok(await me('POST', `/api/homework/${parent.id}/complete`));
  assert.equal(done.done, 1);
  assert.ok(done.completed_at > 0);
  const data = await me('GET', '/api/data');
  assert.deepEqual(data.homework.map((h) => [h.title, h.done]), [['parent', 1], ['child', 1]]);
  assert.equal(ok(await me('POST', `/api/homework/${parent.id}/complete`)).completed_at, done.completed_at);
  const back = ok(await me('POST', `/api/homework/${parent.id}/uncomplete`));
  assert.deepEqual([back.done, back.completed_at], [0, null]);
  assert.equal((await me('GET', '/api/data')).homework.find((h) => h.id === child.id).done, 1, 'uncomplete is for the one task');
  assert.equal((await other('POST', `/api/homework/${parent.id}/complete`)).status, 404);
  assert.equal((await other('POST', `/api/homework/${parent.id}/uncomplete`)).status, 404);
  assert.equal((await me('POST', '/api/homework/999/complete')).status, 404);
});

test('users never see each other\'s projects, labels, filters or tasks', async () => {
  const { me, other } = setup();
  const p = ok(await me('POST', '/api/projects', { name: 'secret' }));
  ok(await me('POST', '/api/labels', { name: 'secret' }));
  ok(await me('POST', '/api/filters', { name: 'secret', query: 'today' }));
  const t = ok(await hw(me, { project_id: p.id }));
  ok(await me('POST', `/api/homework/${t.id}/comments`, { body: 'secret' }));
  const d = await other('GET', '/api/data');
  assert.deepEqual([d.projects, d.sections, d.labels, d.filters, d.homework], [[], [], [], [], []]);
  assert.equal((await other('DELETE', `/api/projects/${p.id}`)).status, 404);
  assert.equal((await other('DELETE', `/api/homework/${t.id}`)).status, 404);
  assert.equal((await other('GET', `/api/homework/${t.id}/comments`)).status, 404);
  assert.equal((await other('POST', '/api/projects/reorder', { ids: [p.id] })).ok, true);
  assert.equal((await me('GET', '/api/data')).projects[0].position, 0);
});

test('deleting an account removes tasks and everything attached', async () => {
  const { me, db } = setup();
  const p = ok(await me('POST', '/api/projects', { name: 'P' }));
  const l = ok(await me('POST', '/api/labels', { name: 'L' }));
  const t = ok(await hw(me, { project_id: p.id, label_ids: [l.id] }));
  ok(await me('POST', `/api/homework/${t.id}/comments`, { body: 'c' }));
  ok(await me('POST', `/api/homework/${t.id}/reminders`, { at: Date.now() + 5000 }));
  db.exec(`INSERT INTO completions (user_id, homework_id, completed_at) VALUES (1, ${t.id}, 1)`);
  db.exec('DELETE FROM users WHERE id = 1');
  for (const table of ['projects', 'labels', 'homework', 'task_labels', 'comments', 'task_reminders', 'completions']) {
    assert.equal(db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n, 0, table);
  }
});

test('/api/data keeps its shape and adds projects, sections, labels, filters', async () => {
  const { me } = setup();
  const d = await me('GET', '/api/data');
  for (const k of ['user', 'courses', 'events', 'homework', 'breaks', 'focus', 'habits', 'countdowns', 'notifications', 'projects', 'sections', 'labels', 'filters']) {
    assert.ok(k in d, k);
  }
});

test('the queries /api/data adds use an index on every table', async () => {
  const { db } = setup();
  for (const sql of TASK_DATA_SQL) {
    const plan = db.prepare(`EXPLAIN QUERY PLAN ${sql}`).all(1).map((r) => r.detail);
    for (const line of plan.filter((l) => /^(SCAN|SEARCH)/.test(l))) assert.match(line, /^SEARCH .* USING (COVERING )?(INDEX|PRIMARY KEY)/, `${sql}\n${plan.join('\n')}`);
    assert.ok(plan.some((l) => /USING (COVERING )?INDEX/.test(l)), `${sql}\n${plan.join('\n')}`);
  }
});

test('API tokens: write scope can move and complete, read scope cannot; project endpoints stay closed', async () => {
  const { env, db } = setup();
  const user = db.prepare('SELECT * FROM users WHERE id = 1').get();
  const rnd = () => 'z'.repeat(30) + Math.random().toString(36).slice(2);
  const w = await createToken(env, user, { name: 'w', scope: 'write' }, rnd);
  const r = await createToken(env, user, { name: 'r', scope: 'read' }, rnd);
  const call = (t, method, path, body) => worker.fetch(new Request(`https://app.test${path}`,
    { method, headers: { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' }, body: body && JSON.stringify(body) }), env);
  const t = await (await call(w.token, 'POST', '/api/homework', { ...HW, title: 'x' })).json();
  assert.equal((await call(w.token, 'POST', `/api/homework/${t.id}/complete`)).status, 200);
  assert.equal((await call(w.token, 'POST', `/api/homework/${t.id}/uncomplete`)).status, 200);
  assert.equal((await call(w.token, 'POST', '/api/homework/move', { ids: [t.id], position: 0 })).status, 200);
  assert.equal((await call(r.token, 'POST', `/api/homework/${t.id}/complete`)).status, 403);
  assert.equal((await call(r.token, 'POST', '/api/homework/move', { ids: [t.id] })).status, 403);
  assert.equal((await call(w.token, 'POST', '/api/projects', { name: 'x' })).status, 403);
  assert.equal((await call(r.token, 'GET', '/api/data')).status, 200);
});
