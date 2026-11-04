import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as T from '../public/timeline.js';
import { buildPlan } from '../public/plan.js';
import { d1 } from './d1-shim.js';
import worker from '../src/worker.js';

// 2026-11-04 is a Wednesday.
const TODAY = '2026-11-04';
const parse = (s) => T.parseCommitment(s, { today: TODAY });
const val = (s) => { const r = parse(s); assert.ok(r.ok, `${s}: ${r.error}`); return r.value; };

test('one sentence makes a commitment', () => {
  const cases = [
    ['Thesis 10h/week until 15 dec', { title: 'Thesis', kind: 'work', hours: 10, start_date: TODAY, end_date: '2026-12-15', days: null }],
    ['Job 12 h a week mon wed from 1 nov to 20 dec', { title: 'Job', hours: 12, start_date: '2026-11-01', end_date: '2026-12-20', days: [1, 3] }],
    ['Gym 3h/w weekdays', { title: 'Gym', hours: 3, days: [1, 2, 3, 4, 5], end_date: '2026-12-29' }],
    ['Read 2 hours per week', { title: 'Read', hours: 2 }],
    ['Revision 6h per week for 3 weeks', { hours: 6, start_date: TODAY, end_date: '2026-11-24' }],
    ['Essay 20h by 1 dec', { title: 'Essay', hours: 5, end_date: '2026-12-01', total: 20 }],
    ['Project 5h/week from 1 feb to 1 jun', { start_date: '2027-02-01', end_date: '2027-06-01' }],
    ['Tutoring 4h/week on tue and thu', { days: [2, 4] }],
    ['Lab 1.5h/week until next week', { hours: 1.5, end_date: '2026-11-09' }],
    ['Volunteering 3h/week next week', { start_date: '2026-11-09', end_date: '2026-11-15' }],
    ['Away 20-27 dec', { title: 'Away', kind: 'away', hours: null, start_date: '2026-12-20', end_date: '2026-12-27' }],
    ['Away 28 dec - 2 jan', { start_date: '2026-12-28', end_date: '2027-01-02' }],
    ['Trip to Rome 12-15 nov', { title: 'Trip to Rome', kind: 'away', start_date: '2026-11-12', end_date: '2026-11-15' }],
    ['Off tomorrow', { title: 'Off', kind: 'away', start_date: '2026-11-05', end_date: '2026-11-05' }],
    ['Off fri', { start_date: '2026-11-06', end_date: '2026-11-06' }],
    ['Holiday next week', { kind: 'away', start_date: '2026-11-09', end_date: '2026-11-15' }],
    ['Sick for 3 days', { kind: 'away', start_date: TODAY, end_date: '2026-11-06' }],
    ['vacation from 21 dec to 4 jan', { title: 'Vacation', start_date: '2026-12-21', end_date: '2027-01-04' }],
  ];
  for (const [input, want] of cases) {
    const got = val(input);
    for (const [k, v] of Object.entries(want)) assert.deepEqual(got[k], v, `${input}: ${k}`);
  }
});

test('a sentence that doesn\'t say enough gets a plain answer', () => {
  assert.match(parse('Thesis chapter 3').error, /how many hours/);
  assert.match(parse('10h/week').error, /Name it/);
  assert.match(parse('Essay 20h').error, /By when/);
  assert.match(parse('Thesis 100h/week').error, /80 hours/);
  // A title can hold words that are also keywords, and numbers.
  assert.equal(val('Trip to Rome 12-15 nov').title, 'Trip to Rome');
  assert.equal(val('Chapter 3 rewrite 4h/week').title, 'Chapter 3 rewrite');
});

test('checking a commitment', () => {
  const ok = T.cleanCommitment({ title: ' Thesis ', hours: 10.1, start_date: '2026-11-02', end_date: '2026-12-15', days: [3, 1, 1] });
  assert.deepEqual(ok, { ok: true, value: { title: 'Thesis', kind: 'work', hours: 10, start_date: '2026-11-02', end_date: '2026-12-15', days: [1, 3], color: 'blue', project_id: null, course_id: null, note: null } });
  assert.equal(T.cleanCommitment({ title: 'Away', kind: 'away', hours: 5, start_date: TODAY, end_date: TODAY }).value.hours, null);
  assert.match(T.cleanCommitment({ title: 'x', hours: 0, start_date: TODAY, end_date: TODAY }).error, /Hours/);
  assert.match(T.cleanCommitment({ title: 'x', hours: 2, start_date: TODAY, end_date: '2026-11-01' }).error, /end on or after/);
  assert.match(T.cleanCommitment({ title: 'x', hours: 2, start_date: '2026-02-30', end_date: TODAY }).error, /start date/);
  assert.match(T.cleanCommitment({ title: 'x', hours: 2, start_date: TODAY, end_date: '2030-01-01' }).error, /Two years/);
  assert.equal(T.cleanCommitment({ title: 'x', hours: 2, start_date: TODAY, end_date: TODAY, days: [1, 2, 3, 4, 5, 6, 7] }).value.days, null);
  // An edit is checked against the stored row: moving the start past the end is refused.
  assert.match(T.cleanCommitment({ start_date: '2027-01-01' }, { partial: true, current: { kind: 'work', start_date: TODAY, end_date: '2026-12-01' } }).error, /end on or after/);
  assert.deepEqual(T.cleanCommitment({ end_date: '2026-12-31' }, { partial: true, current: { kind: 'work', start_date: TODAY, end_date: '2026-12-01' } }).value, { end_date: '2026-12-31' });
});

test('working hours: checked, labelled, default', () => {
  const av = [[[540, 720], [780, 1080]], [[540, 1080]], [[540, 1080]], [[540, 1080]], [[540, 1080]], [[600, 840]], []];
  assert.deepEqual(T.cleanAvailability(av), { ok: true, value: av });
  assert.equal(T.availabilityLabel(av), 'Mon 09:00–12:00, 13:00–18:00 · Tue–Fri 09:00–18:00 · Sat 10:00–14:00 · Sun off');
  assert.match(T.cleanAvailability([[[600, 500]], [], [], [], [], [], []]).error, /Monday: a time range must start before it ends/);
  assert.match(T.cleanAvailability([[[540, 720], [700, 800]], [], [], [], [], [], []]).error, /overlap/);
  assert.match(T.cleanAvailability([[]]).error, /seven days/);
  assert.equal(T.availabilityOf({ availability: null }), T.DEFAULT_AVAILABILITY);
  assert.equal(T.availabilityOf({ availability: 'not json' }), T.DEFAULT_AVAILABILITY);
  assert.deepEqual(T.availabilityOf({ availability: JSON.stringify(av) }), av);
  assert.equal(T.minutesOn(av, '2026-11-02'), 480);   // Monday
  assert.equal(T.minutesOn(av, '2026-11-08'), 0);     // Sunday
});

test('a commitment\'s hours are spread over its days, and stop on days away', () => {
  const av = T.DEFAULT_AVAILABILITY;
  const thesis = { id: 1, kind: 'work', hours: 14, start_date: '2026-11-02', end_date: '2026-11-30', days: null };
  const job = { id: 2, kind: 'work', hours: 12, start_date: '2026-11-02', end_date: '2026-11-30', days: [1, 3] };
  const trip = { id: 3, kind: 'away', start_date: '2026-11-05', end_date: '2026-11-06' };
  const away = T.awayDays([trip]);
  assert.deepEqual([...away], ['2026-11-05', '2026-11-06']);
  assert.equal(T.minutesOfDay(thesis, '2026-11-04', av, away), 120);
  assert.equal(T.minutesOfDay(thesis, '2026-11-05', av, away), 0);
  assert.equal(T.minutesOfDay(job, '2026-11-02', av, away), 360);
  assert.equal(T.minutesOfDay(job, '2026-11-03', av, away), 0);
  assert.equal(T.minutesOfDay(thesis, '2026-12-01', av, away), 0, 'after its end');
  // Without its own days, the hours go on the days you work: Monday to Friday here.
  const weekdays = [...Array(5).fill([[540, 1020]]), [], []];
  assert.equal(T.minutesOfDay(thesis, '2026-11-02', weekdays), 14 * 60 / 5);
  assert.equal(T.minutesOfDay(thesis, '2026-11-07', weekdays), 0);
});

test('a week\'s load: available hours against classes, commitments and to-dos due', () => {
  const at = (key, h) => new Date(`${key}T${String(h).padStart(2, '0')}:00:00Z`).getTime();
  const courses = [{ id: 1, tracked: 1 }, { id: 2, tracked: 0 }];
  const events = [
    { id: 1, course_id: 1, start_at: at('2026-11-02', 9), end_at: at('2026-11-02', 11), status: null },
    { id: 2, course_id: 1, start_at: at('2026-11-03', 9), end_at: at('2026-11-03', 12), status: 'excused' },
    { id: 3, course_id: 2, start_at: at('2026-11-03', 9), end_at: at('2026-11-03', 12), status: null },
    { id: 4, course_id: 1, start_at: at('2026-11-10', 9), end_at: at('2026-11-10', 10), status: null },
  ];
  const commitments = [
    { id: 1, kind: 'work', hours: 7, start_date: '2026-11-01', end_date: '2026-11-30', days: null, project_id: 9 },
    { id: 2, kind: 'away', start_date: '2026-11-14', end_date: '2026-11-15' },
  ];
  const homework = [
    { id: 1, done: 0, due_date: '2026-11-06', effort_minutes: 90 },
    { id: 2, done: 1, due_date: '2026-11-06', effort_minutes: 90 },
    { id: 3, done: 0, due_date: '2026-11-06', effort_minutes: 600, project_id: 9 },   // the thesis budget covers it
    { id: 4, done: 0, due_date: T.NO_DATE, effort_minutes: 60 },
  ];
  const [a, b] = T.weekLoads({ from: TODAY, count: 2, commitments, courses, events, homework, tz: 'UTC', now: at(TODAY, 8) });
  assert.deepEqual([a.start, a.end], ['2026-11-02', '2026-11-08']);
  assert.equal(a.available, 7 * 14 * 60);
  assert.equal(a.classes, 120);
  assert.equal(a.work, 420);
  assert.equal(a.due, 90);
  assert.deepEqual(a.dueItems, [1]);
  assert.equal(a.total, 630);
  assert.equal(a.free, 7 * 14 * 60 - 630);
  assert.equal(a.level, 'light');
  // Away at the weekend: two days less available, and the thesis hours are spread over the five left.
  assert.equal(b.away, 2);
  assert.equal(b.available, 5 * 14 * 60);
  assert.equal(b.work, 300);
  assert.equal(b.classes, 60);
  assert.equal(T.levelOf(0.9), 'tight');
  assert.equal(T.levelOf(1.2), 'over');
  assert.equal(T.weekLoads({ from: TODAY, count: 1, availability: Array(7).fill([]), homework: [{ id: 1, done: 0, due_date: TODAY, effort_minutes: 30 }] })[0].level, 'over');
});

test('the day plan uses your working hours and days away', () => {
  const tz = 'UTC';
  const now = Date.UTC(2026, 10, 4, 7, 0);   // Wednesday 07:00
  const base = { courses: [], events: [], homework: [], now, tz };
  const def = buildPlan(base);
  assert.deepEqual(def.gaps.map((g) => [new Date(g.start).getUTCHours(), new Date(g.end).getUTCHours()]), [[8, 22]]);
  const av = Array.from({ length: 7 }, () => [[540, 720], [780, 1020]]);
  const own = buildPlan({ ...base, availability: av });
  assert.deepEqual(own.gaps.map((g) => [new Date(g.start).getUTCHours(), new Date(g.end).getUTCHours()]), [[9, 12], [13, 17]]);
  assert.equal(own.week.freeHours, 7 * 7);
  const away = buildPlan({ ...base, availability: av, commitments: [{ id: 1, kind: 'away', start_date: TODAY, end_date: '2026-11-05' }] });
  assert.equal(away.away, true);
  assert.deepEqual(away.gaps, []);
  assert.equal(away.week.freeHours, 5 * 7);
  assert.equal(away.week.awayDays, 2);
  const busy = buildPlan({ ...base, commitments: [{ id: 1, kind: 'work', hours: 14, start_date: TODAY, end_date: '2026-12-01', days: null }] });
  assert.equal(busy.week.commitHours, 14);
  assert.equal(busy.week.load, 14 / busy.week.freeHours);
});

// ---------------------------------------------------------------- the API

function setup() {
  const env = { DB: d1(), ASSETS: { fetch: async () => new Response('') } };
  env.DB.raw.exec(`INSERT INTO users (id, google_sub, created_at, threshold, unit) VALUES (1, 'a', 0, 0.8, 'sessions'), (2, 'b', 0, 0.8, 'sessions');
    INSERT INTO sessions VALUES ('s1', 1, 9e15), ('s2', 2, 9e15);
    INSERT INTO courses (id, user_id, key, name, tracked) VALUES (5, 1, 'algo', 'Algo', 1), (6, 2, 'theirs', 'Theirs', 1);`);
  const api = (sid) => async (method, path, body) => {
    const res = await worker.fetch(new Request(`https://app.test${path}`, {
      method, headers: { Cookie: `sid=${sid}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
    }), env);
    return Object.assign(await res.json(), { status: res.status });
  };
  return { env, db: env.DB.raw, me: api('s1'), other: api('s2') };
}
const ok = (r) => { assert.equal(r.status, 200, JSON.stringify(r)); return r; };
const C = { title: 'Thesis', hours: 10, start_date: '2026-11-02', end_date: '2026-12-15' };

test('commitments: create, edit, delete, only your own', async () => {
  const { me, other, db } = setup();
  const c = ok(await me('POST', '/api/commitments', { ...C, days: [1, 3], course_id: 5 }));
  assert.deepEqual([c.title, c.kind, c.hours, c.days, c.color, c.course_id], ['Thesis', 'work', 10, [1, 3], 'blue', 5]);
  assert.equal((await me('POST', '/api/commitments', { ...C, course_id: 6 })).status, 400, 'someone else\'s course');
  assert.equal((await me('POST', '/api/commitments', { ...C, project_id: 99 })).status, 400);
  assert.match((await me('POST', '/api/commitments', { ...C, end_date: '2026-10-01' })).error, /end on or after/);
  const moved = ok(await me('PATCH', `/api/commitments/${c.id}`, { start_date: '2026-11-09', end_date: '2026-12-20' }));
  assert.deepEqual([moved.start_date, moved.end_date, moved.hours, moved.days], ['2026-11-09', '2026-12-20', 10, [1, 3]]);
  assert.match((await me('PATCH', `/api/commitments/${c.id}`, { end_date: '2026-11-01' })).error, /end on or after/);
  const away = ok(await me('PATCH', `/api/commitments/${c.id}`, { kind: 'away' }));
  assert.equal(away.hours, null);
  assert.match((await me('PATCH', `/api/commitments/${c.id}`, { kind: 'work' })).error, /Hours a week/);
  assert.equal((await other('PATCH', `/api/commitments/${c.id}`, { title: 'x' })).status, 404);
  assert.equal((await other('DELETE', `/api/commitments/${c.id}`)).status, 404);
  const data = ok(await me('GET', '/api/data'));
  assert.equal(data.commitments.length, 1);
  assert.equal(data.user.availability, null);
  assert.equal(ok(await other('GET', '/api/data')).commitments.length, 0);
  ok(await me('DELETE', `/api/commitments/${c.id}`));
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM commitments').get().n, 0);
});

test('a linked course or project going away unlinks the commitment; deleting the account removes it', async () => {
  const { me, db } = setup();
  const p = ok(await me('POST', '/api/projects', { name: 'Thesis' }));
  const c = ok(await me('POST', '/api/commitments', { ...C, project_id: p.id, course_id: 5 }));
  ok(await me('DELETE', `/api/projects/${p.id}`));
  db.exec('DELETE FROM courses WHERE id = 5');
  const row = db.prepare('SELECT project_id, course_id FROM commitments WHERE id = ?').get(c.id);
  assert.deepEqual([row.project_id, row.course_id], [null, null]);
  ok(await me('DELETE', '/api/account', { confirm: 'DELETE' }));
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM commitments').get().n, 0);
});

test('working hours are saved, used by /api/plan, and reset with null', async () => {
  const { me } = setup();
  const av = Array.from({ length: 7 }, (_, i) => (i < 5 ? [[540, 1020]] : []));
  assert.match((await me('PUT', '/api/availability', { days: [[[600, 500]], [], [], [], [], [], []] })).error, /start before/);
  assert.deepEqual(ok(await me('PUT', '/api/availability', { days: av })).availability, av);
  assert.deepEqual(ok(await me('GET', '/api/data')).user.availability, av);
  const day = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
  ok(await me('POST', '/api/commitments', { ...C, start_date: day(-30), end_date: day(300) }));
  const plan = ok(await me('GET', '/api/plan?tz=Europe/Paris'));
  assert.deepEqual(plan.timeline.availability, av);
  assert.equal(plan.timeline.commitments.length, 1);
  assert.equal(plan.timeline.weeks.length, 3);
  assert.equal(plan.timeline.weeks[1].available, 5 * 8 * 60);
  assert.equal(plan.timeline.weeks[1].work, 600);
  assert.equal(plan.plan.week.commitHours, 10);
  assert.equal(ok(await me('PUT', '/api/availability', { days: null })).availability, null);
  assert.equal(ok(await me('GET', '/api/data')).user.availability, null);
});

test('the commitments queries use their index', (t) => {
  const { db } = setup();
  for (const [sql, args] of [['SELECT id FROM commitments WHERE user_id = ? ORDER BY start_date, id', [1]], ['SELECT id FROM commitments WHERE user_id = ? AND end_date >= ? ORDER BY start_date, id', [1, '2026-01-01']]]) {
    const plan = db.prepare(`EXPLAIN QUERY PLAN ${sql}`).all(...args).map((r) => r.detail).join(' | ');
    assert.match(plan, /USING (COVERING )?INDEX commitments_user/, sql);
    t.diagnostic(plan);
  }
});
