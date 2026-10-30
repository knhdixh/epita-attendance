import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2];
if (!dir) throw new Error('Expected isolated local Wrangler directory');
const base = 'http://localhost:8787';
const login = await fetch(`${base}/auth/dev?email=e2e%40example.test`, { redirect: 'manual' });
if (login.status !== 302) throw new Error(`Local login failed: ${login.status}`);
const sql = `
DELETE FROM users WHERE google_sub = 'dev:e2e@example.test';
INSERT INTO users (google_sub,email,name,created_at) VALUES ('dev:e2e@example.test','e2e@example.test','e2e',unixepoch()*1000);
INSERT INTO calendar_sources (user_id,kind,name,created_at) SELECT id,'file','Group A',unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO calendar_sources (user_id,kind,name,created_at) SELECT id,'file','Group B',unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO courses (user_id,key,name) SELECT id,'e2e-a','Algorithms A' FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO courses (user_id,key,name) SELECT id,'e2e-b','Algorithms B' FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO events (user_id,course_id,uid,summary,start_at,end_at) SELECT user_id,id,'e2e-'||key,name,unixepoch()*1000+86400000,unixepoch()*1000+90000000 FROM courses WHERE key IN ('e2e-a','e2e-b') AND user_id=(SELECT id FROM users WHERE google_sub='dev:e2e@example.test');
INSERT INTO event_sources (event_id,source_id,seen_at) SELECT e.id,c.id,unixepoch()*1000 FROM events e JOIN calendar_sources c ON c.user_id=e.user_id AND ((e.uid='e2e-e2e-a' AND c.name='Group A') OR (e.uid='e2e-e2e-b' AND c.name='Group B')) WHERE e.user_id=(SELECT id FROM users WHERE google_sub='dev:e2e@example.test');
INSERT INTO homework (user_id,title,due_date,tz,due_at,created_at,updated_at) SELECT id,'E2E homework',date('now','+7 days'),'UTC',(unixepoch()+604800)*1000,unixepoch()*1000,unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO habits (user_id,name,schedule,created_at,updated_at) SELECT id,'E2E habit','{"type":"daily"}',unixepoch()*1000,unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO habit_logs SELECT id,date('now'),1 FROM habits WHERE name='E2E habit' AND user_id=(SELECT id FROM users WHERE google_sub='dev:e2e@example.test');
INSERT INTO countdowns (user_id,title,at,created_at,updated_at) SELECT id,'E2E countdown',(unixepoch()+604800)*1000,unixepoch()*1000,unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
INSERT INTO focus_sessions (user_id,client_id,label,started_at,ended_at,planned_seconds,focus_seconds,created_at) SELECT id,'e2e-focus','E2E focus',(unixepoch()-1500)*1000,unixepoch()*1000,1500,1500,unixepoch()*1000 FROM users WHERE google_sub='dev:e2e@example.test';
`;
const path = join(dir, 'seed.sql'); writeFileSync(path, sql);
execFileSync(join(dir, 'node_modules/.bin/wrangler'), ['d1','execute','DB','--local','--file',path], { cwd: dir, stdio: 'inherit' });
// Acquire a fresh session after seeding replaces the deterministic account.
const relogin = await fetch(`${base}/auth/dev?email=e2e%40example.test`, { redirect: 'manual' });
const freshCookie = relogin.headers.get('set-cookie').split(';')[0];
const response = await fetch(`${base}/api/data`, { headers: { cookie: freshCookie } });
if (!response.ok) throw new Error(`Local data request failed: ${response.status}`);
const data = await response.json();
for (const [key, text] of [['courses','Algorithms A'],['homework','E2E homework'],['habits','E2E habit'],['countdowns','E2E countdown']]) {
  if (!JSON.stringify(data[key]).includes(text)) throw new Error(`Seeded ${key} missing from real /api/data`);
}
console.log('Local /api/data returned seeded courses, homework, habits and countdowns.');
