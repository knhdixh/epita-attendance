import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseQuickAdd, dayFromPhrase, minutesFrom } from '../public/quickadd.js';

const TODAY = '2026-11-03'; // a Tuesday
const q = (text) => parseQuickAdd(text, { today: TODAY });

// [input, the fields that must be as given]. Everything else is left at "not there".
const NONE = { title: '', due_date: null, due_time: null, recurrence: null, project: null, section: null, labels: [], priority: 1, deadline: null, reminder: null, duration: null };
const CASES = [
  ['Buy milk', { title: 'Buy milk' }],
  ['', {}],
  ['   ', {}],
  ['Buy milk today', { title: 'Buy milk', due_date: '2026-11-03' }],
  ['Buy milk tomorrow', { title: 'Buy milk', due_date: '2026-11-04' }],
  ['Buy milk tmr', { title: 'Buy milk', due_date: '2026-11-04' }],
  ['buy milk tom', { title: 'buy milk', due_date: '2026-11-04' }],
  ['Call Tom', { title: 'Call Tom' }],
  ['Buy milk yesterday', { title: 'Buy milk', due_date: '2026-11-02' }],
  ['Call mom fri', { title: 'Call mom', due_date: '2026-11-06' }],
  ['Call mom on friday', { title: 'Call mom', due_date: '2026-11-06' }],
  ['Submit by friday', { title: 'Submit', due_date: '2026-11-06' }],
  ['Party on friday', { title: 'Party', due_date: '2026-11-06' }],
  ['Standup tue', { title: 'Standup', due_date: '2026-11-10' }],         // today is a Tuesday: the next one
  ['Gym wed', { title: 'Gym', due_date: '2026-11-04' }],
  ['Gym next mon', { title: 'Gym', due_date: '2026-11-09' }],
  ['Plan next week', { title: 'Plan', due_date: '2026-11-09' }],
  ['Plan next month', { title: 'Plan', due_date: '2026-12-01' }],
  ['Hike weekend', { title: 'Hike', due_date: '2026-11-07' }],
  ['Call in 3 days', { title: 'Call', due_date: '2026-11-06' }],
  ['Call in 2 weeks', { title: 'Call', due_date: '2026-11-17' }],
  ['Call in a month', { title: 'Call', due_date: '2026-12-03' }],
  ['Call in 1 year', { title: 'Call', due_date: '2027-11-03' }],
  ['Renew jan 27', { title: 'Renew', due_date: '2027-01-27' }],
  ['Renew 27 jan', { title: 'Renew', due_date: '2027-01-27' }],
  ['Renew 27/1', { title: 'Renew', due_date: '2027-01-27' }],
  ['Renew 27/1/2028', { title: 'Renew', due_date: '2028-01-27' }],
  ['Renew 27.1.28', { title: 'Renew', due_date: '2028-01-27' }],
  ['Renew 3 nov', { title: 'Renew', due_date: '2026-11-03' }],
  ['Renew 2 nov', { title: 'Renew', due_date: '2027-11-02' }],
  ['Renew 2027-01-27', { title: 'Renew', due_date: '2027-01-27' }],
  ['Renew March 1st', { title: 'Renew', due_date: '2027-03-01' }],
  ['Renew 29 feb', { title: 'Renew', due_date: '2028-02-29' }],
  ['Renew 30 feb', { title: 'Renew 30 feb' }],
  ['Meet at 9am', { title: 'Meet', due_date: '2026-11-03', due_time: '09:00' }],
  ['Meet 9:30', { title: 'Meet', due_date: '2026-11-03', due_time: '09:30' }],
  ['Meet 9.30pm', { title: 'Meet', due_date: '2026-11-03', due_time: '21:30' }],
  ['Meet at 18', { title: 'Meet', due_date: '2026-11-03', due_time: '18:00' }],
  ['Meet 18h30', { title: 'Meet', due_date: '2026-11-03', due_time: '18:30' }],
  ['Meet 12am', { title: 'Meet', due_date: '2026-11-03', due_time: '00:00' }],
  ['Meet 12pm', { title: 'Meet', due_date: '2026-11-03', due_time: '12:00' }],
  ['Meet 25:00', { title: 'Meet 25:00' }],
  ['Meet 13pm', { title: 'Meet 13pm' }],
  ['Meet at 9am tomorrow', { title: 'Meet', due_date: '2026-11-04', due_time: '09:00' }],
  ['Lunch tomorrow at 12:30', { title: 'Lunch', due_date: '2026-11-04', due_time: '12:30' }],
  ['Task #Home', { title: 'Task', project: 'Home' }],
  ['Task #"Big project"', { title: 'Task', project: 'Big project' }],
  ['Task #Home /Chores', { title: 'Task', project: 'Home', section: 'Chores' }],
  ['#today', { project: 'today' }],
  ['Task @a @b @A', { title: 'Task', labels: ['a', 'b'] }],
  ['Mail me@x.com', { title: 'Mail me@x.com' }],
  ['Learn C# today', { title: 'Learn C#', due_date: '2026-11-03' }],
  ['Task p1', { title: 'Task', priority: 4 }],
  ['Task p2', { title: 'Task', priority: 3 }],
  ['Task P3', { title: 'Task', priority: 2 }],
  ['Task p4', { title: 'Task', priority: 1 }],
  ['Task p5', { title: 'Task p5' }],
  ['Task !30m', { title: 'Task', reminder: 30 }],
  ['Task !2h', { title: 'Task', reminder: 120 }],
  ['Task !1d', { title: 'Task', reminder: 1440 }],
  ['Task !1h30m', { title: 'Task', reminder: 90 }],
  ['Task !99d', { title: 'Task !99d' }],
  ['Task for 45m', { title: 'Task', duration: 45 }],
  ['Task for 2h', { title: 'Task', duration: 120 }],
  ['Task for 1h30m', { title: 'Task', duration: 90 }],
  ['Task for 1.5h', { title: 'Task', duration: 90 }],
  ['Task for 90 min', { title: 'Task', duration: 90 }],
  ['Meeting for 45 minutes', { title: 'Meeting', duration: 45 }],
  ['Task for 25h', { title: 'Task for 25h' }],
  ['Thanks for you', { title: 'Thanks for you' }],
  ['Task {fri}', { title: 'Task', deadline: '2026-11-06' }],
  ['Task {27 jan}', { title: 'Task', deadline: '2027-01-27' }],
  ['Task {nonsense}', { title: 'Task {nonsense}' }],
  ['Task fri {mon}', { title: 'Task', due_date: '2026-11-06', deadline: '2026-11-09' }],
  ['Water plants every day', { title: 'Water plants', due_date: '2026-11-03', recurrence: 'every day' }],
  ['Gym every mon, wed', { title: 'Gym', due_date: '2026-11-04', recurrence: 'every mon, wed' }],
  ['Standup every weekday at 9:30', { title: 'Standup', due_date: '2026-11-03', due_time: '09:30', recurrence: 'every weekday' }],
  ['Meet every 3rd friday', { title: 'Meet', due_date: '2026-11-20', recurrence: 'every 3rd fri' }],
  ['Pay every month on the 15th', { title: 'Pay', due_date: '2026-11-15', recurrence: 'every month on the 15th' }],
  ['Pay every other week', { title: 'Pay', due_date: '2026-11-03', recurrence: 'every other week' }],
  ['Stretch every! 3 days', { title: 'Stretch', due_date: '2026-11-03', recurrence: 'every! 3 days' }],
  ['Rent every month on the 1st until 1 jun', { title: 'Rent', due_date: '2026-12-01', recurrence: 'every month on the 1st until 1 jun 2027' }],
  ['Gym every mon starting 7 dec', { title: 'Gym', due_date: '2026-12-07', recurrence: 'every mon starting 7 dec 2026' }],
  ['Walk every day tomorrow', { title: 'Walk', due_date: '2026-11-04', recurrence: 'every day' }],
  ['Clean every window', { title: 'Clean every window' }],
  ['Submit report fri at 5pm p2 #School @urgent !1h for 2h {mon}', {
    title: 'Submit report', due_date: '2026-11-06', due_time: '17:00', priority: 3, project: 'School', labels: ['urgent'], reminder: 60, duration: 120, deadline: '2026-11-09',
  }],
];
for (const [input, want] of CASES) {
  test(`quick add: "${input}"`, () => {
    const { ranges, ...got } = q(input);
    assert.deepEqual(got, { ...NONE, ...want });
    assert.ok(Array.isArray(ranges));
  });
}

test('ranges say where each part was read from', () => {
  assert.deepEqual(q('Buy milk tomorrow p1').ranges.map(({ kind, start, end, text }) => [kind, start, end, text]),
    [['date', 9, 17, 'tomorrow'], ['priority', 18, 20, 'p1']]);
  assert.deepEqual(q('Call on friday').ranges.map((r) => [r.kind, r.text]), [['date', 'on friday']]);
  assert.deepEqual(q('Gym every mon, wed #Home').ranges.map((r) => [r.kind, r.text, r.value]),
    [['recurrence', 'every mon, wed', 'every mon, wed'], ['project', '#Home', 'Home']]);
  assert.deepEqual(q('Nothing here').ranges, []);
});

test('ranges never overlap, match the text, and what is left is the title', () => {
  for (const [input] of CASES) {
    const { ranges, title } = q(input);
    let end = 0, left = '';
    for (const r of ranges) {
      assert.equal(input.slice(r.start, r.end), r.text, input);
      assert.ok(r.start >= end, `overlap in "${input}"`);
      left += input.slice(end, r.start) + ' ';
      end = r.end;
    }
    left += input.slice(end);
    const squashed = left.replace(/\s+/g, ' ').replace(/^[\s,–—:-]+|[\s,–—:-]+$/g, '').trim();
    // A trailing "on", "at", "by", "due" or "for" left behind by a part that was taken out is dropped from the title.
    assert.equal(title, squashed.replace(/\s+(?:on|at|by|due|for)$/i, ''), input);
  }
});

test('dates without a year are the next time they come round', () => {
  assert.equal(dayFromPhrase('jan 27', '2026-11-03'), '2027-01-27');
  assert.equal(dayFromPhrase('jan 27', '2026-01-10'), '2026-01-27');
  assert.equal(dayFromPhrase('jan 27', '2026-01-27'), '2026-01-27');
  assert.equal(dayFromPhrase('jan 27', '2026-01-28'), '2027-01-27');
});

test('month ends and next month', () => {
  assert.equal(parseQuickAdd('x in a month', { today: '2026-01-31' }).due_date, '2026-02-28');
  assert.equal(parseQuickAdd('x in 1 month', { today: '2028-01-31' }).due_date, '2028-02-29');
  assert.equal(parseQuickAdd('x next month', { today: '2026-12-15' }).due_date, '2027-01-01');
  assert.equal(parseQuickAdd('x in 1 year', { today: '2028-02-29' }).due_date, '2029-02-28');
});

test('weekdays are the next one after today, whatever today is', () => {
  for (let i = 0; i < 7; i++) {
    const today = `2026-11-${String(2 + i).padStart(2, '0')}`; // Monday to Sunday
    const names = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
    for (let j = 0; j < 7; j++) {
      const got = parseQuickAdd(`x ${names[j]}`, { today }).due_date;
      const gap = (new Date(got).getTime() - new Date(today).getTime()) / 86400000;
      assert.equal(gap, ((j - i + 7) % 7) || 7, `${names[j]} from ${today}`);
    }
  }
});

test('year boundaries and DST do not move a date', () => {
  assert.equal(parseQuickAdd('x tomorrow', { today: '2026-12-31' }).due_date, '2027-01-01');
  assert.equal(parseQuickAdd('x tomorrow', { today: '2026-03-28' }).due_date, '2026-03-29');
  assert.equal(parseQuickAdd('x in 2 days', { today: '2026-03-28' }).due_date, '2026-03-30');
  assert.equal(parseQuickAdd('x in 1 week', { today: '2026-10-25' }).due_date, '2026-11-01');
});

test('durations', () => {
  for (const [text, want] of [['45m', 45], ['2h', 120], ['1h30m', 90], ['1.5h', 90], ['1,5h', 90], ['90 min', 90], ['1 hour 30 min', 90], ['2 hours', 120], ['1d', 1440], ['0m', null], ['abc', null], ['', null], ['5', null]]) {
    assert.equal(minutesFrom(text), want, text);
  }
});

test('it keeps its promises on odd input', () => {
  assert.equal(q('!!! ??? ...').title, '!!! ??? ...');
  assert.equal(q('#').project, null);
  assert.equal(q('@').labels.length, 0);
  assert.equal(q('/').section, null);
  assert.equal(q('every').recurrence, null);
  assert.equal(q(null).title, '');
  assert.equal(q('x'.repeat(5000)).title.length, 5000);
});
