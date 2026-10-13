import { test } from 'node:test';
import assert from 'node:assert/strict';
import { d1 } from './d1-shim.js';
import { deleteAccount } from '../src/account.js';

test('deleting an account removes every row belonging to the user, and only theirs', async () => {
  const env = { DB: d1() };
  const db = env.DB.raw;
  for (const id of [1, 2]) {
    db.exec(`INSERT INTO users (id, google_sub, email, created_at) VALUES (${id}, 'g${id}', 'u${id}@x', 0);
      INSERT INTO sessions VALUES ('s${id}', ${id}, 9e15);
      INSERT INTO courses (id, user_id, key, name) VALUES (${id}, ${id}, 'c', 'C');
      INSERT INTO events (user_id, course_id, uid, summary, start_at, end_at) VALUES (${id}, ${id}, 'e${id}', 'E', 0, 1);
      INSERT INTO homework (user_id, title, due_date, tz, due_at, created_at, updated_at) VALUES (${id}, 'H', '2026-10-20', 'UTC', 0, 0, 0);
      INSERT INTO breaks (user_id, start_date, end_date, created_at) VALUES (${id}, '2026-10-20', '2026-10-21', 0);
      INSERT INTO ai_usage (user_id, day) VALUES (${id}, '2026-10-13');
      INSERT INTO ai_keys (user_id, provider, api_key, key_hint, model, created_at) VALUES (${id}, 'groq', 'enc', 'abcd', 'm', 0);
      INSERT INTO passkeys (id, user_id, public_key, created_at) VALUES ('pk${id}', ${id}, 'k', 0);
      INSERT INTO auth_challenges (id, challenge, kind, user_id, expires_at) VALUES ('ch${id}', 'x', 'register', ${id}, 9e15);`);
  }
  const tables0 = db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all().map((r) => r.name);
  const before = Object.fromEntries(tables0.map((t) => {
    const cols = db.prepare(`PRAGMA table_info(${t})`).all().map((c) => c.name);
    const col = t === 'users' ? 'id' : cols.includes('user_id') ? 'user_id' : null;
    return [t, col ? db.prepare(`SELECT COUNT(*) n FROM ${t} WHERE ${col} = 2`).get().n : 0];
  }));
  await deleteAccount(env, { id: 1 });
  const tables = db.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'd1_%'").all().map((r) => r.name);
  for (const t of tables) {
    const cols = db.prepare(`PRAGMA table_info(${t})`).all().map((c) => c.name);
    const col = t === 'users' ? 'id' : cols.includes('user_id') ? 'user_id' : null;
    if (!col) continue;
    assert.equal(db.prepare(`SELECT COUNT(*) n FROM ${t} WHERE ${col} = 1`).get().n, 0, `${t} still has rows for the deleted user`);
    assert.equal(db.prepare(`SELECT COUNT(*) n FROM ${t} WHERE ${col} = 2`).get().n, before[t], `${t} lost the other user's rows`);
  }
});
