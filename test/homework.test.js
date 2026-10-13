import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dueInstant, buildGcalEvent, buildFeed, reminderMinutes, isValidTimeZone } from '../src/homework.js';

const base = { id: 7, title: 'TP 3, part 2', notes: 'Push to Git;\nsend link', course_id: 1, tz: 'Europe/Paris', done: 0, updated_at: 0 };

test('dueInstant: timed and all-day, DST-aware', () => {
  assert.equal(dueInstant('2026-10-20', '09:00', 'Europe/Paris'), Date.UTC(2026, 9, 20, 7));    // CEST
  assert.equal(dueInstant('2026-11-20', '09:00', 'Europe/Paris'), Date.UTC(2026, 10, 20, 8));   // CET
  assert.equal(dueInstant('2026-11-20', null, 'Europe/Paris'), Date.UTC(2026, 10, 20, 23) - 1); // end of day
  assert.ok(isValidTimeZone('Europe/Paris'));
  assert.ok(!isValidTimeZone('Mars/Olympus'));
});

test('timed homework -> 30 min Google event ending at deadline with popup reminder', () => {
  const hw = { ...base, due_date: '2026-10-20', due_time: '09:00', due_at: Date.UTC(2026, 9, 20, 7), remind_minutes: 1440 };
  const ev = buildGcalEvent(hw, 'Relational Databases', 'https://x.dev');
  assert.equal(ev.summary, '📚 TP 3, part 2 · Relational Databases');
  assert.equal(ev.start.dateTime, '2026-10-20T06:30:00.000Z');
  assert.equal(ev.end.dateTime, '2026-10-20T07:00:00.000Z');
  assert.equal(ev.start.timeZone, 'Europe/Paris');
  assert.deepEqual(ev.reminders, { useDefault: false, overrides: [{ method: 'popup', minutes: 1440 }] });
  assert.match(ev.description, /https:\/\/x\.dev\/#homework/);
});

test('all-day homework, done homework, reminder conversion', () => {
  const hw = { ...base, due_date: '2026-12-31', due_time: null, due_at: 0, remind_minutes: 1440 };
  const ev = buildGcalEvent(hw, null);
  assert.deepEqual(ev.start, { date: '2026-12-31' });
  assert.deepEqual(ev.end, { date: '2027-01-01' });
  assert.equal(reminderMinutes(hw), 360);                         // 18:00 the day before
  assert.equal(reminderMinutes({ ...hw, remind_minutes: 2880 }), 2880 - 1080);
  assert.equal(reminderMinutes({ ...hw, remind_minutes: 60 }), 360);
  assert.equal(reminderMinutes({ ...hw, remind_minutes: null }), null);
  const done = buildGcalEvent({ ...hw, done: 1 }, null);
  assert.equal(done.summary, '✅ TP 3, part 2');
  assert.deepEqual(done.reminders.overrides, []);
});

test('ics feed: escaping, folding, alarms', () => {
  const longTitle = 'Écrire un rapport très détaillé sur les bases de données relationnelles et leurs index';
  const hws = [
    { ...base, due_date: '2026-10-20', due_time: '09:00', due_at: Date.UTC(2026, 9, 20, 7), remind_minutes: 60 },
    { ...base, id: 8, title: longTitle, due_date: '2026-10-21', due_time: null, due_at: 0, remind_minutes: null },
  ];
  const ics = buildFeed(hws, { 1: 'Algo' }, { host: 'h.dev' });
  assert.match(ics, /UID:homework-7@h\.dev/);
  assert.match(ics, /DTSTART:20261020T063000Z\r\nDTEND:20261020T070000Z/);
  assert.match(ics, /SUMMARY:📚 TP 3\\, part 2 · Algo/);
  assert.ok(ics.includes('DESCRIPTION:Push to Git\\;\\nsend link'));
  assert.match(ics, /TRIGGER:-PT60M/);
  assert.match(ics, /DTSTART;VALUE=DATE:20261021\r\nDTEND;VALUE=DATE:20261022/);
  assert.equal((ics.match(/BEGIN:VALARM/g) || []).length, 1);
  const enc = new TextEncoder();
  for (const line of ics.split('\r\n')) assert.ok(enc.encode(line).length <= 75, `line too long: ${line}`);
  // Round-trips through our own parser.
  return import('../src/ics.js').then(({ parseICS }) => {
    const { events } = parseICS(ics);
    assert.equal(events.length, 2);
    assert.equal(events[1].summary, `📚 ${longTitle} · Algo`);
  });
});
