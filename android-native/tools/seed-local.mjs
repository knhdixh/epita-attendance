import { execFileSync } from 'node:child_process';
import { writeFileSync, existsSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';

const dir = process.argv[2];
if (!dir || !realpathSync(dir).startsWith(realpathSync(tmpdir()) + '/') || !existsSync(join(dir, '.a80-e2e-isolated'))) throw new Error('Expected isolated temporary Wrangler directory');
const base = 'http://localhost:8787';
const sqlFile = join(dir, 'seed.sql');
function localSql(sql) {
  writeFileSync(sqlFile, sql);
  execFileSync(join(dir, 'node_modules/.bin/wrangler'), ['d1', 'execute', 'DB', '--local', '--file', sqlFile], { cwd: dir, stdio: 'inherit' });
}
// The isolated database belongs to this run; reset only this deterministic account on retries.
localSql("DELETE FROM users WHERE google_sub = 'dev:e2e@example.test';");
const login = await fetch(`${base}/auth/dev?email=e2e%40example.test`, { redirect: 'manual' });
assert.equal(login.status, 302, 'local developer login');
const cookie = login.headers.get('set-cookie').split(';')[0];
async function request(path, body) {
  const response = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { cookie, ...(body ? { 'content-type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  if (!response.ok) throw new Error(`${path}: ${response.status} ${await response.text()}`);
  return response.json();
}
const now = Date.now();
const stamp = ms => new Date(ms).toISOString().replace(/[-:]/g, '').slice(0, 15) + 'Z';
function calendar(group) {
  const events = [];
  for (let week = -7; week <= 8; week++) {
    const start = now + week * 7 * 86400000;
    for (const [uid, summary, offset] of [[`lecture-${week}`, 'E2E shared lecture', 0], [`group-${group}-${week}`, `GR${group} - E2E Algorithms`, 7200000]]) {
      events.push('BEGIN:VEVENT', `UID:${uid}`, `SUMMARY:${summary}`, `DTSTART:${stamp(start + offset)}`, `DTEND:${stamp(start + offset + 3600000)}`, 'END:VEVENT');
    }
  }
  return ['BEGIN:VCALENDAR', 'VERSION:2.0', ...events, 'END:VCALENDAR'].join('\r\n');
}
for (const group of [1, 2]) await request('/api/calendars', { kind: 'file', name: `Group ${group}`, fileName: `group${group}.ics`, ics: calendar(group) });
localSql(`
UPDATE courses SET tracked = 1 WHERE name = 'GR1 - E2E Algorithms' AND user_id = (SELECT id FROM users WHERE google_sub = 'dev:e2e@example.test');
INSERT INTO homework (user_id,title,due_date,tz,due_at,created_at,updated_at) SELECT id,'E2E homework',date('now','+7 days'),'UTC',(unixepoch()+604800)*1000,unixepoch()*1000,unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO habits (user_id,name,schedule,created_at,updated_at) SELECT id,'E2E habit','{"type":"daily"}',unixepoch()*1000,unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO habit_logs SELECT id,date('now'),1 FROM habits WHERE name='E2E habit' AND user_id=(SELECT id FROM users WHERE google_sub='dev:e2e@example.test');
INSERT INTO countdowns (user_id,title,at,created_at,updated_at) SELECT id,'E2E countdown',(unixepoch()+604800)*1000,unixepoch()*1000,unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO focus_sessions (user_id,client_id,label,started_at,ended_at,planned_seconds,focus_seconds,created_at) SELECT id,'e2e-focus','E2E focus',(unixepoch()-1500)*1000,unixepoch()*1000,1500,1500,unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO breaks (user_id,label,start_date,end_date,changes,created_at) SELECT id,'E2E break',date('now','+20 days'),date('now','+21 days'),'[]',unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
`);
const data = await request('/api/data');
const group1 = data.calendars.find(c => c.name === 'Group 1');
const group2 = data.calendars.find(c => c.name === 'Group 2');
assert.ok(group1 && group2, 'both calendars');
const shared = data.events.find(e => e.summary === 'E2E shared lecture');
const first = data.events.find(e => e.summary === 'GR1 - E2E Algorithms');
const second = data.events.find(e => e.summary === 'GR2 - E2E Algorithms');
assert.deepEqual(shared.cals, [group1.id, group2.id].sort((a,b) => a-b));
assert.deepEqual(first.cals, [group1.id]);
assert.deepEqual(second.cals, [group2.id]);
assert.ok(data.events.length >= 48, 'semester events');
assert.ok(data.courses.some(c => c.name === 'GR1 - E2E Algorithms' && c.tracked));
assert.ok(data.courses.some(c => c.name === 'GR2 - E2E Algorithms'));
assert.ok(data.homework.some(x => x.title === 'E2E homework'));
const habit = data.habits.list.find(x => x.name === 'E2E habit');
assert.ok(habit && data.habits.logs.some(x => x.habit_id === habit.id && x.count === 1));
assert.ok(data.countdowns.some(x => x.title === 'E2E countdown'));
assert.ok(data.focus.sessions.some(x => x.label === 'E2E focus'));
assert.ok(data.breaks.some(x => x.label === 'E2E break'));
console.log(`Local /api/data verified ${data.events.length} events and shared/group-specific calendar memberships, homework, habit logs, countdown, focus and break.`);
