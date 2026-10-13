import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { d1 } from './d1-shim.js';
import { _internal, chat } from '../src/assistant.js';

const { buildContext, runTool, resolveDay } = _internal;
const H = 3600000;
const NOW = Date.UTC(2026, 9, 13, 10);
let env, user, ctx;

async function loadCtx() {
  const db = env.DB.raw;
  const courses = db.prepare('SELECT id, name, tracked, tag FROM courses').all();
  return {
    env, user, tz: 'Europe/Paris', origin: 'https://app', actions: [],
    data: {
      courses, events: db.prepare('SELECT * FROM events ORDER BY start_at').all(),
      homework: db.prepare('SELECT * FROM homework').all(), breaks: [], byId: Object.fromEntries(courses.map((c) => [c.id, c])),
    },
  };
}

beforeEach(async () => {
  env = { DB: d1() };
  const db = env.DB.raw;
  db.exec(`INSERT INTO users (id, google_sub, email, threshold, unit, created_at) VALUES (1, 's', 'a@b.c', 0.8, 'sessions', 0)`);
  db.exec(`INSERT INTO courses (id, user_id, key, name, tracked, tag) VALUES (1, 1, 'algo', 'Algo', 1, 'normal'), (2, 1, 'db', 'Databases', 1, 'normal')`);
  // Algo: 10 sessions daily from yesterday; #5 is an exam. Databases: 5 sessions.
  for (let i = 0; i < 10; i++) db.exec(`INSERT INTO events (id, user_id, course_id, uid, summary, start_at, end_at, tag)
    VALUES (${i + 1}, 1, 1, 'a${i}', 'Algo', ${NOW - 24 * H + i * 24 * H}, ${NOW - 22 * H + i * 24 * H}, ${i === 4 ? "'exam'" : 'NULL'})`);
  for (let i = 0; i < 5; i++) db.exec(`INSERT INTO events (id, user_id, course_id, uid, summary, start_at, end_at)
    VALUES (${i + 11}, 1, 2, 'd${i}', 'Databases', ${NOW + 2 * H + i * 48 * H}, ${NOW + 4 * H + i * 48 * H})`);
  user = db.prepare('SELECT * FROM users WHERE id = 1').get();
  ctx = await loadCtx();
});

test('context lists stats, sessions with ids, and unmarked past sessions', () => {
  const c = buildContext(ctx.data, user, 'Europe/Paris', NOW);
  assert.match(c, /NOW: Tue 2026-10-13 12:00 \(Europe\/Paris\)/);
  assert.match(c, /DATES .*Mon 2026-10-12, Tue 2026-10-13 \(today\), Wed 2026-10-14 \(tomorrow\), Thu 2026-10-15, Fri 2026-10-16, Sat 2026-10-17/);
  assert.match(c, /course #1 Algo: projected 100%, absences 0 sessions of 2 sessions allowed, can still skip 2 sessions, safe/);
  assert.match(c, /#1 \| Mon 2026-10-12 12:00-14:00 \| Algo \(course #1\) \| - \| NOT MARKED/);
  assert.match(c, /#5 \| .* \| EXAM \| upcoming/);
});

test('what_if_skip uses app maths and warns on exams, without changing data', async () => {
  const r = await runTool('what_if_skip', { session_ids: [3, 5] }, ctx);
  assert.equal(r.courses[0].before.can_still_skip, '2 sessions');
  assert.equal(r.courses[0].after.can_still_skip, '0 sessions');
  assert.equal(r.warnings.length, 1);
  assert.match(r.warnings[0], /#5 .* exam/);
  assert.equal(env.DB.raw.prepare('SELECT COUNT(*) n FROM events WHERE status IS NOT NULL').get().n, 0);
});

test('set_attendance marks own sessions only, records undo info', async () => {
  env.DB.raw.exec(`INSERT INTO users (id, google_sub, created_at) VALUES (2, 'other', 0);
    INSERT INTO courses (id, user_id, key, name) VALUES (9, 2, 'x', 'X');
    INSERT INTO events (id, user_id, course_id, uid, summary, start_at, end_at) VALUES (99, 2, 9, 'x', 'X', 0, 1);`);
  const r = await runTool('set_attendance', { session_ids: [1, 99], status: 'absent' }, ctx);
  assert.deepEqual(r.missing, [99]);
  assert.equal(r.courses[0].now.can_still_skip, '1 sessions');
  assert.equal(env.DB.raw.prepare('SELECT status FROM events WHERE id = 1').get().status, 'absent');
  assert.equal(env.DB.raw.prepare('SELECT status FROM events WHERE id = 99').get().status, null);
  assert.deepEqual(ctx.actions[0].changes.map((c) => [c.id, c.from, c.to]), [[1, null, 'absent']]);
  const r2 = await runTool('set_attendance', { session_ids: [1], status: 'unmarked' }, ctx);
  assert.equal(r2.status, 'unmarked');
  assert.equal(env.DB.raw.prepare('SELECT status FROM events WHERE id = 1').get().status, null);
});

test('add_homework validates, defaults the reminder, and returns errors to the model', async () => {
  const ok = await runTool('add_homework', { title: 'TP4', course_id: 2, due: 'thursday', due_time: '14:00' }, ctx);
  assert.equal(ok.created, 'hw #1');
  const row = env.DB.raw.prepare('SELECT * FROM homework WHERE id = 1').get();
  assert.equal(row.remind_minutes, 1440);
  assert.equal(row.due_at, Date.UTC(2026, 9, 15, 12));
  const unknown = await runTool('add_homework', { title: 'X', due: 'someday' }, ctx);
  assert.match(unknown.error, /Could not understand/);
  const bad = await runTool('add_homework', { title: 'X', course_id: 99, due: '2026-10-15' }, ctx);
  assert.equal(bad.error, 'Unknown course.');
  const done = await runTool('complete_homework', { homework_id: 1 }, ctx);
  assert.equal(done.done, true);
  assert.deepEqual(ctx.actions.at(-1).from, { done: 0 });
});

test('chat loop: tool call, then final answer; usage limit enforced', async () => {
  const calls = [];
  env.AI_DAILY_LIMIT = '2';
  env.AI = {
    run: async (model, input) => {
      calls.push(input);
      if (calls.length % 2 === 1) {
        return { choices: [{ message: { content: null, tool_calls: [{ id: 'c1', type: 'function',
          function: { name: 'set_attendance', arguments: '{"session_ids":[1],"status":"present"}' } }] } }], usage: { neurons: 3 } };
      }
      return { choices: [{ message: { content: 'Marked **Algo** as attended.' } }], usage: { neurons: 2 } };
    },
  };
  const r = await chat(env, user, { messages: [{ role: 'user', content: 'I went to algo yesterday' }], tz: 'Europe/Paris' }, 'https://app');
  assert.equal(r.reply, 'Marked **Algo** as attended.');
  assert.equal(r.actions[0].type, 'attendance');
  assert.ok(calls[0].tools && calls[0].messages[0].content.includes('CONTEXT:'));
  assert.equal(calls[1].messages.at(-1).role, 'tool');
  assert.deepEqual(r.usage, { used: 1, limit: 2, owner: false, byok: null });
  await chat(env, user, { messages: [{ role: 'user', content: 'again' }] }, 'https://app');
  await assert.rejects(chat(env, user, { messages: [{ role: 'user', content: 'third' }] }, 'https://app'), /used today's 2/);
  assert.equal(env.DB.raw.prepare('SELECT neurons FROM ai_usage').get().neurons, 10);
});

test('resolveDay: weekdays, relative words, French, dates', () => {
  // NOW is Tue 2026-10-13 12:00 Paris.
  const r = (t) => resolveDay(t, NOW, 'Europe/Paris');
  assert.equal(r('friday'), '2026-10-16');
  assert.equal(r('Friday'), '2026-10-16');
  assert.equal(r('by friday'), '2026-10-16');
  assert.equal(r('vendredi'), '2026-10-16');
  assert.equal(r('tuesday'), '2026-10-13');
  assert.equal(r('next tuesday'), '2026-10-20');
  assert.equal(r('mardi prochain'), '2026-10-20');
  assert.equal(r('next monday'), '2026-10-19');
  assert.equal(r('tomorrow'), '2026-10-14');
  assert.equal(r('demain'), '2026-10-14');
  assert.equal(r('today'), '2026-10-13');
  assert.equal(r('2026-12-01'), '2026-12-01');
  assert.equal(r('someday'), null);
  assert.equal(r('Fri 2026-10-16'), '2026-10-16');
  assert.equal(r('Fri 2026-10-17'), null);  // mismatched weekday is rejected
  assert.equal(r('vendredi 2026-10-16'), '2026-10-16');
  // Late evening in Paris is already the next UTC day: dates follow the user's zone.
  assert.equal(resolveDay('tomorrow', Date.UTC(2026, 9, 13, 22, 30), 'Europe/Paris'), '2026-10-15');
});

test('break tools: check next week, save, then remove restores statuses', async () => {
  const { removeBreak } = await import('../src/breaks-store.js');
  // Algo runs daily from Mon 12 Oct; Databases every 2 days from 13 Oct. "next week" = 19–25 Oct.
  const check = await runTool('check_break', { week: 'next week' }, ctx);
  assert.equal(check.break, '2026-10-19 to 2026-10-25 (7 days)');
  assert.match(check.verdict, /not possible/);   // Algo would lose 3 of its 10 (only 2 allowed)
  const tue = await runTool('check_break', { from: 'thursday', to: 'thursday' }, ctx);
  assert.equal(tue.break, '2026-10-15 to 2026-10-15 (1 days)');
  assert.equal(tue.sessions_skipped, 2);
  const exam = await runTool('check_break', { from: '2026-10-16', to: '2026-10-16' }, ctx);
  assert.equal(exam.exam_or_mandatory_conflicts.length, 1);   // session #5 is an exam
  const bad = await runTool('check_break', { week: 'whenever' }, ctx);
  assert.match(bad.error, /Could not understand the week/);

  env.DB.raw.exec("UPDATE events SET status = 'present' WHERE id = 3");
  ctx = await loadCtx();
  const saved = await runTool('save_break', { from: '2026-10-14', to: '2026-10-14', label: 'Trip' }, ctx);
  assert.equal(saved.sessions_marked_absent, 1);
  assert.equal(ctx.actions[0].type, 'break_saved');
  assert.equal(env.DB.raw.prepare('SELECT status FROM events WHERE id = 3').get().status, 'absent');
  await removeBreak(env, user, ctx.actions[0].break.id);
  assert.equal(env.DB.raw.prepare('SELECT status FROM events WHERE id = 3').get().status, 'present');
  assert.equal(env.DB.raw.prepare('SELECT COUNT(*) n FROM breaks').get().n, 0);
});

test('find_break and skippable_weeks return readable results', async () => {
  const r = await runTool('find_break', { days: 2 }, ctx);
  assert.ok(r.best_windows?.length || r.result);
  const w = await runTool('skippable_weeks', { weeks: 3 }, ctx);
  assert.equal(w.weeks.length, 2);   // calendar ends 2026-10-21: weeks past it aren't listed
  assert.match(w.weeks[0], /^2026-10-12 to 2026-10-18: /);
});

test('limits: owner gets the higher allowance and bypasses the shared budget; others share it', async () => {
  const { usage, isOwner } = await import('../src/assistant.js');
  const db = env.DB.raw;
  db.exec(`UPDATE users SET email = 'owner@example.com' WHERE id = 1;
    INSERT INTO users (id, google_sub, email, created_at) VALUES (2, 'g2', 'student@example.com', 0), (3, 'passkey:x', NULL, 0);`);
  const owner = db.prepare('SELECT * FROM users WHERE id = 1').get();
  const student = db.prepare('SELECT * FROM users WHERE id = 2').get();
  const passkeyUser = db.prepare('SELECT * FROM users WHERE id = 3').get();
  env.AI_OWNER_EMAILS = 'Owner@Example.com, someone@else.com';
  env.AI_SHARED_DAILY_NEURONS = '100';
  env.AI = { run: async () => ({ choices: [{ message: { content: 'hi' } }], usage: { neurons: 60 } }) };
  const ask = (u) => chat(env, u, { messages: [{ role: 'user', content: 'hi' }] }, 'https://app');

  assert.ok(isOwner(env, owner));
  assert.ok(!isOwner(env, student));
  assert.ok(!isOwner(env, passkeyUser));       // no email: not an owner unless listed by id
  assert.deepEqual(await usage(env, owner), { used: 0, limit: 100, owner: true, byok: null });
  assert.deepEqual(await usage(env, student), { used: 0, limit: 10, owner: false, byok: null });

  await ask(student);                           // 60 shared neurons
  await ask(passkeyUser);                       // 120 >= 100: shared budget now used up
  await assert.rejects(ask(student), /shared limit for all users/);
  await ask(owner);                             // owners are exempt
  await ask(owner);
  assert.equal((await usage(env, owner)).used, 2);

  env.AI_OWNER_USER_IDS = '3';                  // passkey-only owner by id
  assert.ok(isOwner(env, passkeyUser));
  await ask(passkeyUser);
});
