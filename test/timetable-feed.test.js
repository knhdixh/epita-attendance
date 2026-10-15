import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildTimetableFeed } from '../src/timetable-feed.js';
import { parseICS } from '../src/ics.js';

const H = 3600 * 1000;
const now = Date.UTC(2026, 9, 15, 12);
const courses = [{ id: 1, tracked: 1, tag: 'normal' }, { id: 2, tracked: 0, tag: 'normal' }, { id: 3, tracked: 1, tag: 'exam' }];
const ev = (o) => ({ uid: 'u', course_id: 1, summary: 'Maths', location: '', description: '', start_at: now + H, end_at: now + 3 * H,
  status: null, tag: null, note: '', ...o });

test('timetable feed: tracked sessions only, with marks, tags and planned skips', () => {
  const text = buildTimetableFeed([
    ev({ uid: 'a', location: 'KB 604, Villejuif', status: 'present', start_at: now - 5 * H, end_at: now - 3 * H }),
    ev({ uid: 'b', course_id: 2, summary: 'GR2 – Maths' }),
    ev({ uid: 'c', course_id: 3, summary: 'Algo final', note: 'Bring ID' }),
    ev({ uid: 'd', status: 'absent' }),
  ], courses, { now, host: 'epita.knhdixh.com' });

  assert.ok(text.endsWith('END:VCALENDAR\r\n'));
  assert.ok(text.split('\r\n').every((l) => new TextEncoder().encode(l).length <= 75), 'lines are folded');
  const { events } = parseICS(text);
  assert.deepEqual(events.map((e) => e.summary), ['Maths', 'Exam: Algo final', 'Skipping: Maths']);
  assert.equal(events[0].location, 'KB 604, Villejuif');
  assert.equal(events[0].description, 'Attended');
  assert.equal(events[0].uid, 'a-timetable@epita.knhdixh.com');
  assert.equal(events[1].description, 'Exam\n\nBring ID');
  assert.equal(events[2].description, 'Planned absence');
  assert.equal(events[0].start, now - 5 * H);
});
