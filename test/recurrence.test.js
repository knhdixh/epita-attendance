import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseRecurrence, formatRecurrence, nextOccurrence, cleanRecurrence, parseDay } from '../public/recurrence.js';

const TODAY = '2026-11-03'; // a Tuesday

const next = (text, due, done = due) => {
  const r = parseRecurrence(text, { today: TODAY, due });
  assert.ok(r.ok, `${text}: ${r.error}`);
  return nextOccurrence(r.rule, { due, done });
};

// [repeat, due, done, expected next due date]. 2026-11-02 is a Monday; 2026-11-06 a Friday.
const STEPS = [
  ['every day', '2026-11-03', '2026-11-03', '2026-11-04'],
  ['every day', '2026-11-03', '2026-11-06', '2026-11-07'],          // overdue: on from the day it was done
  ['every day', '2026-11-10', '2026-11-03', '2026-11-11'],          // early: on from the due date
  ['every day', '2026-12-31', '2026-12-31', '2027-01-01'],
  ['every day', '2026-03-28', '2026-03-28', '2026-03-29'],          // EU clocks change on the 29th
  ['every day', '2026-03-29', '2026-03-29', '2026-03-30'],
  ['every other day', '2026-11-03', '2026-11-03', '2026-11-05'],
  ['every 3 days', '2026-11-03', '2026-11-04', '2026-11-06'],
  ['every 3 days', '2026-11-03', '2026-11-10', '2026-11-12'],       // 3, 6, 9, 12
  ['every weekday', '2026-11-06', '2026-11-06', '2026-11-09'],      // Friday to Monday
  ['every weekday', '2026-11-05', '2026-11-05', '2026-11-06'],
  ['every weekday', '2026-11-06', '2026-11-07', '2026-11-09'],      // done on the Saturday
  ['every workday', '2026-11-02', '2026-11-02', '2026-11-03'],
  ['every weekend', '2026-11-07', '2026-11-07', '2026-11-08'],
  ['every weekend', '2026-11-08', '2026-11-08', '2026-11-14'],
  ['every mon, wed', '2026-11-02', '2026-11-02', '2026-11-04'],
  ['every mon, wed', '2026-11-04', '2026-11-04', '2026-11-09'],
  ['every monday and friday', '2026-11-02', '2026-11-02', '2026-11-06'],
  ['every mon-fri', '2026-11-06', '2026-11-06', '2026-11-09'],
  ['every week', '2026-11-03', '2026-11-03', '2026-11-10'],
  ['every week', '2026-10-21', '2026-10-21', '2026-10-28'],         // across the end of daylight saving (25 Oct)
  ['every other week', '2026-11-03', '2026-11-03', '2026-11-17'],
  ['every 2 weeks on mon, wed', '2026-11-02', '2026-11-02', '2026-11-04'],
  ['every 2 weeks on mon, wed', '2026-11-04', '2026-11-04', '2026-11-16'],
  ['every 3rd friday', '2026-11-20', '2026-11-20', '2026-12-18'],
  ['every last friday', '2026-11-27', '2026-11-27', '2026-12-25'],
  ['every last friday', '2026-12-25', '2026-12-25', '2027-01-29'],
  ['every first monday', '2026-11-02', '2026-11-02', '2026-12-07'],
  ['every 2nd tuesday', '2026-11-10', '2026-11-10', '2026-12-08'],
  ['every 2 months on the 3rd friday', '2026-11-20', '2026-11-20', '2027-01-15'],
  ['every month on the 15th', '2026-11-15', '2026-11-15', '2026-12-15'],
  ['every month on the 15th', '2026-11-03', '2026-11-03', '2026-11-15'],
  ['every month on the 31st', '2026-01-31', '2026-01-31', '2026-02-28'],
  ['every month on the 31st', '2026-02-28', '2026-02-28', '2026-03-31'],
  ['every 31st', '2026-04-30', '2026-04-30', '2026-05-31'],
  ['every last day', '2026-02-28', '2026-02-28', '2026-03-31'],
  ['every last day', '2028-01-31', '2028-01-31', '2028-02-29'],     // leap year
  ['every month', '2026-11-03', '2026-11-03', '2026-12-03'],
  ['every month', '2026-01-31', '2026-01-31', '2026-02-28'],
  ['every month', '2026-02-28', '2026-02-28', '2026-03-28'],        // started on the 28th: stays on the 28th
  ['every 3 months starting 1 jan', '2026-11-03', '2026-11-03', '2027-01-01'],
  ['every 3 months starting 1 jan', '2027-01-01', '2027-01-01', '2027-04-01'],
  ['every year', '2026-11-03', '2026-11-03', '2027-11-03'],
  ['every year', '2028-02-29', '2028-02-29', '2029-02-28'],
  ['every year', '2031-02-28', '2031-02-28', '2032-02-28'],
  ['every jan 15', '2026-01-15', '2026-01-15', '2027-01-15'],
  ['every 2 years', '2026-05-05', '2026-05-05', '2028-05-05'],
  ['every day until 5 nov 2026', '2026-11-04', '2026-11-04', '2026-11-05'],
  ['every day until 5 nov 2026', '2026-11-05', '2026-11-05', null],
  ['every week until 2026-11-09', '2026-11-03', '2026-11-03', null],
  ['every week starting 7 dec 2026', '2026-11-03', '2026-11-03', '2026-12-07'],
  ['every 2nd mon until dec 2026', '2026-11-03', '2026-11-09', '2026-12-14'],
  ['every 2nd mon until dec 2026', '2026-12-14', '2026-12-14', null],
  ['every day until february 2028', '2028-02-28', '2028-02-28', '2028-02-29'],
  ['every day until feb 2028', '2028-02-29', '2028-02-29', null],
  ['every day until nov 2026', '2026-11-30', '2026-11-30', null],
  ['every week starting jan 2027', '2026-11-03', '2026-11-03', '2027-01-01'],
  ['every month starting dec 2026 until mar 2027', '2026-11-03', '2026-11-03', '2026-12-01'],
  ['every! 3 days', '2026-11-03', '2026-11-10', '2026-11-13'],
  ['every! day', '2026-11-10', '2026-11-03', '2026-11-04'],          // done early: counted from today
  ['every! week', '2026-11-03', '2026-11-10', '2026-11-17'],
  ['every! month', '2026-01-01', '2026-01-31', '2026-02-28'],
  ['every! year', '2028-02-01', '2028-02-29', '2029-02-28'],
  ['every! mon, wed', '2026-11-01', '2026-11-09', '2026-11-11'],
];
for (const [text, due, done, want] of STEPS) {
  test(`next: ${text}, due ${due}, done ${done}`, () => assert.equal(next(text, due, done), want));
}

// Text in, canonical text out.
const FORMATS = [
  ['every day', 'every day'], ['Every Day', 'every day'], ['daily', 'every day'], ['every other day', 'every other day'],
  ['every 2 days', 'every other day'], ['every 10 days', 'every 10 days'], ['every weekday', 'every weekday'],
  ['every workday', 'every weekday'], ['weekdays', 'every weekday'], ['every mon-fri', 'every weekday'], ['every weekend', 'every weekend'],
  ['every mon, wed', 'every mon, wed'], ['every Monday and Friday', 'every mon, fri'], ['every fri & mon', 'every mon, fri'],
  ['every week', 'every week'], ['weekly', 'every week'], ['every other week', 'every other week'], ['every 3 weeks', 'every 3 weeks'],
  ['every 2 weeks on mon, wed', 'every other week on mon, wed'], ['every 3rd friday', 'every 3rd fri'], ['every last friday', 'every last fri'],
  ['every first monday', 'every 1st mon'], ['every last day', 'every last day'], ['every 15th', 'every month on the 15th'], ['every 3rd', 'every month on the 3rd'],
  ['every month on the 15th', 'every month on the 15th'], ['every month on the 1st', 'every month on the 1st'], ['every month', 'every month'],
  ['monthly', 'every month'], ['every 3 months', 'every 3 months'], ['every year', 'every year'], ['yearly', 'every year'],
  ['every 1 jan', 'every 1 jan'], ['every jan 15', 'every 15 jan'], ['every 3 months starting 1 jan', 'every 3 months starting 1 jan 2027'],
  ['every week until 1 jun', 'every week until 1 jun 2027'], ['every week until dec 2026', 'every week until 31 dec 2026'], ['every week starting feb 2028', 'every week starting 1 feb 2028'], ['every day until 2027-01-01', 'every day until 1 jan 2027'],
  ['every! 3 days', 'every! 3 days'], ['every! week', 'every! week'], ['every.', 'x'],
];
for (const [text, want] of FORMATS.slice(0, -1)) {
  test(`format: "${text}"`, () => {
    const r = parseRecurrence(text, { today: TODAY });
    assert.ok(r.ok, r.error);
    assert.equal(formatRecurrence(r.rule), want);
    // The canonical text reads back as the same rule.
    const again = parseRecurrence(want, { today: TODAY });
    assert.ok(again.ok, again.error);
    assert.deepEqual(again.rule, r.rule);
  });
}

for (const text of ['', '   ', 'every', 'sometimes', 'every fortnight', 'every 0 days', 'every 1000 days', 'every feb 30',
  'every 32nd', 'every 5th friday', 'every day until yesterday', 'every week until 1 jun starting 1 jul', 'every day for ever', 'every mon, funday']) {
  test(`error: "${text}"`, () => {
    const r = parseRecurrence(text, { today: TODAY });
    assert.equal(r.ok, false);
    assert.ok(r.error.length > 5);
  });
}

const DAYS = [
  ['2026-06-01', '2026-06-01'], ['1 jun', '2027-06-01'], ['jun 1', '2027-06-01'], ['1/6', '2027-06-01'], ['1/6/2026', '2026-06-01'],
  ['3 nov', '2026-11-03'], ['2 nov', '2027-11-02'], ['today', TODAY], ['tomorrow', '2026-11-04'], ['1st of march', '2027-03-01'],
  ['march 1st 2028', '2028-03-01'], ['29 feb', '2028-02-29'], ['30 feb', null], ['31/4', null], ['soon', null], ['2026-13-01', null],
];
for (const [text, want] of DAYS) test(`date: "${text}"`, () => assert.equal(parseDay(text, TODAY), want));

test('a series that never ends keeps going for years without getting stuck', () => {
  for (const text of ['every day', 'every weekday', 'every 3rd friday', 'every month on the 31st', 'every other week', 'every year', 'every! 3 days']) {
    let due = '2026-01-31', n = 0;
    const r = parseRecurrence(text, { today: TODAY, due });
    for (; n < 400; n++) {
      const d = nextOccurrence(r.rule, { due, done: due });
      assert.ok(d > due, `${text}: ${d} after ${due}`);
      due = d;
    }
  }
});

test('cleanRecurrence gives the canonical text, or the error', () => {
  assert.deepEqual(cleanRecurrence('Every  Mon,Wed', { today: TODAY }).text, 'every mon, wed');
  assert.match(cleanRecurrence('banana').error, /couldn't read/);
});

test('a day-of-month repeat without a day keeps the day it started on only where it would drift', () => {
  assert.equal(formatRecurrence(parseRecurrence('every month', { today: TODAY, due: '2026-01-31' }).rule), 'every month on the 31st');
  assert.equal(formatRecurrence(parseRecurrence('every month', { today: TODAY, due: '2026-01-15' }).rule), 'every month');
  assert.equal(formatRecurrence(parseRecurrence('every year', { today: TODAY, due: '2028-02-29' }).rule), 'every 29 feb');
});
