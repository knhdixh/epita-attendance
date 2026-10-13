import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { d1 } from './d1-shim.js';
import { encrypt, syncHomework, deleteGcalEvent, resync, disconnect } from '../src/gcal.js';

// Fake Google: one calendar store, records calls.
function fakeGoogle() {
  const g = { calendars: new Map(), calls: [], revoked: false, nextId: 1 };
  globalThis.fetch = async (url, init = {}) => {
    const u = new URL(url); const method = init.method || 'GET';
    g.calls.push(`${method} ${u.pathname}`);
    const reply = (status, body) => new Response(body == null ? null : JSON.stringify(body), { status });
    if (u.pathname === '/token') {
      return g.revoked ? reply(400, { error: 'invalid_grant' }) : reply(200, { access_token: 'at-' + g.nextId++, expires_in: 3600 });
    }
    if (u.pathname === '/revoke') { g.revoked = true; return reply(200, {}); }
    let m;
    if (u.pathname === '/calendar/v3/calendars' && method === 'POST') {
      const id = `cal${g.nextId++}`; g.calendars.set(id, new Map()); return reply(200, { id });
    }
    if ((m = u.pathname.match(/^\/calendar\/v3\/calendars\/([^/]+)\/events(?:\/([^/]+))?$/))) {
      const cal = g.calendars.get(decodeURIComponent(m[1]));
      if (!cal) return reply(404, { error: { message: 'Not Found' } });
      const evId = m[2] && decodeURIComponent(m[2]);
      const body = init.body ? JSON.parse(init.body) : null;
      if (method === 'POST') { const id = `ev${g.nextId++}`; cal.set(id, body); return reply(200, { id, ...body }); }
      if (!cal.has(evId)) return reply(404, { error: { message: 'Not Found' } });
      if (method === 'PUT') { cal.set(evId, body); return reply(200, { id: evId, ...body }); }
      if (method === 'DELETE') { cal.delete(evId); return reply(204); }
    }
    return reply(500, { error: { message: 'unexpected ' + url } });
  };
  return g;
}

let env, g, hw;
const userId = 1;
beforeEach(async () => {
  g = fakeGoogle();
  env = { DB: d1(), GOOGLE_CLIENT_ID: 'id', GOOGLE_CLIENT_SECRET: 'secret', TOKEN_KEY: btoa(String.fromCharCode(...new Uint8Array(32).fill(7))) };
  env.DB.raw.exec(`INSERT INTO users (id, google_sub, email, created_at) VALUES (1, 's', 'a@b.c', 0)`);
  env.DB.raw.prepare(`INSERT INTO gcal_links (user_id, email, refresh_token, access_expires, connected_at) VALUES (1, 'a@b.c', ?, 0, 0)`)
    .run(await encrypt(env, 'refresh-1'));
  env.DB.raw.exec(`INSERT INTO homework (id, user_id, title, due_date, due_time, tz, due_at, remind_minutes, created_at, updated_at)
    VALUES (1, 1, 'TP 3', '2026-11-09', '13:00', 'Europe/Paris', ${Date.UTC(2026, 10, 9, 12)}, 1440, 0, 0)`);
  hw = env.DB.raw.prepare('SELECT * FROM homework WHERE id = 1').get();
});

const row = () => env.DB.raw.prepare('SELECT * FROM homework WHERE id = 1').get();
const link = () => env.DB.raw.prepare('SELECT * FROM gcal_links WHERE user_id = 1').get();

test('first sync creates the calendar and the event; update reuses it', async () => {
  const r = await syncHomework(env, userId, hw, 'Databases', 'https://app');
  assert.equal(r.sync_error, null);
  assert.ok(row().gcal_event_id);
  assert.ok(link().calendar_id);
  const [cal] = g.calendars.values();
  assert.equal(cal.get(row().gcal_event_id).summary, '📚 TP 3 · Databases');
  const before = g.calls.length;
  await syncHomework(env, userId, { ...row(), done: 1 }, 'Databases', 'https://app');
  assert.deepEqual(g.calls.slice(before), [`PUT /calendar/v3/calendars/${link().calendar_id}/events/${row().gcal_event_id}`]);
  assert.equal(cal.get(row().gcal_event_id).summary, '✅ TP 3 · Databases');
  assert.equal(g.calls.filter((c) => c === 'POST /token').length, 1, 'access token is cached');
});

test('event deleted in Google is recreated; deleted calendar is recreated', async () => {
  await syncHomework(env, userId, hw, null);
  const cal1 = link().calendar_id;
  g.calendars.get(cal1).clear();
  await syncHomework(env, userId, row(), null);
  assert.equal(g.calendars.get(cal1).size, 1);
  g.calendars.delete(cal1);
  const r = await syncHomework(env, userId, row(), null);
  assert.equal(r.sync_error, null);
  assert.notEqual(link().calendar_id, cal1);
  assert.equal(g.calendars.get(link().calendar_id).size, 1);
});

test('delete removes the Google event', async () => {
  await syncHomework(env, userId, hw, null);
  await deleteGcalEvent(env, userId, row().gcal_event_id);
  assert.equal(g.calendars.get(link().calendar_id).size, 0);
});

test('revoked token: error recorded on row and link, resync does not loop', async () => {
  g.revoked = true;
  const r = await syncHomework(env, userId, hw, null);
  assert.match(r.sync_error, /revoked/);
  assert.match(link().last_error, /revoked/);
  const rs = await resync(env, userId, {}, null);
  assert.deepEqual([rs.synced, rs.failed, rs.remaining], [0, 1, 0]);
});

test('resync pushes pending rows; disconnect revokes and clears ids', async () => {
  for (let i = 2; i <= 4; i++) env.DB.raw.exec(`INSERT INTO homework (id, user_id, title, due_date, tz, due_at, created_at, updated_at)
    VALUES (${i}, 1, 'hw${i}', '2026-11-1${i}', 'Europe/Paris', ${Date.UTC(2026, 10, 10 + i)}, 0, 0)`);
  const rs = await resync(env, userId, {}, null);
  assert.deepEqual([rs.synced, rs.failed, rs.remaining], [4, 0, 0]);
  await disconnect(env, userId);
  assert.ok(g.revoked);
  assert.equal(link(), undefined);
  assert.equal(env.DB.raw.prepare('SELECT COUNT(*) n FROM homework WHERE gcal_event_id IS NOT NULL').get().n, 0);
});
