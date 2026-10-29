import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
import { d1 } from '../../test/d1-shim.js';
import worker from '../../src/worker.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.resolve(__dirname, '../app/src/test/resources/api');
fs.mkdirSync(outDir, { recursive: true });

async function createAccount(email) {
  const env = { DB: d1(), DEV_LOGIN: '1' };
  const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
  const devRes = await worker.fetch(new Request(`https://app.test/auth/app/dev?email=${encodeURIComponent(email)}&challenge=${challenge}`), env);
  const code = new URL(devRes.headers.get('Location')).searchParams.get('code');
  const tokenRes = await worker.fetch(new Request('https://app.test/auth/app/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, verifier }),
  }), env);
  const { token } = await tokenRes.json();
  return { env, token };
}

async function generateEmpty() {
  const { env, token } = await createAccount('newuser@example.com');
  const dataRes = await worker.fetch(new Request('https://app.test/api/data', {
    headers: { Authorization: `Bearer ${token}` },
  }), env);
  const data = await dataRes.json();
  const filePath = path.join(outDir, 'data_empty.json');
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2) + '\n');
  console.log('Wrote', filePath);
}

async function generateFull() {
  const { env, token } = await createAccount('student@epita.fr');
  const authHeader = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  // 1. Calendar with 3 events across 2 courses
  const ics = [
    'BEGIN:VCALENDAR',
    'BEGIN:VEVENT',
    'UID:maths-1',
    'SUMMARY:Mathématiques',
    'LOCATION:Amphi 1',
    'DTSTART:20261102T080000Z',
    'DTEND:20261102T100000Z',
    'END:VEVENT',
    'BEGIN:VEVENT',
    'UID:maths-2',
    'SUMMARY:Mathématiques',
    'LOCATION:Amphi 1',
    'DTSTART:20261104T080000Z',
    'DTEND:20261104T100000Z',
    'END:VEVENT',
    'BEGIN:VEVENT',
    'UID:algo-1',
    'SUMMARY:Algorithmique',
    'LOCATION:Salle 302',
    'DTSTART:20261103T130000Z',
    'DTEND:20261103T150000Z',
    'END:VEVENT',
    'END:VCALENDAR',
  ].join('\r\n');

  await worker.fetch(new Request('https://app.test/api/calendars', {
    method: 'POST',
    headers: authHeader,
    body: JSON.stringify({ kind: 'file', ics, fileName: 'epita_classes.ics' }),
  }), env);

  // 2. Homework without reminder
  await worker.fetch(new Request('https://app.test/api/homework', {
    method: 'POST',
    headers: authHeader,
    body: JSON.stringify({
      title: 'Maths Exercise 4',
      course_id: 1,
      due_date: '2026-11-15',
      tz: 'Europe/Paris',
      remind_minutes: null,
      effort_minutes: null,
      notes: 'Do problems 1 to 5',
    }),
  }), env);

  // 3. Homework with reminder and effort
  await worker.fetch(new Request('https://app.test/api/homework', {
    method: 'POST',
    headers: authHeader,
    body: JSON.stringify({
      title: 'Algo Binary Trees',
      course_id: 2,
      due_date: '2026-11-18',
      due_time: '17:00',
      tz: 'Europe/Paris',
      remind_minutes: 60,
      effort_minutes: 120,
      notes: 'Implement AVL rotation',
    }),
  }), env);

  // 4. Focus session without label
  const now = 1793188000000; // Fixed timestamp for deterministic fixtures
  await worker.fetch(new Request('https://app.test/api/focus', {
    method: 'POST',
    headers: authHeader,
    body: JSON.stringify({
      client_id: 'client-focus-no-label-12345',
      started_at: now - 3600000,
      ended_at: now - 1800000,
      planned_seconds: 1800,
      focus_seconds: 1800,
      completed: true,
      tags: ['study'],
    }),
  }), env);

  // 5. Focus session with label
  await worker.fetch(new Request('https://app.test/api/focus', {
    method: 'POST',
    headers: authHeader,
    body: JSON.stringify({
      client_id: 'client-focus-with-label-12345',
      label: 'Algorithms revision',
      course_id: 2,
      started_at: now - 7200000,
      ended_at: now - 5400000,
      planned_seconds: 1800,
      focus_seconds: 1800,
      completed: true,
      tags: ['algo', 'exam'],
    }),
  }), env);

  // 6. Break
  await worker.fetch(new Request('https://app.test/api/breaks', {
    method: 'POST',
    headers: authHeader,
    body: JSON.stringify({
      from: '2026-11-02',
      to: '2026-11-06',
      label: 'All Saints Break',
      tz: 'Europe/Paris',
    }),
  }), env);

  // 7. Data
  const dataRes = await worker.fetch(new Request('https://app.test/api/data', {
    headers: { Authorization: `Bearer ${token}` },
  }), env);
  const data = await dataRes.json();
  const filePath = path.join(outDir, 'data.json');
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2) + '\n');
  console.log('Wrote', filePath);
}

await generateEmpty();
await generateFull();
