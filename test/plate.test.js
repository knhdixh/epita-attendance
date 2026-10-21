import { test } from 'node:test';
import assert from 'node:assert/strict';
import { d1 } from './d1-shim.js';
import { parseICS, parseDuration } from '../src/ics.js';
import { addFile, addLink, updateCalendar, sisuSession } from '../src/calendars.js';
import { parseCalendarLink } from '../src/calendar-link.js';
import { createCatchup } from '../src/catchups.js';
import { createToken, userFromToken, tokenAllows, revokeToken } from '../src/tokens.js';
import { buildPlan, localMidnight } from '../public/plan.js';
import { statsByCourse } from '../public/stats.js';
import worker from '../src/worker.js';

const H = 3600000;
const DAY = 24 * H;
const t = (ms) => new Date(ms).toISOString().replace(/[-:]/g, '').slice(0, 15) + 'Z';

// A Sisu-like calendar: DTSTART + DURATION, codes in the title, a cancelled session.
const sisu = (list) => ['BEGIN:VCALENDAR', 'PRODID:-//Funidata//SISU//FI', ...list.flatMap(([uid, summary, start, status]) => [
  'BEGIN:VEVENT', `DTSTART:${t(start)}`, 'DURATION:PT1H45M', `SUMMARY:${summary}`, 'TZID:Europe/Helsinki',
  ...(status ? [`STATUS:${status}`] : []), 'LOCATION:TAU Tietotalo TB104', `UID:${uid}`, 'END:VEVENT']), 'END:VCALENDAR'].join('\r\n');

function setup() {
  const env = { DB: d1() };
  env.DB.raw.exec("INSERT INTO users (id, google_sub, created_at, threshold, unit) VALUES (1, 'a', 0, 0.8, 'sessions')");
  return { env, db: env.DB.raw, user: { id: 1, threshold: 0.8, unit: 'sessions' } };
}

test('DURATION gives the end, and since drops older events before parsing', () => {
  assert.equal(parseDuration('PT1H45M'), 105 * 60000);
  assert.equal(parseDuration('P1DT2H'), 26 * H);
  assert.equal(parseDuration('-PT15M'), -15 * 60000);
  assert.equal(parseDuration('P'), null);
  const text = sisu([['a', 'X.1 Old', Date.UTC(2024, 8, 3, 11)], ['b', 'X.1 New', Date.UTC(2026, 8, 3, 11)]]);
  const all = parseICS(text).events;
  assert.equal(all.length, 2);
  assert.equal(all[1].end - all[1].start, 105 * 60000);
  assert.deepEqual(parseICS(text, { since: '20260101' }).events.map((e) => e.uid), ['b']);
});

test('Sisu titles: the course is the code, the session says what kind it is', () => {
  assert.deepEqual(sisuSession('COMP.CS.410, Operating Systems and Concurrency, Lectures 24.8.–5.12.2026 - Common - Lecture: Luennot 2 / Luennot 1 - TAU Festia FA133 (100)'),
    { key: 'sisu:comp.cs.410', course: 'Operating Systems and Concurrency', summary: 'Operating Systems and Concurrency · Lecture: Luennot 2 / Luennot 1' });
  assert.equal(sisuSession('AUT.130, Introduction to Control, Lectures 24.8.–31.12.2026 - Common - Exercise:  - TAU Konetalo K1320 (50)').summary,
    'Introduction to Control · Exercise');
  assert.equal(sisuSession('EE.ELE.250, Microcontrollers, Lectures - English implementation 24.8.–6.12.2026 - Groups - Group 1: Online').summary,
    'Microcontrollers · Group 1: Online');
  assert.deepEqual(sisuSession('DATA.DB.100 Q&A'), { key: 'sisu:data.db.100', course: 'DATA.DB.100', summary: 'DATA.DB.100 · Q&A' });
  assert.equal(sisuSession('Algorithmique'), null);
});

test('Sisu links are recognised; Sisu calendars skip attendance and old history', async () => {
  const link = parseCalendarLink('https://sisu.tuni.fi:443/ilmo/api/calendar-share/71a64b90-c42c-4b4a-a440-0defef5be142');
  assert.equal(link.sisu, true);
  assert.equal(link.url, 'https://sisu.tuni.fi/ilmo/api/calendar-share/71a64b90-c42c-4b4a-a440-0defef5be142');

  const { env, db, user } = setup();
  const now = Date.now();
  const r = await addFile(env, user, { ics: sisu([
    ['old', 'DATA.ML.200, Deep Learning, Lectures - Common - Lecture: ', now - 400 * DAY],
    ['l1', 'DATA.ML.200, Deep Learning, Lectures - Common - Lecture: ', now + DAY],
    ['g3', 'DATA.ML.200, Deep Learning, Lectures - Groups - Group 3: ', now + 2 * DAY],
    ['x', 'CANCELLED! DATA.ML.200, Deep Learning, Lectures - Common - Lecture: ', now + 3 * DAY, 'CANCELLED'],
  ]), name: 'Tampere' });
  assert.equal(r.total, 2);
  assert.deepEqual(db.prepare('SELECT name, tracked, attendance FROM courses').all().map((c) => ({ ...c })),
    [{ name: 'Deep Learning', tracked: 1, attendance: 0 }]);

  // Not in the attendance maths; turning attendance on applies to the calendar's courses.
  const courses = db.prepare('SELECT * FROM courses').all();
  const events = db.prepare('SELECT * FROM events').all().map((e) => ({ ...e, status: 'absent' }));
  assert.equal(statsByCourse(courses, events, { now }).overall.total, 0);
  await updateCalendar(env, user, r.calendar, { attendance: true });
  assert.equal(db.prepare('SELECT attendance FROM courses').get().attendance, 1);
});

test('catch-ups: due at the next session of the course, one per session, with the recording link', async () => {
  const { env, db, user } = setup();
  const now = Date.now();
  const missed = now - DAY, next = Math.floor((now + 2 * DAY) / 1000) * 1000; // .ics times are whole seconds
  await addFile(env, user, { ics: sisu([['m', 'COMP.CS.130, Functional programming, X - Common - Lecture: ', missed],
    ['n', 'COMP.CS.130, Functional programming, X - Common - Lecture: ', next]]), attendance: true });
  db.exec("UPDATE courses SET recording_url = 'https://panopto.example/fp'");
  const ev = db.prepare("SELECT id FROM events WHERE uid = 'm'").get();
  const c = await createCatchup(env, user, { event_id: ev.id, tz: 'Europe/Helsinki' }, 'https://app');
  assert.equal(c.kind, 'catchup');
  assert.equal(c.title, 'Catch up: Functional programming · Lecture');
  assert.equal(c.due_at, next);
  assert.equal(c.effort_minutes, 105);
  assert.match(c.notes, /^Recording: https:\/\/panopto\.example\/fp\n/);
  const again = await createCatchup(env, user, { event_id: ev.id, tz: 'Europe/Helsinki' }, 'https://app');
  assert.equal(again.id, c.id);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM homework').get().n, 1);
  await assert.rejects(createCatchup(env, { id: 2 }, { event_id: ev.id }), { status: 404 });
});

test('plan: now and next, free time with to-dos fitted in, clashes and load', () => {
  const tz = 'Europe/Helsinki';
  const now = Date.parse('2026-10-21T08:30:00Z'); // 11:30 in Helsinki
  const mid = localMidnight(now, tz);
  assert.equal(new Date(mid).toISOString(), '2026-10-20T21:00:00.000Z');
  const at = (h) => mid + h * H;
  const courses = [{ id: 1, name: 'DL', tracked: 1 }, { id: 2, name: 'Algo', tracked: 1 }, { id: 3, name: 'Hidden', tracked: 0 }];
  const events = [
    { id: 10, course_id: 1, start_at: at(10), end_at: at(12) },        // now
    { id: 11, course_id: 2, start_at: at(14), end_at: at(16) },
    { id: 12, course_id: 1, start_at: at(15), end_at: at(17), status: 'absent' }, // planned skip: free
    { id: 13, course_id: 3, start_at: at(18), end_at: at(20) },        // untracked: free
    { id: 14, course_id: 1, start_at: at(24 + 9), end_at: at(24 + 11) },
    { id: 15, course_id: 2, start_at: at(24 + 10), end_at: at(24 + 12) }, // clashes with 14
  ];
  const homework = [
    { id: 1, title: 'late', due_at: now - DAY, done: 0 },
    { id: 2, title: 'big', due_at: now + 2 * DAY, effort_minutes: 300, done: 0 },
    { id: 3, title: 'small', due_at: now + DAY, effort_minutes: 60, done: 0 },
    { id: 4, title: 'done', due_at: now + DAY, done: 1 },
    { id: 5, title: 'far', due_at: now + 30 * DAY, done: 0 },
  ];
  const p = buildPlan({ courses, events, homework, now, tz });
  assert.deepEqual(p.current, [10]);
  assert.equal(p.next, 11);
  assert.equal(p.tomorrowFirst, 14);
  assert.deepEqual(p.todo.map((h) => [h.title, h.urgency]), [['late', 'overdue'], ['small', 'urgent'], ['big', 'due']]);
  assert.equal(p.later, 1);
  // 12:00–14:00 and 16:00–22:00 are free.
  assert.deepEqual(p.gaps.map((g) => [g.minutes, g.fits.map((f) => f.title)]), [[120, ['late', 'small']], [360, ['big']]]);
  assert.deepEqual(p.week.clashes, [[14, 15]]);
  assert.equal(p.week.workHours, 7);
});

test('tokens: hashed, scoped, revocable, and the plan is readable with one', async () => {
  const { env, db, user } = setup();
  const rnd = () => 'x'.repeat(30) + Math.random().toString(36).slice(2);
  const read = await createToken(env, user, { name: 'Claude Code', scope: 'read' }, rnd);
  assert.match(read.token, /^a80_/);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM api_tokens WHERE token_hash = ?').get(read.token).n, 0);
  assert.equal((await userFromToken(env, `Bearer ${read.token}`)).tokenScope, 'read');
  assert.equal(await userFromToken(env, 'Bearer a80_wrongwrongwrongwrongwrong'), null);
  assert.ok(tokenAllows('read', 'GET', '/api/plan'));
  assert.ok(!tokenAllows('read', 'POST', '/api/homework'));
  assert.ok(tokenAllows('write', 'POST', '/api/homework'));
  assert.ok(!tokenAllows('write', 'POST', '/api/tokens'));
  assert.ok(!tokenAllows('write', 'DELETE', '/api/account'));

  const call = (path, init = {}) => worker.fetch(new Request(`https://app.test${path}`,
    { ...init, headers: { Authorization: `Bearer ${read.token}`, ...init.headers } }), env);
  const res = await call('/api/plan?tz=Europe/Helsinki');
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.plan.tz, 'Europe/Helsinki');
  assert.deepEqual(body.homework, []);
  assert.equal((await call('/api/homework', { method: 'POST', body: '{}' })).status, 403);
  assert.equal((await call('/api/tokens')).status, 403);
  await revokeToken(env, user, read.id);
  assert.equal((await call('/api/plan')).status, 401);
});
