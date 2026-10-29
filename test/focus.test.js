import { test } from 'node:test';
import assert from 'node:assert/strict';
import { d1 } from './d1-shim.js';
import * as F from '../public/focus.js';
import { logFocus, listFocus, deleteFocus, saveFocusSettings, focusSettings } from '../src/focus.js';
import { createToken } from '../src/tokens.js';
import worker from '../src/worker.js';

const MIN = 60000;
const S = { ...F.DEFAULT_SETTINGS, longEvery: 2 };
const T0 = new Date(2026, 9, 21, 10, 0).getTime(); // local time, so day boundaries hold in any TZ

test('settings: out-of-range or missing values take their defaults', () => {
  assert.deepEqual(F.cleanSettings(null), F.DEFAULT_SETTINGS);
  const s = F.cleanSettings({ focus: 50, short: 0, long: '20', longEvery: 3.5, goal: 30, sound: false, notify: 'no', extra: 1 });
  assert.equal(s.focus, 50);
  assert.equal(s.short, 5);
  assert.equal(s.long, 20);
  assert.equal(s.longEvery, 4);
  assert.equal(s.goal, 8);
  assert.equal(s.sound, false);
  assert.equal(s.notify, true);
  assert.equal('extra' in s, false);
});

test('timer: pauses are not counted, and a finished focus is recorded once with its target', () => {
  const target = { homework_id: 7, course_id: 3, label: 'TP 3' };
  let t = F.start({ ...F.idle(), target }, S, T0, 'run1');
  assert.equal(t.phase, 'focus');
  assert.equal(F.remaining(t, T0 + 5 * MIN), 20 * MIN);
  t = F.pause(t, T0 + 5 * MIN);
  assert.equal(F.remaining(t, T0 + 50 * MIN), 20 * MIN, 'frozen while paused');
  t = F.resume(t, T0 + 15 * MIN);
  assert.equal(t.endsAt, T0 + 35 * MIN);
  assert.ok(!F.isDue(t, T0 + 34 * MIN));
  assert.ok(F.isDue(t, T0 + 35 * MIN));

  const { log, next } = F.finish(t, S, T0 + 35 * MIN + 500, 'run2');
  assert.deepEqual(log, {
    client_id: 'run1', started_at: T0, ended_at: T0 + 35 * MIN, planned_seconds: 1500, focus_seconds: 1500, completed: true,
    homework_id: 7, course_id: 3, label: 'TP 3', tags: [],
  });
  assert.equal(next.phase, 'short', 'breaks start by themselves by default');
  assert.equal(next.run, 'run2');
  assert.equal(next.done, 1);
  assert.deepEqual(next.target, target);
});

test('timer: a long break after every longEvery focus, then the count starts again', () => {
  let t = F.start(F.idle(), S, T0, 'a');
  t = F.finish(t, S, t.endsAt, 'b').next;             // short break
  t = F.finish(t, { ...S, autoFocus: true }, t.endsAt, 'c').next;
  assert.equal(t.phase, 'focus');
  t = F.finish(t, S, t.endsAt, 'd').next;
  assert.equal(t.phase, 'long');
  assert.equal(t.done, 2);
  t = F.finish(t, S, t.endsAt, 'e').next;
  assert.deepEqual([t.phase, t.next, t.done], ['idle', 'focus', 0], 'autoFocus is off: waits for Start');
});

test('timer: skip, stop, short focus, and a phase that ended long ago', () => {
  const t = F.start(F.idle(), S, T0, 'a');
  const skipped = F.finish(t, S, T0 + 10 * MIN, 'b', { early: true });
  assert.equal(skipped.log.focus_seconds, 600);
  assert.equal(skipped.log.completed, false);
  assert.equal(skipped.next.done, 0, 'an early end doesn\'t count toward the long break');
  assert.equal(skipped.next.phase, 'short');

  assert.equal(F.stop(t, T0 + 30000).log, null, 'under a minute is not recorded');
  const stopped = F.stop(t, T0 + 3 * MIN);
  assert.equal(stopped.log.focus_seconds, 180);
  assert.deepEqual([stopped.next.phase, stopped.next.next], ['idle', 'focus']);

  // Back the next morning: the focus is recorded up to its end, but no break starts.
  const late = F.finish(t, S, T0 + 20 * 3600000, 'c');
  assert.equal(late.log.ended_at, T0 + 25 * MIN);
  assert.equal(late.log.completed, true);
  assert.deepEqual([late.next.phase, late.next.next], ['idle', 'short']);
});

test('stats: today, streak, week against last week, breakdowns', () => {
  const at = (daysAgo, h) => new Date(2026, 9, 21 - daysAgo, h).getTime(); // 21 Oct 2026 is a Wednesday
  const s = (daysAgo, h, min, extra = {}) => ({ started_at: at(daysAgo, h), focus_seconds: min * 60, completed: 1, course_id: 1, homework_id: null, label: null, ...extra });
  const sessions = [
    s(0, 9, 25, { homework_id: 5, label: 'TP 3' }), s(0, 14, 25, { homework_id: 5, label: 'TP 3' }), s(0, 15, 10, { completed: 0, course_id: null }),
    s(1, 14, 50, { course_id: 2 }), s(2, 14, 25),
    s(4, 10, 25),                // a gap on day 3 ends the streak
    s(7, 9, 30), s(8, 9, 90),    // last week: Wednesday and Tuesday
  ];
  const st = F.focusStats(sessions, { now: at(0, 18), range: 7 });
  assert.deepEqual(st.today, { minutes: 60, count: 2 });
  assert.equal(st.streak, 3);
  assert.deepEqual(st.week.map((d) => Math.round(d.minutes)), [25, 50, 60, 0, 0, 0, 0]);
  assert.deepEqual(st.week.map((d) => d.future), [false, false, false, true, true, true, true]);
  assert.equal(st.thisWeek, 135);
  assert.equal(st.lastWeek, 120, 'last week up to Wednesday');
  assert.deepEqual(st.range.courses.map((c) => [c.course_id, c.minutes]), [[1, 100], [2, 50], [null, 10]]);
  assert.deepEqual(st.range.homework.map((h) => [h.label, h.minutes, h.count]), [['TP 3', 50, 2]]);
  assert.equal(st.range.activeDays, 4);
  assert.equal(st.range.peakHour, 14);
  assert.equal(st.best.minutes, 90);

  // Today without focus yet doesn't break yesterday's streak.
  assert.equal(F.focusStats(sessions.slice(3), { now: at(0, 18) }).streak, 2);
  assert.equal(F.minutesByHomework(sessions).get(5), 50);
});

function setup() {
  const env = { DB: d1() };
  const db = env.DB.raw;
  db.exec(`INSERT INTO users (id, google_sub, created_at, threshold, unit) VALUES (1, 'a', 0, 0.8, 'sessions'), (2, 'b', 0, 0.8, 'sessions');
    INSERT INTO courses (id, user_id, key, name, tracked, tag) VALUES (3, 1, 'algo', 'Algo', 1, 'normal'), (4, 2, 'x', 'Theirs', 1, 'normal');
    INSERT INTO homework (id, user_id, course_id, title, notes, due_date, tz, due_at, done, created_at, updated_at)
      VALUES (7, 1, 3, 'TP 3', '', '2026-10-30', 'Europe/Paris', 0, 0, 0, 0), (8, 2, 4, 'Not yours', '', '2026-10-30', 'Europe/Paris', 0, 0, 0, 0);`);
  return { env, db, user: { id: 1 } };
}

const now = Date.now();
const entry = (extra = {}) => ({
  client_id: 'abcdef1234567890', started_at: now - 30 * MIN, ended_at: now - 5 * MIN, planned_seconds: 1500, focus_seconds: 1500,
  completed: true, homework_id: 7, ...extra,
});

test('server: records a session once, takes course and label from the homework, and checks ownership', async () => {
  const { env, db, user } = setup();
  const a = await logFocus(env, user, entry());
  assert.equal(a.course_id, 3);
  assert.equal(a.label, 'TP 3');
  assert.equal(a.completed, 1);
  const again = await logFocus(env, user, entry({ focus_seconds: 999 }));
  assert.equal(again.id, a.id, 'a retry or a second tab gives the same row');
  assert.equal(again.focus_seconds, 1500);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM focus_sessions').get().n, 1);

  const other = await logFocus(env, user, entry({ client_id: 'other-run-1234', homework_id: 8, course_id: 4, label: '' }));
  assert.equal(other.homework_id, null, "another user's homework is ignored");
  assert.equal(other.course_id, null);
  assert.equal(other.label, null);

  // Deleting the homework keeps the session, with its label and course.
  db.exec('DELETE FROM homework WHERE id = 7');
  const [kept] = await listFocus(env, user, 0);
  assert.deepEqual([kept.homework_id, kept.label, kept.course_id], [null, 'TP 3', 3]);
  assert.equal((await listFocus(env, { id: 2 }, 0)).length, 0);
  await assert.rejects(deleteFocus(env, { id: 2 }, kept.id), { status: 404 });
  await deleteFocus(env, user, kept.id);
  assert.equal((await listFocus(env, user, 0)).length, 1);
});

test('server: rejects impossible sessions', async () => {
  const { env, user } = setup();
  const bad = async (extra, re) => assert.rejects(logFocus(env, user, entry(extra)), { status: 400, message: re });
  await bad({ client_id: 'x' }, /client_id/);
  await bad({ client_id: 'has spaces in it' }, /client_id/);
  await bad({ ended_at: now + 3600000 }, /time/);
  await bad({ started_at: now - 40 * 86400000 }, /time/);
  await bad({ started_at: '1' }, /time/);
  await bad({ focus_seconds: 30 }, /focus/);
  await bad({ focus_seconds: 3600 }, /focus/, 'more than the time between start and end');
  await bad({ planned_seconds: 10 }, /planned/);
});

test('server: settings are cleaned and saved on the account', async () => {
  const { env, db, user } = setup();
  const saved = await saveFocusSettings(env, user, { focus: 50, goal: 0, autoFocus: true });
  assert.equal(saved.focus, 50);
  assert.equal(saved.goal, 8);
  assert.equal(saved.autoFocus, true);
  const row = db.prepare('SELECT focus_settings FROM users WHERE id = 1').get();
  assert.deepEqual(focusSettings(row), saved);
  assert.deepEqual(focusSettings({ focus_settings: '{broken' }), F.DEFAULT_SETTINGS);
});

test('summary for scripts: days are those of the time zone, across a DST change', () => {
  const tz = 'Europe/Helsinki';
  // Sunday 29 March 2026 is 23 hours long in Helsinki (clocks go forward at 03:00).
  const now = Date.parse('2026-03-30T08:00:00Z');                 // Mon 11:00 local
  const s = (iso, min, extra = {}) => ({ started_at: Date.parse(iso), focus_seconds: min * 60, completed: 1, course_id: 1, ...extra });
  const sessions = [
    s('2026-03-29T22:30:00Z', 25),                                 // Mon 01:30 local: today, though still Sunday in UTC
    s('2026-03-30T07:00:00Z', 10, { completed: 0, course_id: null }),
    s('2026-03-28T22:30:00Z', 50),                                 // Sun 00:30 local (UTC+2 until 03:00)
    s('2026-03-27T12:00:00Z', 25),                                 // Fri: Saturday is empty, so the streak is 2
    s('2026-03-10T12:00:00Z', 25),                                 // outside the last 7 days
  ];
  const f = F.focusSummary(sessions, { now, tz });
  assert.deepEqual(f.today, { minutes: 35, pomodoros: 1 });
  assert.equal(f.streak, 2);
  assert.equal(f.last7.minutes, 110);
  assert.deepEqual(f.last7.byDay.map((d) => d.date), ['2026-03-24', '2026-03-25', '2026-03-26', '2026-03-27', '2026-03-28', '2026-03-29', '2026-03-30']);
  assert.deepEqual(f.last7.byDay.map((d) => d.minutes), [0, 0, 0, 25, 0, 50, 35]);
  assert.deepEqual(f.last7.byCourse, [{ course_id: 1, minutes: 100 }, { course_id: null, minutes: 10 }]);
});

test('tokens: focus is readable (and in the plan) but not writable', async () => {
  const { env } = setup();
  await logFocus(env, { id: 1 }, entry());
  const rnd = () => 'y'.repeat(30) + Math.random().toString(36).slice(2);
  const { token } = await createToken(env, { id: 1 }, { name: 'Claude Code', scope: 'write' }, rnd);
  const call = (path, init = {}) => worker.fetch(new Request(`https://app.test${path}`, { ...init, headers: { Authorization: `Bearer ${token}` } }), env);
  const list = await (await call('/api/focus?since=0')).json();
  assert.equal(list.sessions.length, 1);
  const plan = await (await call('/api/plan?tz=Europe/Paris')).json();
  assert.equal(plan.focus.today.minutes + plan.focus.last7.minutes >= 25, true);
  assert.equal(plan.focus.settings.focus, 25);
  assert.equal((await call('/api/focus', { method: 'POST', body: '{}' })).status, 403);
  assert.equal((await call('/api/focus/settings', { method: 'PUT', body: '{}' })).status, 403);
});

test('assetlinks.json: only once the app\'s key fingerprint is set', async () => {
  const get = (vars) => worker.fetch(new Request('https://app.test/.well-known/assetlinks.json'), { ...vars });
  assert.equal((await get({ ANDROID_PACKAGE: 'com.knhdixh.attendance80', ANDROID_CERT_SHA256: '' })).status, 404);
  const res = await get({ ANDROID_PACKAGE: 'com.knhdixh.attendance80', ANDROID_CERT_SHA256: 'aa:bb, CC:DD' });
  assert.deepEqual(await res.json(), [{
    relation: ['delegate_permission/common.handle_all_urls', 'delegate_permission/common.use_as_origin'],
    target: { namespace: 'android_app', package_name: 'com.knhdixh.attendance80', sha256_cert_fingerprints: ['AA:BB', 'CC:DD'] },
  }]);
});

test('tasks and tags: cleaned in settings, recorded with the session, and counted in the stats', async () => {
  const s = F.cleanSettings({ tasks: ['  Thesis:  reading ', 'thesis: READING', '', 42, 'Gym'], tags: ['#Reading', 'reading', ' deep  work ', 'x'.repeat(40)] });
  assert.deepEqual(s.tasks, ['Thesis: reading', 'Gym']);
  assert.deepEqual(s.tags, ['reading', 'deep work', 'x'.repeat(24)]);
  assert.equal(F.cleanSettings({ tags: Array.from({ length: 30 }, (_, i) => `t${i}`) }).tags.length, F.MAX_TAGS);

  let t = F.start({ ...F.idle(), target: { label: 'Thesis: reading', tags: ['Reading', 'reading', 'deep work'] } }, S, T0, 'run-tags');
  const { log } = F.finish(t, S, t.endsAt, 'next');
  assert.deepEqual(log.tags, ['reading', 'deep work']);
  assert.equal(log.homework_id, null);

  const st = F.focusStats([
    { started_at: T0, focus_seconds: 1500, completed: 1, label: 'Thesis: reading', tags: ['reading', 'deep work'] },
    { started_at: T0 + 3600000, focus_seconds: 600, completed: 0, label: 'Gym', tags: ['reading'] },
  ], { now: T0 + 4 * 3600000 });
  assert.deepEqual(st.range.tags, [{ tag: 'reading', minutes: 35 }, { tag: 'deep work', minutes: 25 }]);
  assert.deepEqual(st.range.homework.map((h) => h.label), ['Thesis: reading', 'Gym']);

  const { env, user } = setup();
  const row = await logFocus(env, user, entry({ client_id: 'tags-run-12345', homework_id: null, label: 'Thesis: reading', tags: ['#Reading', 'a', 'b', 'c', 'd', 'e'] }));
  assert.deepEqual(row.tags, ['reading', 'a', 'b', 'c', 'd'], 'cleaned, five at most');
  assert.deepEqual((await listFocus(env, user, 0))[0].tags, ['reading', 'a', 'b', 'c', 'd']);
  await assert.rejects(logFocus(env, user, entry({ client_id: 'tags-run-67890', tags: 'reading' })), { status: 400 });
  const saved = await saveFocusSettings(env, user, { tasks: ['Thesis'], tags: ['Reading'] });
  assert.deepEqual([saved.tasks, saved.tags, saved.focus], [['Thesis'], ['reading'], 25]);
  assert.deepEqual((await saveFocusSettings(env, { ...user, focus_settings: JSON.stringify(saved) }, { goal: 6 })).tasks, ['Thesis'], 'other settings keep the lists');
});

test('time adjustments: the next length while idle, the end of a running phase', () => {
  let t = F.setLength(F.idle(), S, 50);
  assert.equal(t.custom, 50);
  assert.equal(F.nextLength(t, S), 50);
  assert.equal(F.setLength(t, S, 25).custom, undefined, 'back to the setting');
  assert.equal(F.adjust(t, S, 5, T0).custom, 55);
  assert.equal(F.adjust(F.idle(), S, -30, T0).custom, 1, 'at least a minute');
  assert.equal(F.setLength(F.idle(), S, 500).custom, F.MAX_PHASE_MIN);

  t = F.start(t, S, T0, 'r');
  assert.equal(t.endsAt, T0 + 50 * MIN);
  assert.equal(t.custom, undefined, 'only for that phase');
  const longer = F.adjust(t, S, 5, T0 + 10 * MIN);
  assert.equal(longer.endsAt, T0 + 55 * MIN);
  assert.equal(F.adjust(t, S, -60, T0 + 10 * MIN).endsAt, T0 + 11 * MIN, 'a minute left at least');
  const paused = F.pause(t, T0 + 10 * MIN);
  assert.equal(F.remaining(F.adjust(paused, S, -5, T0 + 20 * MIN), T0 + 20 * MIN), 35 * MIN, 'paused: from the pause');
  assert.equal(F.finish(longer, S, longer.endsAt, 'n').log.planned_seconds, 55 * 60);
  // A break after an adjusted focus is the usual length again.
  assert.equal(F.finish(longer, S, longer.endsAt, 'n').next.endsAt - longer.endsAt, 5 * MIN);
});

test('timer: typed times, and the time left set to the second within the limits', () => {
  assert.equal(F.parseClock('25'), 25 * MIN);
  assert.equal(F.parseClock(' 12:30 '), 12 * MIN + 30000);
  assert.equal(F.parseClock('1:05:00'), 65 * MIN);
  for (const bad of ['', 'abc', '2:75', '1:2:3:4', '-5']) assert.equal(F.parseClock(bad), null, bad);

  assert.equal(F.setRemaining(F.idle(), S, 37 * MIN, T0).custom, 37, 'idle: the next length, in minutes');
  const t = F.start(F.idle(), S, T0, 'r');
  assert.equal(F.setRemaining(t, S, 12 * MIN + 30000, T0 + MIN).endsAt, T0 + 13 * MIN + 30000);
  assert.equal(F.setRemaining(t, S, 5000, T0 + MIN).endsAt, T0 + 2 * MIN, 'at least a minute left');
  assert.equal(F.setRemaining(t, S, 500 * MIN, T0).endsAt, T0 + F.MAX_PHASE_MIN * MIN, 'at most MAX_PHASE_MIN in all');
  const paused = F.pause(t, T0 + 10 * MIN);
  assert.equal(F.remaining(F.setRemaining(paused, S, 3 * MIN, T0 + 20 * MIN), T0 + 30 * MIN), 3 * MIN, 'paused: counts from the pause');
});
