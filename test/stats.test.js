import { test } from 'node:test';
import assert from 'node:assert/strict';
import { computeStats, splitGroup } from '../public/stats.js';

const H = 3600000;
const now = 100 * H;
const ev = (id, startH, extra = {}) => ({ id, course_id: 1, start_at: startH * H, end_at: (startH + 2) * H, status: null, tag: null, ...extra });
const courses = { 1: { id: 1, tag: 'normal' } };

test('10 sessions at 80%: two skips allowed', () => {
  const events = Array.from({ length: 10 }, (_, i) => ev(i, i * 24));
  const s = computeStats(events, courses, { now: 0 });
  assert.equal(s.total, 10);
  assert.equal(s.remainingDisplay, 2);
  assert.equal(s.level, 'ok');
});

test('absences, planned skips, excused and optional', () => {
  const events = [
    ev(1, 10, { status: 'present' }), ev(2, 20, { status: 'absent' }), ev(3, 30, { status: 'excused' }),
    ev(4, 40), ev(5, 200, { status: 'absent' }), ev(6, 210, { tag: 'optional', status: 'absent' }),
    ev(7, 220), ev(8, 230), ev(9, 240), ev(10, 250), ev(11, 260),
  ];
  const s = computeStats(events, courses, { now });
  assert.equal(s.total, 9);           // excluded: excused + optional
  assert.equal(s.absent, 2);
  assert.equal(s.plannedAbsent, 1);
  assert.equal(s.unmarkedPast, 1);
  assert.equal(s.currentRate, 0.5);   // 1 present / 2 marked
  assert.ok(Math.abs(s.remaining - (9 * 0.2 - 2)) < 1e-9);
  assert.equal(s.level, 'fail');
});

test('exam absence is flagged', () => {
  const events = Array.from({ length: 20 }, (_, i) => ev(i, i * 24, i === 0 ? { tag: 'exam', status: 'absent' } : {}));
  const s = computeStats(events, courses, { now: 0 });
  assert.equal(s.missedMustAttend.length, 1);
  assert.equal(s.level, 'risk');
});

test('hours unit weighs by duration', () => {
  const events = [ev(1, 0), { ...ev(2, 10), end_at: 13 * H, status: 'absent' }];
  const s = computeStats(events, courses, { unit: 'hours', now });
  assert.equal(s.total, 5);
  assert.equal(s.absent, 3);
});

test('splitGroup', () => {
  assert.deepEqual(splitGroup('GR10 - French for Fall 26 T1'), { group: 'GR10', family: 'French for Fall 26 T1' });
  assert.deepEqual(splitGroup('G2 - Adapting to a New Culture'), { group: 'G2', family: 'Adapting to a New Culture' });
  assert.equal(splitGroup('Tutorat & French for Fall 26 T1'), null);
});
