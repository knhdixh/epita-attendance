import { test } from 'node:test';
import assert from 'node:assert/strict';
import { period, shift, isoWeek, layout, courseColors, autoPlan, atMinutes } from '../public/calendar.js';

const MIN = 60000;
const at = (d, h, m = 0) => new Date(2026, 10, d, h, m).getTime(); // November 2026, local time

test('periods: day, 3 days, a week from Monday, a month in whole weeks', () => {
  const wed = at(4, 15); // Wednesday 4 Nov
  assert.deepEqual(period('day', wed).days, [at(4, 0)]);
  assert.deepEqual(period('3day', wed).days, [at(4, 0), at(5, 0), at(6, 0)]);
  const week = period('week', wed);
  assert.equal(week.start, at(2, 0));
  assert.equal(week.end, at(9, 0));
  const month = period('month', wed);
  assert.equal(month.start, new Date(2026, 9, 26).getTime()); // Monday 26 Oct
  assert.equal(month.days.length, 42);                         // 1 Nov is a Sunday: six weeks
  assert.equal(month.days.at(-1), new Date(2026, 11, 6).getTime());
  assert.equal(new Date(shift('month', at(30, 9), 1)).getMonth(), 11);
  assert.equal(shift('week', wed, -1), at(4 - 7, 15));
  assert.equal(isoWeek(wed), 45);
  assert.equal(isoWeek(new Date(2027, 0, 1).getTime()), 53);   // 1 Jan 2027 is in week 53 of 2026
});

test('layout: overlapping items share the width, a chain of overlaps shares one group', () => {
  const l = layout([
    { id: 'a', start: 0, end: 60 }, { id: 'b', start: 30, end: 90 }, { id: 'c', start: 60, end: 120 },
    { id: 'd', start: 200, end: 260 },
  ]);
  assert.deepEqual(l.get('a'), { col: 0, cols: 2 });
  assert.deepEqual(l.get('b'), { col: 1, cols: 2 });
  assert.deepEqual(l.get('c'), { col: 0, cols: 2 }); // a has ended: c takes its column
  assert.deepEqual(l.get('d'), { col: 0, cols: 1 });
});

test('course colours: tracked courses first, stable by id', () => {
  const c = courseColors([{ id: 3, tracked: 1 }, { id: 1, tracked: 0 }, { id: 2, tracked: 1 }]);
  assert.equal(c.get(2), 'blue');
  assert.equal(c.get(3), 'orange');
  assert.equal(c.get(1), 'green');
});

test('auto-plan: earliest free slot before the deadline, away from classes, with a daily cap', () => {
  const now = at(2, 9, 7); // Monday 09:07
  const busy = [[at(2, 10), at(2, 12)], [at(2, 13), at(2, 18)]];
  const { planned, unplaced } = autoPlan({
    now, until: at(9, 0), busy, blocks: [[at(3, 9), at(3, 12)]], cap: 240,
    todos: [
      { id: 1, due_at: at(2, 23), effort: 30 },   // fits 9:15–9:45, before class at 10 (10 min buffer)
      { id: 2, due_at: at(5, 12), effort: 60 },   // 12:10–13:00 is too short; after 18:10
      { id: 3, due_at: at(3, 12), effort: 300 },  // a 2 h first part, right after #2
      { id: 4, due_at: at(2, 9, 30), effort: 60 },// can't be done before 9:30
      { id: 5, due_at: at(1, 9), effort: 45 },    // overdue: as soon as possible, but Monday is full (210 + 45 > 240)
      { id: 6, due_at: at(3, 9), effort: 90 },    // Tuesday 8:00 is taken by #5 and 9:00 by a planned block
    ],
  });
  assert.deepEqual(planned.map((p) => [p.id, new Date(p.start).getDate(), new Date(p.start).getHours(), new Date(p.start).getMinutes(), p.minutes]), [
    [1, 2, 9, 15, 30],
    [2, 2, 18, 15, 60],
    [3, 2, 19, 15, 120],
    [5, 3, 8, 0, 45],
  ]);
  assert.deepEqual(unplaced, [4, 6]);
  assert.equal(atMinutes(at(2, 0), 9 * 60), at(2, 9));
});
