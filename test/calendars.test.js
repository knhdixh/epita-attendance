import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { d1 } from './d1-shim.js';
import { parseCalendarLink } from '../src/calendar-link.js';
import {
  importIcs, addLink, addFile, replaceFile, updateCalendar, removeCalendar, listCalendars, syncDueCalendars, MAX_CALENDARS,
} from '../src/calendars.js';
import { removeBreak } from '../src/breaks-store.js';

const LINK = 'https://zeus.ionis-it.com/api/group/1234/ics/AbC123def456';
const H = 3600 * 1000;
const DAY = 24 * H;

// One VEVENT per entry: a uid, or [uid, summary, startMs].
const ics = (list) => ['BEGIN:VCALENDAR', ...list.flatMap((x) => {
  const [uid, summary = 'Maths', start = Date.UTC(2026, 8, 1, 8)] = Array.isArray(x) ? x : [x];
  const t = (ms) => new Date(ms).toISOString().replace(/[-:]/g, '').slice(0, 15) + 'Z';
  return ['BEGIN:VEVENT', `UID:${uid}`, `SUMMARY:${summary}`, `DTSTART:${t(start)}`, `DTEND:${t(start + 2 * H)}`, 'END:VEVENT'];
}), 'END:VCALENDAR'].join('\r\n');

function setup() {
  const env = { DB: d1() };
  env.DB.raw.exec("INSERT INTO users (id, google_sub, created_at) VALUES (1, 'a', 0), (2, 'b', 0)");
  return { env, db: env.DB.raw, user: { id: 1 } };
}
const uids = (db, where = 'user_id = 1') => db.prepare(`SELECT uid FROM events WHERE ${where} ORDER BY uid`).all().map((r) => r.uid);

test('links: Zeus links are normalised, other https/webcal calendars are kept, unsafe ones refused', () => {
  assert.deepEqual(parseCalendarLink(` ${LINK}?startDate=2026-09-01T00:00:00&x=1 `), { url: LINK, from: '2026-09-01', zeus: true, name: 'Zeus group 1234' });
  assert.deepEqual(parseCalendarLink(LINK.replace('https:', 'webcal:')), { url: LINK, from: null, zeus: true, name: 'Zeus group 1234' });
  assert.deepEqual(parseCalendarLink('webcal://p12-caldav.icloud.com/published/2/abc?x=1#frag'),
    { url: 'https://p12-caldav.icloud.com/published/2/abc?x=1', from: null, zeus: false, sisu: false, name: 'p12-caldav.icloud.com' });
  for (const bad of ['', 'not a url', 'http://zeus.ionis-it.com/api/group/1/ics/AbC123def456', 'ftp://example.com/a.ics',
    'https://zeus.ionis-it.com/api/group/1/ics', 'https://zeus.ionis-it.com/api/group/1/ics/../../x/AbC123def456',
    'https://127.0.0.1/a.ics', 'https://[::1]/a.ics', 'https://localhost/a.ics', 'https://intranet/a.ics',
    'https://user:pw@example.com/a.ics', `https://example.com/${'a'.repeat(2100)}`]) {
    assert.throws(() => parseCalendarLink(bad), { status: 400 }, bad);
  }
});

// Calendars each session is in, by uid: { uid: [calendar names] }.
const memberships = (db) => Object.fromEntries(db.prepare(`SELECT e.uid, group_concat(s.name, '+') AS cals FROM events e
  LEFT JOIN event_sources es ON es.event_id = e.id LEFT JOIN calendar_sources s ON s.id = es.source_id
  WHERE e.user_id = 1 GROUP BY e.id ORDER BY e.uid`).all().map((r) => [r.uid, (r.cals || '').split('+').filter(Boolean).sort()]));

test('a session shared by several group calendars is stored once and is in each of them', async () => {
  const { env, db, user } = setup();
  // Like three Zeus group links: one shared lecture, plus each group's own classes.
  const se1 = await addFile(env, user, { ics: ics(['lecture', 'g1-td', 'g1-tp']), name: 'SE1' });
  const se2 = await addFile(env, user, { ics: ics(['lecture']), name: 'SE2' });
  const se3 = await addFile(env, user, { ics: ics(['lecture', 'g3-td']), name: 'SE3' });
  assert.equal(se2.added, 0); // the lecture already existed: SE2 joins it instead of taking it from SE1
  assert.deepEqual(memberships(db), { 'g1-td': ['SE1'], 'g1-tp': ['SE1'], 'g3-td': ['SE3'], lecture: ['SE1', 'SE2', 'SE3'] });
  assert.deepEqual((await listCalendars(env, 1)).map((c) => [c.name, c.sessions]), [['SE1', 3], ['SE2', 1], ['SE3', 2]]);

  // SE1 drops the lecture and g1-tp: g1-tp goes; the lecture stays, still in SE2 and SE3.
  const r = await replaceFile(env, user, se1.calendar, { ics: ics(['g1-td']), fileName: 'SE1 v2.ics' });
  assert.equal(r.removed, 1);
  assert.deepEqual(memberships(db), { 'g1-td': ['SE1'], 'g3-td': ['SE3'], lecture: ['SE2', 'SE3'] });

  // A marked past session that SE3 drops stays in SE3, so it still counts under that group.
  db.exec("UPDATE events SET status = 'present' WHERE uid = 'g3-td'");
  await replaceFile(env, user, se3.calendar, { ics: ics(['lecture']) });
  assert.deepEqual(memberships(db)['g3-td'], ['SE3']);

  // Removing SE3 deletes only what no other calendar has; the lecture stays in SE2.
  db.exec("INSERT INTO courses (id, user_id, key, name) VALUES (99, 2, 'x', 'X')");
  await updateCalendar(env, user, se2.calendar, { name: '  Group 2  ' });
  assert.equal((await listCalendars(env, 1))[1].name, 'Group 2');
  assert.deepEqual(await removeCalendar(env, user, se3.calendar), { removed: 1 });
  assert.deepEqual(memberships(db), { 'g1-td': ['SE1'], lecture: ['Group 2'] });
  assert.equal(db.prepare('SELECT COUNT(*) n FROM courses WHERE user_id = 2').get().n, 1); // another user is untouched
  await assert.rejects(removeCalendar(env, { id: 2 }, se1.calendar), { status: 404 }); // not theirs
});

test('new courses made only of all-day events (holidays, term dates) start untracked', async () => {
  const { env, db, user } = setup();
  const day = ['BEGIN:VEVENT', 'UID:xmas', 'SUMMARY:Christmas Day', 'DTSTART;VALUE=DATE:20261225', 'DTEND;VALUE=DATE:20261226', 'END:VEVENT'];
  await addFile(env, user, { ics: ics(['m1', ['g1', 'GR2 – English']]).replace('END:VCALENDAR', [...day, 'END:VCALENDAR'].join('\r\n')) });
  assert.deepEqual(db.prepare('SELECT name, tracked FROM courses ORDER BY name').all().map((c) => [c.name, c.tracked]),
    [['Christmas Day', 0], ['GR2 – English', 0], ['Maths', 1]]);
});

test('re-import keeps the attendance record but drops cancelled future classes', async () => {
  const { env, db, user } = setup();
  const now = Date.UTC(2026, 9, 19, 12);
  db.exec(`INSERT INTO calendar_sources (id, user_id, kind, name, created_at) VALUES (1, 1, 'file', 'S3', 0)`);
  const past = now - DAY, future = now + DAY;
  await importIcs(env, 1, 1, ics([['p-marked', 'Maths', past], ['p-plain', 'Maths', past], ['f-skip', 'Maths', future],
    ['f-note', 'Maths', future], ['f-plain', 'Maths', future]]), now);
  db.exec(`UPDATE events SET status = 'present' WHERE uid = 'p-marked';
    UPDATE events SET status = 'absent' WHERE uid = 'f-skip';
    UPDATE events SET note = 'bring laptop' WHERE uid = 'f-note';`);
  const r = await importIcs(env, 1, 1, ics(['other']), now);
  // Past marked session stays; future planned skip goes (it no longer exists); a note keeps a session.
  assert.deepEqual(uids(db), ['f-note', 'other', 'p-marked']);
  assert.equal(r.removed, 3);
});

test('adding a link imports it first; a failing or duplicate link leaves nothing behind', async (t) => {
  const { env, db, user } = setup();
  t.mock.method(globalThis, 'fetch', async (url) => (String(url).includes('bad')
    ? new Response('', { status: 404 }) : new Response(ics(['z1', 'z2']))));
  const ok = await addLink(env, user, { url: `${LINK}?startDate=2026-09-01` });
  assert.equal(ok.added, 2);
  const [cal] = await listCalendars(env, 1);
  assert.deepEqual([cal.kind, cal.name, cal.site, cal.from, cal.sessions], ['link', 'Zeus group 1234', 'zeus.ionis-it.com · group 1234', '2026-09-01', 2]);
  assert.ok(!('url' in cal), 'the private link is never sent back');

  await assert.rejects(addLink(env, user, { url: LINK }), { status: 409 });
  await assert.rejects(addLink(env, user, { url: 'https://zeus.ionis-it.com/api/group/9/ics/badtoken99' }), /no longer accepts/);
  await assert.rejects(addLink(env, user, { url: 'https://example.com/cal.ics', name: 'x'.repeat(61) }), { status: 400 });
  assert.equal(db.prepare('SELECT COUNT(*) n FROM calendar_sources').get().n, 1);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM calendar_syncs').get().n, 1, 'the failed attempt\'s log went with it');

  for (let i = 1; i < MAX_CALENDARS; i++) db.exec(`INSERT INTO calendar_sources (user_id, kind, name, created_at) VALUES (1, 'file', 'f${i}', 0)`);
  await assert.rejects(addFile(env, user, { ics: ics(['q']) }), /up to 10 calendars/);
});

test('the cron refreshes one overdue link per run, logs it, retries failures sooner, and keeps marks', async (t) => {
  const { env, db } = setup();
  const now = Date.now();
  db.exec(`INSERT INTO calendar_sources (id, user_id, kind, name, url, from_date, synced_at, created_at) VALUES
    (1, 1, 'link', 'Zeus', '${LINK}', '2026-09-01', ${now - 80 * H}, 0),
    (2, 2, 'link', 'Old', 'https://zeus.ionis-it.com/api/group/9/ics/revokedtoken', NULL, ${now - 90 * H}, 0),
    (3, 2, 'link', 'Fresh', 'https://example.com/fresh.ics', NULL, ${now - 24 * H}, 0),
    (4, 2, 'file', 'Upload', NULL, NULL, NULL, 0)`);
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url) => {
    calls.push(String(url));
    return String(url).includes('revoked') ? new Response('', { status: 401 }) : new Response(ics(['e1', 'e2']));
  });

  // Most overdue first, one per run: link 2 (fails), then link 1. Link 3 isn't due and files never are.
  assert.deepEqual(await syncDueCalendars(env, now), { due: 1, ok: 0, failed: 1 });
  assert.deepEqual(await syncDueCalendars(env, now), { due: 1, ok: 1, failed: 0 });
  assert.deepEqual(await syncDueCalendars(env, now), { due: 0, ok: 0, failed: 0 });
  assert.deepEqual(calls, ['https://zeus.ionis-it.com/api/group/9/ics/revokedtoken', `${LINK}?startDate=2026-09-01`]);
  assert.deepEqual(uids(db, 'id IN (SELECT event_id FROM event_sources WHERE source_id = 1)'), ['e1', 'e2']);
  const [s1, s2] = db.prepare('SELECT ok_at, synced_at, error FROM calendar_sources WHERE id IN (1, 2) ORDER BY id').all();
  assert.ok(s1.ok_at && !s1.error);
  assert.match(s2.error, /no longer accepts/);
  assert.ok(s2.synced_at && !s2.ok_at, 'a failing link is pushed to the back of the queue');

  const log = db.prepare('SELECT user_id, source_id, source, ok, http_status, sessions, added, error FROM calendar_syncs ORDER BY id').all();
  assert.deepEqual(log.map((r) => ({ ...r })), [
    { user_id: 2, source_id: 2, source: 'cron', ok: 0, http_status: 401, sessions: null, added: null, error: s2.error },
    { user_id: 1, source_id: 1, source: 'cron', ok: 1, http_status: 200, sessions: 2, added: 2, error: null },
  ]);
  assert.ok(db.prepare('SELECT finished_at FROM cron_runs').all().every((r) => r.finished_at));

  // The failed link comes back after 6 hours; the good one waits for its 3-day cycle.
  calls.length = 0;
  assert.equal((await syncDueCalendars(env, now + 5 * H)).due, 0);
  assert.equal((await syncDueCalendars(env, now + 7 * H)).due, 1);
  assert.deepEqual(calls, ['https://zeus.ionis-it.com/api/group/9/ics/revokedtoken']);

  // A later refresh keeps the (past) mark on e1 after Zeus drops it, and removes the unmarked e2.
  db.exec("UPDATE events SET status = 'present' WHERE uid = 'e1'");
  t.mock.method(globalThis, 'fetch', async () => new Response(ics(['e3'])));
  for (let i = 0; i < 3; i++) await syncDueCalendars(env, now + 72 * H);
  assert.deepEqual(uids(db, 'id IN (SELECT event_id FROM event_sources WHERE source_id = 1)'), ['e1', 'e3']);
  assert.equal(db.prepare('SELECT removed FROM calendar_syncs WHERE source_id = 1 ORDER BY id DESC').get().removed, 1);
});

test('removing a break restores only the sessions still there and still absent', async () => {
  const { env, db, user } = setup();
  db.exec(`INSERT INTO courses (id, user_id, key, name) VALUES (1, 1, 'm', 'Maths');
    INSERT INTO events (id, user_id, course_id, uid, summary, start_at, end_at, status) VALUES
      (1, 1, 1, 'x', 'Maths', 0, 1, 'absent'), (2, 1, 1, 'y', 'Maths', 0, 1, 'present');
    INSERT INTO breaks (id, user_id, start_date, end_date, changes, created_at) VALUES
      (1, 1, '2026-10-01', '2026-10-02', '[{"id":1,"from":null},{"id":2,"from":null},{"id":3,"from":"excused"}]', 0);`);
  // #2 was re-marked since, #3 was removed by a re-import: only #1 is restored.
  assert.deepEqual(await removeBreak(env, user, 1), { restored: 1 });
  assert.deepEqual(db.prepare('SELECT id, status FROM events ORDER BY id').all().map((r) => [r.id, r.status]), [[1, null], [2, 'present']]);
});

test('migrations 0009 and 0010 turn each user\'s link or upload into a calendar with its sessions and marks', () => {
  const db = new DatabaseSync(':memory:');
  db.exec('PRAGMA foreign_keys = ON;');
  const files = fs.readdirSync('migrations').sort();
  for (const f of files.filter((f) => f < '0009')) db.exec(fs.readFileSync(`migrations/${f}`, 'utf8'));
  db.exec(`INSERT INTO users (id, google_sub, created_at, ics_url, ics_from, ics_synced_at, ics_ok_at, ics_error) VALUES
      (1, 'link', 10, '${LINK}', '2026-09-01', 500, 400, NULL), (2, 'file', 20, NULL, NULL, NULL, NULL, NULL),
      (3, 'empty', 30, NULL, NULL, NULL, NULL, NULL);
    INSERT INTO courses (id, user_id, key, name) VALUES (1, 1, 'm', 'Maths'), (2, 2, 'm', 'Maths');
    INSERT INTO events (user_id, course_id, uid, summary, start_at, end_at, seen_at, status) VALUES
      (1, 1, 'a', 'Maths', 1, 2, 300, 'present'), (2, 2, 'a', 'Maths', 1, 2, 700, NULL);
    INSERT INTO calendar_syncs (user_id, at, source, ok, duration_ms) VALUES (1, 400, 'cron', 1, 5), (2, 100, 'manual', 1, 5);`);
  for (const f of files.filter((f) => f >= '0009')) db.exec(fs.readFileSync(`migrations/${f}`, 'utf8'));
  // Links are queued for a re-import (synced_at NULL), which restores memberships of shared sessions.
  assert.deepEqual(db.prepare('SELECT user_id, kind, url, from_date, synced_at, ok_at FROM calendar_sources ORDER BY user_id').all().map((r) => ({ ...r })), [
    { user_id: 1, kind: 'link', url: LINK, from_date: '2026-09-01', synced_at: null, ok_at: 400 },
    { user_id: 2, kind: 'file', url: null, from_date: null, synced_at: null, ok_at: 700 },
  ]);
  assert.deepEqual(db.prepare(`SELECT e.user_id, es.source_id, e.status FROM events e JOIN event_sources es ON es.event_id = e.id
    ORDER BY e.user_id`).all().map((r) => ({ ...r })), [{ user_id: 1, source_id: 1, status: 'present' }, { user_id: 2, source_id: 2, status: null }]);
  assert.ok(!db.prepare('PRAGMA table_info(events)').all().some((c) => ['source_id', 'seen_at'].includes(c.name)));
  assert.deepEqual(db.prepare('SELECT user_id, source_id FROM calendar_syncs').all().map((r) => ({ ...r })), [{ user_id: 1, source_id: 1 }]);
  assert.ok(!db.prepare('PRAGMA table_info(users)').all().some((c) => c.name.startsWith('ics_')));
  assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);
});
