import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as H from '../public/habits.js';
import * as C from '../public/countdowns.js';

const counts = (...days) => new Map(days.map((d) => (Array.isArray(d) ? d : [d, 1])));
const daily = { schedule: { type: 'daily' }, target: 1 };
// 2026-10-28 is a Wednesday.
const TODAY = '2026-10-28';

test('day keys: arithmetic is the same in any time zone', () => {
  assert.equal(H.addDays('2026-03-28', 2), '2026-03-30');
  assert.equal(H.diffDays('2026-11-01', '2026-10-25'), 7);
  assert.equal(H.dow(TODAY), 3);
  assert.equal(H.weekOf(TODAY), '2026-10-26');
  assert.ok(H.isKey('2026-02-28') && !H.isKey('2026-02-30') && !H.isKey('2026-2-3'));
  assert.equal(H.keyInZone(Date.UTC(2026, 9, 28, 23, 30), 'Europe/Helsinki'), '2026-10-29');
});

test('daily streak: today only breaks it once the day is over', () => {
  const c = counts('2026-10-25', '2026-10-26', '2026-10-27');
  let s = H.habitStats(daily, c, { today: TODAY, start: '2026-10-01' });
  assert.equal(s.streak, 3);
  assert.equal(s.todayDone, false);
  c.set(TODAY, 1);
  s = H.habitStats(daily, c, { today: TODAY, start: '2026-10-01' });
  assert.equal(s.streak, 4);
  assert.equal(s.best, 4);
  s = H.habitStats(daily, counts('2026-10-20', '2026-10-21', '2026-10-22', '2026-10-27'), { today: TODAY, start: '2026-10-01' });
  assert.deepEqual([s.streak, s.best], [1, 3]);
});

test('weekday habits: days off neither count nor break the streak', () => {
  const gym = { schedule: { type: 'days', days: [1, 3, 5] }, target: 1 };
  // Fri 23, Mon 26 done; Wed 28 (today) not yet.
  const s = H.habitStats(gym, counts('2026-10-21', '2026-10-23', '2026-10-26'), { today: TODAY, start: '2026-10-19' });
  assert.equal(s.streak, 3);
  assert.equal(s.due, true);
  assert.equal(H.habitStats(gym, counts(), { today: '2026-10-27' }).due, false, 'Tuesday is a day off');
  // Missed Mon 19: Wed 21 starts a new run.
  assert.equal(H.habitStats(gym, counts('2026-10-21', '2026-10-23', '2026-10-26'), { today: TODAY, start: '2026-10-16' }).best, 3);
});

test('targets: a day is done once its check-ins reach the target', () => {
  const water = { schedule: { type: 'daily' }, target: 8 };
  const s = H.habitStats(water, counts(['2026-10-27', 8], [TODAY, 5]), { today: TODAY, start: '2026-10-27' });
  assert.deepEqual([s.today, s.todayDone, s.streak, s.checkins], [5, false, 1, 13]);
});

test('n times a week: streaks count weeks, the current week only once it is met', () => {
  const read = { schedule: { type: 'weekly', n: 2 }, target: 1 };
  const c = counts('2026-10-13', '2026-10-15', '2026-10-20', '2026-10-22', '2026-10-27');
  let s = H.habitStats(read, c, { today: TODAY, start: '2026-10-12' });
  assert.deepEqual([s.streak, s.unit, s.progress], [2, 'week', { done: 1, of: 2 }]);
  assert.equal(s.due, true);
  c.set(TODAY, 1);
  s = H.habitStats(read, c, { today: TODAY, start: '2026-10-12' });
  assert.deepEqual([s.streak, s.best, s.due], [3, 3, true], 'done today still shows today');
  s = H.habitStats(read, counts('2026-10-26', '2026-10-27'), { today: TODAY });
  assert.equal(s.due, false, 'quota met for the week');
});

test('monthly and every-n-days schedules', () => {
  const m = H.habitStats({ schedule: { type: 'monthly', n: 1 }, target: 1 }, counts('2026-08-03', '2026-09-30', '2026-10-01'), { today: TODAY, start: '2026-08-01' });
  assert.deepEqual([m.streak, m.unit], [3, 'month']);
  const every3 = { schedule: { type: 'interval', n: 3 }, target: 1 };
  let s = H.habitStats(every3, counts('2026-10-19', '2026-10-22', '2026-10-25'), { today: TODAY });
  assert.deepEqual([s.streak, s.due], [3, true]);
  s = H.habitStats(every3, counts('2026-10-19', '2026-10-22', '2026-10-26'), { today: TODAY });
  assert.equal(s.due, false, 'two days since the last time');
  assert.equal(H.habitStats(every3, counts('2026-10-19', '2026-10-25'), { today: TODAY }).streak, 1);
  assert.equal(H.habitStats(every3, counts('2026-10-19', '2026-10-24'), { today: TODAY }).streak, 0, 'missed the 27th');
});

test('rate: the share of planned days done over the last 30 days', () => {
  const s = H.habitStats(daily, counts('2026-10-24', '2026-10-25', '2026-10-27'), { today: TODAY, start: '2026-10-24' });
  assert.equal(s.rate, 3 / 4, 'today is not counted until it is done');
});

test('validation: schedules and fields', () => {
  assert.deepEqual(H.cleanSchedule({ type: 'days', days: [5, 1, 1, 9] }), { type: 'days', days: [1, 5] });
  assert.deepEqual(H.cleanSchedule({ type: 'days', days: [1, 2, 3, 4, 5, 6, 7] }), { type: 'daily' });
  assert.equal(H.cleanSchedule({ type: 'weekly', n: 0 }), null);
  assert.deepEqual(H.cleanHabit({ name: '  Read ', target: 2, color: 'nope' }).value, { name: 'Read', color: 'blue', schedule: { type: 'daily' }, target: 2 });
  assert.equal(H.cleanHabit({ name: '' }).ok, false);
  assert.equal(H.cleanHabit({ target: 0 }, { partial: true }).ok, false);
  assert.deepEqual(H.cleanHabit({ archived: true }, { partial: true }).value, { archived: 1 });
});

test('quick add: habits from a sentence', () => {
  const p = (s) => { const r = H.parseHabit(s); return [r.name, H.scheduleLabel(r.schedule), r.target, r.unit, r.icon]; };
  assert.deepEqual(p('Gym mon wed fri'), ['Gym', 'Mon, Wed, Fri', 1, null, null]);
  assert.deepEqual(p('Read 20 pages 3x a week'), ['Read 20 pages', '3× a week', 1, null, null]);
  assert.deepEqual(p('💧 Water 8 glasses a day'), ['Water', 'Every day', 8, 'glasses', '💧']);
  assert.deepEqual(p('Stretch every other day'), ['Stretch', 'Every other day', 1, null, null]);
  assert.deepEqual(p('Meditate weekdays'), ['Meditate', 'Weekdays', 1, null, null]);
  assert.deepEqual(p('Call home twice a week'), ['Call home', '2× a week', 1, null, null]);
  assert.deepEqual(p('Review notes on monday and thursday'), ['Review notes', 'Mon, Thu', 1, null, null]);
  assert.deepEqual(p('Pushups x3'), ['Pushups', 'Every day', 3, null, null]);
  assert.deepEqual(H.parseHabit('Journal').chips, []);
});

// ---------------------------------------------------------------- countdowns

const NOW = new Date(2026, 9, 28, 14, 0).getTime(); // Wed 28 Oct 2026, 14:00 local
const local = (...a) => new Date(...a).getTime();

test('countdown views: days, today, count-ups and past', () => {
  const exam = { at: local(2026, 11, 12), all_day: 1, repeat: 'none', units: 'auto', after: 'up' };
  assert.deepEqual(C.view(exam, NOW).parts, '45 days');
  assert.equal(C.view({ ...exam, units: 'weeks' }, NOW).parts, '6 w 3 d');
  assert.equal(C.view({ ...exam, units: 'months' }, NOW).parts, '1 mo 14 d');
  assert.equal(C.view({ ...exam, units: 'workdays' }, NOW).parts, '32 working days');
  assert.equal(C.view({ ...exam, at: local(2026, 9, 28) }, NOW).state, 'today');
  const quit = { ...exam, at: local(2026, 8, 1) };
  assert.deepEqual([C.view(quit, NOW).state, C.view(quit, NOW).parts], ['up', '57 days']);
  assert.equal(C.view({ ...quit, after: 'past' }, NOW).state, 'past');
});

test('countdown views: timed ones go to hours, then a live clock', () => {
  const t = { all_day: 0, repeat: 'none', units: 'auto', after: 'up' };
  assert.equal(C.view({ ...t, at: NOW + 5 * 3600000 + 7 * 60000 }, NOW).parts, '5 h 07 min');
  const v = C.view({ ...t, at: NOW + 25 * 60000 + 3000 }, NOW);
  assert.deepEqual([v.parts, v.live], ['25:03', true]);
  assert.equal(C.view({ ...t, at: NOW + 3 * 86400000 + 3723000, units: 'clock' }, NOW).parts, '3d 01:02:03');
  assert.equal(C.view({ ...t, at: NOW + 50 * 3600000, units: 'hours' }, NOW).parts, '50 hours');
});

test('countdowns: repeating dates move on to the next occurrence, with progress since the last', () => {
  const bday = { at: local(2000, 2, 3), all_day: 1, repeat: 'yearly', units: 'days', after: 'up' };
  const { at, prev } = C.occurrence(bday, NOW);
  assert.equal(at, local(2027, 2, 3));
  assert.equal(prev, local(2026, 2, 3));
  const v = C.view(bday, NOW);
  assert.equal(v.state, 'future');
  assert.ok(v.progress > 0.6 && v.progress < 0.7);
  assert.equal(C.occurrence({ ...bday, at: local(2026, 0, 31), repeat: 'monthly' }, NOW).at, local(2026, 9, 31));
  assert.equal(C.occurrence({ ...bday, at: local(2024, 1, 29) }, local(2026, 2, 1)).at, local(2027, 1, 28), 'Feb 29 falls on the 28th');
  assert.equal(C.view({ ...bday, at: local(2020, 9, 28) }, NOW).state, 'today');
});

test('countdowns: sorted soonest first, then count-ups, then past', () => {
  const base = { all_day: 1, repeat: 'none', units: 'auto', after: 'up' };
  const list = [{ id: 1, ...base, at: local(2026, 9, 1) }, { id: 2, ...base, at: local(2026, 11, 1) }, { id: 3, ...base, at: local(2026, 10, 1) },
    { id: 4, ...base, at: local(2026, 8, 1), after: 'past' }];
  assert.deepEqual(C.sortCountdowns(list, NOW).map((x) => x.c.id), [3, 2, 1, 4]);
});

test('quick add: countdowns from a sentence', () => {
  const p = (s) => { const r = C.parseCountdown(s, NOW); return r && [r.title, new Date(r.at).toString().slice(0, 21), r.all_day, r.repeat]; };
  assert.deepEqual(p('Algo exam 12 dec 9:00'), ['Algo exam', 'Sat Dec 12 2026 09:00', 0, 'none']);
  assert.deepEqual(p("Mum's birthday 3 march"), ["Mum's birthday", 'Wed Mar 03 2027 00:00', 1, 'yearly']);
  assert.deepEqual(p('Trip to Lapland in 3 weeks'), ['Trip to Lapland', 'Wed Nov 18 2026 00:00', 1, 'none']);
  assert.deepEqual(p('Deadline friday 5pm'), ['Deadline', 'Fri Oct 30 2026 17:00', 0, 'none']);
  assert.deepEqual(p('Thesis due 2027-05-31'), ['Thesis due', 'Mon May 31 2027 00:00', 1, 'none']);
  assert.deepEqual(p('Flight 24/12 at 7.45'), ['Flight', 'Thu Dec 24 2026 07:45', 0, 'none']);
  assert.deepEqual(p('Rent the 1st monthly'), ['Rent', 'Sun Nov 01 2026 00:00', 1, 'monthly']);
  assert.deepEqual(p('Christmas dec 25th'), ['Christmas', 'Fri Dec 25 2026 00:00', 1, 'none']);
  const since = C.parseCountdown('No coffee since 1 sep', NOW);
  assert.deepEqual([since.title, since.since, new Date(since.at).getFullYear()], ['No coffee', true, 2026]);
  assert.equal(C.parseCountdown('Something someday', NOW), null);
  assert.equal(C.parseCountdown('Lab 31/02', NOW), null);
});

test('countdown validation', () => {
  const ok = C.cleanCountdown({ title: ' Exam ', at: NOW });
  assert.deepEqual(ok.value, { title: 'Exam', at: NOW, all_day: 1, repeat: 'none', units: 'auto', after: 'up', color: 'blue' });
  assert.equal(C.cleanCountdown({ title: 'x', at: 'soon' }).ok, false);
  assert.equal(C.cleanCountdown({ units: 'eons' }, { partial: true }).ok, false);
  assert.deepEqual(C.cleanCountdown({ pinned: true, start_at: null }, { partial: true }).value, { pinned: 1, start_at: null });
});

// ---------------------------------------------------------------- API

test('habits and countdowns API: create, check in, edit, isolate users, and summarise in /api/plan', async () => {
  const { d1 } = await import('./d1-shim.js');
  const { createToken } = await import('../src/tokens.js');
  const { createHabit, logHabit, updateHabit, deleteHabit } = await import('../src/habits.js');
  const { createCountdown, updateCountdown } = await import('../src/countdowns.js');
  const worker = (await import('../src/worker.js')).default;
  const env = { DB: d1() };
  env.DB.raw.exec(`INSERT INTO users (id, google_sub, created_at, threshold, unit) VALUES (1, 'a', 0, 0.8, 'sessions'), (2, 'b', 0, 0.8, 'sessions')`);
  const me = { id: 1 }, other = { id: 2 };

  const gym = await createHabit(env, me, { name: 'Gym', schedule: { type: 'days', days: [1, 3, 5] }, color: 'green' });
  assert.deepEqual([gym.name, gym.schedule, gym.target, gym.position, gym.archived], ['Gym', { type: 'days', days: [1, 3, 5] }, 1, 0, false]);
  assert.equal((await createHabit(env, me, { name: 'Read' })).position, 1);
  await assert.rejects(createHabit(env, me, { name: 'x', schedule: { type: 'weekly', n: 9 } }), { status: 400 });

  const today = new Date().toISOString().slice(0, 10);
  await logHabit(env, me, gym.id, { day: today, count: 1 });
  await logHabit(env, me, gym.id, { day: today, count: 2 });
  await logHabit(env, me, gym.id, { day: H.addDays(today, -2), count: 1 });
  await logHabit(env, me, gym.id, { day: H.addDays(today, -2), count: 0 });
  await assert.rejects(logHabit(env, other, gym.id, { day: today, count: 1 }), { status: 404 });
  await assert.rejects(logHabit(env, me, gym.id, { day: '2026-02-30', count: 1 }), { status: 400 });
  await assert.rejects(logHabit(env, me, gym.id, { day: H.addDays(today, 5), count: 1 }), { status: 400 });
  assert.deepEqual(env.DB.raw.prepare('SELECT day, count FROM habit_logs').all().map((r) => ({ ...r })), [{ day: today, count: 2 }]);

  assert.equal((await updateHabit(env, me, gym.id, { target: 2, schedule: { type: 'daily' } })).target, 2);
  await assert.rejects(updateHabit(env, other, gym.id, { name: 'Mine' }), { status: 404 });

  const exam = await createCountdown(env, me, { title: 'Exam', at: Date.now() + 10 * 86400000, units: 'weeks', homework_id: 999 });
  assert.deepEqual([exam.units, exam.homework_id, exam.pinned], ['weeks', null, 0], 'a link to someone else\'s homework is dropped');
  assert.equal((await updateCountdown(env, me, exam.id, { pinned: true })).pinned, 1);
  await assert.rejects(updateCountdown(env, other, exam.id, { pinned: false }), { status: 404 });

  // The logs query reads habits first, then each one's logs by key (D1 bills rows read).
  const plan = env.DB.raw.prepare(`EXPLAIN QUERY PLAN SELECT l.habit_id, l.day, l.count FROM habits h CROSS JOIN habit_logs l ON l.habit_id = h.id
    WHERE h.user_id = ? AND l.day >= ? ORDER BY l.day`).all(1, '2026-01-01').map((r) => r.detail).join(' | ');
  assert.match(plan, /SEARCH h USING (COVERING )?INDEX habits_user.*SEARCH l USING PRIMARY KEY/);

  const { token } = await createToken(env, me, { name: 'cli', scope: 'read' }, () => 'x'.repeat(32));
  const call = (path) => worker.fetch(new Request(`https://app.test${path}`, { headers: { Authorization: `Bearer ${token}` } }), env);
  const data = await (await call('/api/data')).json();
  assert.equal(data.habits.list.length, 2);
  assert.deepEqual(data.habits.logs, [{ habit_id: gym.id, day: today, count: 2 }]);
  assert.equal(data.countdowns[0].title, 'Exam');
  const p = await (await call('/api/plan?tz=UTC')).json();
  const g = p.habits.find((h) => h.name === 'Gym');
  assert.deepEqual([g.done, g.streak, g.schedule, g.last7.length], [true, 1, 'Every day', 7]);
  assert.deepEqual([p.countdowns[0].title, p.countdowns[0].days, p.countdowns[0].pinned], ['Exam', 10, true]);

  await deleteHabit(env, me, gym.id);
  assert.equal(env.DB.raw.prepare('SELECT COUNT(*) AS n FROM habit_logs').get().n, 0, 'logs go with the habit');
});
