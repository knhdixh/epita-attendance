import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildTimetableFeed } from '../src/timetable-feed.js';
import { parseICS } from '../src/ics.js';

const H = 3600 * 1000;
const now = Date.UTC(2026, 9, 15, 12);
const courses = [{ id: 1, tracked: 1 }, { id: 2, tracked: 0 }, { id: 3, tracked: 1 }];
const ev = (o) => ({ uid: 'u', course_id: 1, summary: 'Maths', location: '', description: '', start_at: now + H, end_at: now + 3 * H, ...o });

test('timetable feed: every session of tracked courses, exactly as imported', () => {
  const text = buildTimetableFeed([
    ev({ uid: 'a', location: 'KB 604, Villejuif', description: 'Prof: Smith', start_at: now - 90 * 24 * H, end_at: now - 89 * 24 * H }),
    ev({ uid: 'b', course_id: 2, summary: 'GR2 – Maths' }),
    ev({ uid: 'c', course_id: 3, summary: 'Algo final; room B' }),
  ], courses, { now, host: 'epita.knhdixh.com' });

  assert.ok(text.endsWith('END:VCALENDAR\r\n'));
  assert.ok(text.split('\r\n').every((l) => new TextEncoder().encode(l).length <= 75), 'lines are folded');
  const { events } = parseICS(text);
  assert.deepEqual(events.map((e) => e.summary), ['Maths', 'Algo final; room B']);
  assert.equal(events[0].uid, 'a-timetable@epita.knhdixh.com');
  assert.equal(events[0].location, 'KB 604, Villejuif');
  assert.equal(events[0].description, 'Prof: Smith');
  assert.equal(events[0].start, now - 90 * 24 * H, 'past sessions are included');
});

test('timetable feed: groups keeps sessions in any picked calendar, and those in none', () => {
  const text = buildTimetableFeed([
    ev({ uid: 'a', summary: 'SE1 only', cals: [10] }),
    ev({ uid: 'b', summary: 'SE2 only', cals: [11] }),
    ev({ uid: 'c', summary: 'Shared', cals: [10, 11, 12] }),
    ev({ uid: 'd', summary: 'No calendar', cals: [] }),
  ], courses, { now, groups: [10], name: 'Timetable (SE1)' });
  assert.ok(text.includes('X-WR-CALNAME:Timetable (SE1)'));
  assert.deepEqual(parseICS(text).events.map((e) => e.summary), ['SE1 only', 'Shared', 'No calendar']);
});
