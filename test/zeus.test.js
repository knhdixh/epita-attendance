import { test } from 'node:test';
import assert from 'node:assert/strict';
import { d1 } from './d1-shim.js';
import { parseZeusLink } from '../src/zeus.js';
import { syncDueCalendars } from '../src/worker.js';

const LINK = 'https://zeus.ionis-it.com/api/group/1234/ics/AbC123def456';

test('Zeus links are normalised, and anything else is rejected', () => {
  assert.deepEqual(parseZeusLink(` ${LINK}?startDate=2026-09-01T00:00:00&x=1 `), { url: LINK, from: '2026-09-01' });
  assert.deepEqual(parseZeusLink(LINK.replace('https:', 'webcal:')), { url: LINK, from: null });
  for (const bad of ['', 'not a url', 'http://zeus.ionis-it.com/api/group/1/ics/AbC123def456',
    'https://evil.example/api/group/1/ics/AbC123def456', 'https://zeus.ionis-it.com.evil.example/api/group/1/ics/AbC123def456',
    'https://zeus.ionis-it.com/api/group/1/ics', 'https://zeus.ionis-it.com/api/group/1/ics/../../x/AbC123def456']) {
    assert.throws(() => parseZeusLink(bad), { status: 400 }, bad);
  }
});

const ics = (uids) => ['BEGIN:VCALENDAR', ...uids.flatMap((u) => ['BEGIN:VEVENT', `UID:${u}`, 'SUMMARY:Maths',
  'DTSTART:20260901T080000Z', 'DTEND:20260901T100000Z', 'END:VEVENT']), 'END:VCALENDAR'].join('\r\n');

test('the cron refreshes one overdue link per run, logs it, retries failures sooner, and keeps marks', async (t) => {
  const env = { DB: d1() };
  const db = env.DB.raw;
  const H = 3600 * 1000;
  const now = Date.now();
  db.exec(`INSERT INTO users (id, google_sub, created_at, ics_url, ics_from, ics_synced_at) VALUES
    (1, 'a', 0, '${LINK}', '2026-09-01', ${now - 80 * H}),
    (2, 'b', 0, 'https://zeus.ionis-it.com/api/group/9/ics/revokedtoken', NULL, ${now - 90 * H}),
    (3, 'c', 0, '${LINK}', NULL, ${now - 24 * H})`);
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url) => {
    calls.push(String(url));
    return String(url).includes('revoked') ? new Response('', { status: 401 }) : new Response(ics(['e1', 'e2']));
  });

  // Most overdue first, one per run: user 2 (fails), then user 1. User 3 isn't due (checked a day ago).
  assert.deepEqual(await syncDueCalendars(env, now), { due: 1, ok: 0, failed: 1 });
  assert.deepEqual(await syncDueCalendars(env, now), { due: 1, ok: 1, failed: 0 });
  assert.deepEqual(await syncDueCalendars(env, now), { due: 0, ok: 0, failed: 0 });
  assert.deepEqual(calls, ['https://zeus.ionis-it.com/api/group/9/ics/revokedtoken', `${LINK}?startDate=2026-09-01`]);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM events WHERE user_id = 1').get().n, 2);
  const [u1, u2] = db.prepare('SELECT ics_ok_at, ics_synced_at, ics_error FROM users WHERE id IN (1, 2) ORDER BY id').all();
  assert.ok(u1.ics_ok_at && !u1.ics_error);
  assert.match(u2.ics_error, /no longer accepts/);
  assert.ok(u2.ics_synced_at && !u2.ics_ok_at, 'a failing link is pushed to the back of the queue');

  // Every fetch is logged with Zeus's status; only runs that had work are recorded, each as finished.
  const log = db.prepare('SELECT user_id, source, ok, http_status, sessions, added, error FROM calendar_syncs ORDER BY id').all();
  assert.deepEqual(log.map((r) => ({ ...r })), [
    { user_id: 2, source: 'cron', ok: 0, http_status: 401, sessions: null, added: null, error: u2.ics_error },
    { user_id: 1, source: 'cron', ok: 1, http_status: 200, sessions: 2, added: 2, error: null },
  ]);
  const runs = db.prepare('SELECT finished_at, due, ok, failed FROM cron_runs ORDER BY id').all();
  assert.equal(runs.length, 2);
  assert.ok(runs.every((r) => r.finished_at));

  // The failed link comes back after 6 hours; the good one waits for its 3-day cycle.
  calls.length = 0;
  assert.equal((await syncDueCalendars(env, now + 5 * H)).due, 0);
  assert.equal((await syncDueCalendars(env, now + 7 * H)).due, 1);
  assert.deepEqual(calls, ['https://zeus.ionis-it.com/api/group/9/ics/revokedtoken']);

  // A later refresh keeps the mark on e1 even after Zeus drops it, and removes the unmarked e2.
  db.exec("UPDATE events SET status = 'present' WHERE uid = 'e1'");
  t.mock.method(globalThis, 'fetch', async () => new Response(ics(['e3'])));
  // Imports are told apart by their millisecond stamp, so don't let both land in the same one.
  await new Promise((r) => setTimeout(r, 5));
  // Both links are due again by now, one per run.
  assert.equal((await syncDueCalendars(env, now + 72 * H)).ok, 1);
  assert.equal((await syncDueCalendars(env, now + 72 * H)).ok, 1);
  assert.deepEqual(db.prepare('SELECT uid FROM events WHERE user_id = 1 ORDER BY uid').all().map((r) => r.uid), ['e1', 'e3']);
  assert.equal(db.prepare('SELECT removed FROM calendar_syncs WHERE user_id = 1 ORDER BY id DESC').get().removed, 1);
});
