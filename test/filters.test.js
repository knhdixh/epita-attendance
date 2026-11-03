import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseFilter, runFilter } from '../public/filters.js';

const TODAY = '2026-11-03'; // a Tuesday

const ctx = {
  today: TODAY,
  projects: [
    { id: 1, name: 'Work', parent_id: null }, { id: 2, name: 'Reports', parent_id: 1 }, { id: 3, name: 'Home', parent_id: null },
    { id: 4, name: 'Archived', parent_id: null, archived: true },
  ],
  sections: [{ id: 10, name: 'Backlog', project_id: 1 }, { id: 11, name: 'Now', project_id: 1 }, { id: 12, name: 'Chores', project_id: 3 }],
  labels: [{ id: 1, name: 'urgent' }, { id: 2, name: 'waiting' }],
  courses: [{ id: 1, name: 'Algorithms' }, { id: 2, name: 'Databases' }],
  tasks: [
    { id: 1, title: 'Write report', notes: '', project_id: 2, section_id: null, parent_id: null, due_date: '2026-11-03', priority: 4, labels: [1], course_id: null, recurrence: null },
    { id: 2, title: 'Email boss', notes: '', project_id: 1, section_id: 11, parent_id: null, due_date: '2026-11-02', priority: 3, labels: [2], course_id: null, recurrence: null },
    { id: 3, title: 'Buy milk', notes: '', project_id: 3, section_id: 12, parent_id: null, due_date: '2026-11-04', priority: 1, labels: [], course_id: null, recurrence: 'every week' },
    { id: 4, title: 'Algo homework', notes: '', project_id: null, section_id: null, parent_id: null, due_date: '2026-11-05', priority: 2, labels: [], course_id: 1, recurrence: null },
    { id: 5, title: 'Inbox thing', notes: '', project_id: null, section_id: null, parent_id: null, due_date: null, priority: 1, labels: [], course_id: null, recurrence: null },
    { id: 6, title: 'Subtask', notes: '', project_id: 2, section_id: null, parent_id: 1, due_date: '2026-11-03', priority: 1, labels: [], course_id: null, recurrence: null },
    { id: 7, title: 'DB exam prep', notes: 'Chapter 5', project_id: null, section_id: null, parent_id: null, due_date: '2026-11-10', priority: 4, labels: [1, 2], course_id: 2, recurrence: null },
    { id: 8, title: 'Old done', notes: '', project_id: null, section_id: null, parent_id: null, due_date: '2026-11-03', priority: 4, labels: [], course_id: null, recurrence: null, done: 1 },
    { id: 9, title: 'In archived', notes: '', project_id: 4, section_id: null, parent_id: null, due_date: '2026-11-03', priority: 1, labels: [], course_id: null, recurrence: null },
    { id: 10, title: 'Far', notes: '', project_id: 3, section_id: null, parent_id: null, due_date: '2026-12-25', priority: 1, labels: [], course_id: null, recurrence: null },
    { id: 11, title: 'Plan trip', notes: '', project_id: 1, section_id: 10, parent_id: null, due_date: '2026-11-10', priority: 1, labels: [], course_id: null, recurrence: null },
  ],
};
const ids = (query, c = ctx) => {
  const r = runFilter(query, c);
  assert.ok(r.ok, `${query}: ${r.error}`);
  return r.tasks.map((t) => t.id);
};

// Results come out by date (none last), then time, then urgency, then place in the list.
const CASES = [
  ['today', [1, 6]],
  ['tomorrow', [3]],
  ['yesterday', [2]],
  ['overdue', [2]],
  ['over due', [2]],
  ['today | overdue', [2, 1, 6]],
  ['no date', [5]],
  ['no due date', [5]],
  ['date: 2026-11-05', [4]],
  ['date: 5 nov', [4]],
  ['date: today', [1, 6]],
  ['date: fri', []],
  ['due before: 5 nov', [2, 1, 6, 3]],
  ['before: 5 nov', [2, 1, 6, 3]],
  ['due after: 5 nov', [7, 11, 10]],
  ['due after:5 nov', [7, 11, 10]],
  ['next 7 days', [1, 6, 3, 4, 7, 11]],
  ['next 1 day', [1, 6, 3]],
  ['next 0 days', [1, 6]],
  ['5 nov', [4]],
  ['jan 27', []],
  ['p1', [1, 7]],
  ['p2', [2]],
  ['P3', [4]],
  ['p4', [6, 3, 11, 10, 5]],
  ['no priority', [6, 3, 11, 10, 5]],
  ['#Work', [2, 11]],
  ['##Work', [2, 1, 6, 11]],
  ['#work', [2, 11]],
  ['#Reports', [1, 6]],
  ['##Reports', [1, 6]],
  ['#"Reports"', [1, 6]],
  ['#Inbox', [4, 7, 5]],
  ['#Nope', []],
  ['#Archived', []],
  ['/Now', [2]],
  ['/backlog', [11]],
  ['/Chores', [3]],
  ['/nope', []],
  ['@urgent', [1, 7]],
  ['@Waiting', [2, 7]],
  ['@urgent & @waiting', [7]],
  ['@urgent | @waiting', [2, 1, 7]],
  ['@nope', []],
  ['no labels', [6, 3, 4, 11, 10, 5]],
  ['subtask', [6]],
  ['!subtask & today', [1]],
  ['recurring', [3]],
  ['!recurring & tomorrow', []],
  ['search: report', [1]],
  ['search: REPORT', [1]],
  ['search: chapter', [7]],
  ['search: buy milk', [3]],
  ['search: boss & overdue', [2]],
  ['search: "buy milk"', [3]],
  ['course: algo', [4]],
  ['course: Databases', [7]],
  ['school', [4, 7]],
  ['!school & p1', [1]],
  ['overdue & !@waiting', []],
  ['(today | tomorrow) & p4', [6, 3]],
  ['today | tomorrow & p1', [1, 6]],            // & binds tighter than |
  ['(today | tomorrow) & p1', [1]],
  ['!today', [2, 3, 4, 7, 11, 10, 5]],
  ['!(today | overdue)', [3, 4, 7, 11, 10, 5]],
  ['!!today', [1, 6]],
  ['all', [2, 1, 6, 3, 4, 7, 11, 10, 5]],
  ['view all', [2, 1, 6, 3, 4, 7, 11, 10, 5]],
  ['today, overdue', [1, 6, 2]],
  ['#Big project', []],
  ['  today  &  p1  ', [1]],
  ['today,', [1, 6]],
  ['##Work & !/Now', [1, 6, 11]],
  ['#Work & @waiting', [2]],
  ['school & next 7 days', [4, 7]],
];
for (const [query, want] of CASES) test(`filter: ${query}`, () => assert.deepEqual(ids(query), want));

test('comma gives separate lists, in order', () => {
  const r = runFilter('today, overdue, p1', ctx);
  assert.deepEqual(r.lists.map((l) => l.map((t) => t.id)), [[1, 6], [2], [1, 7]]);
  assert.deepEqual(r.tasks.map((t) => t.id), [1, 6, 2, 7], 'each task once');
});

const ERRORS = ['', '   ', '(', 'today &', '& today', 'today )', 'today | | p1', 'banana', 'p5', 'date:', 'date: banana', 'next days', 'due before:',
  '@two words', '!', '((today)', 'today (p1)', 'search:', 'course:', ',', '#', '/'];
for (const query of ERRORS) {
  test(`filter error: "${query}"`, () => {
    const r = parseFilter(query, { today: TODAY });
    assert.equal(r.ok, false, JSON.stringify(r));
    assert.ok(r.error.length > 5);
    assert.ok(Number.isInteger(r.at) && r.at >= 0 && r.at <= query.length + 1, `at ${r.at}`);
  });
}

test('errors say where', () => {
  assert.equal(parseFilter('today & banana', { today: TODAY }).at, 8);
  assert.equal(parseFilter('(today', { today: TODAY }).at, 0);
});

test('a task with no date matches none of the date terms', () => {
  for (const q of ['today', 'overdue', 'due before: 2030-01-01', 'due after: 2000-01-01', 'next 365 days', 'date: 2026-11-03']) assert.ok(!ids(q).includes(5), q);
  assert.ok(ids('!today').includes(5));
});

test('month ends, leap days and daylight saving do not shift "today" and "tomorrow"', () => {
  const day = (d) => ({ id: 1, title: 'x', notes: '', due_date: d, priority: 1, labels: [] });
  for (const [today, tomorrow] of [['2026-01-31', '2026-02-01'], ['2026-02-28', '2026-03-01'], ['2028-02-28', '2028-02-29'], ['2028-02-29', '2028-03-01'],
    ['2026-03-28', '2026-03-29'], ['2026-10-24', '2026-10-25'], ['2026-12-31', '2027-01-01']]) {
    const c = { today, tasks: [day(tomorrow)] };
    assert.deepEqual(ids('tomorrow', c), [1], `${today}`);
    assert.deepEqual(ids('today', c), []);
    assert.deepEqual(ids('next 1 days', c), [1]);
    assert.deepEqual(ids('overdue', c), []);
    assert.deepEqual(ids('overdue', { today: tomorrow, tasks: [day(today)] }), [1]);
  }
});

test('subprojects follow ## to any depth, and projects with the same name all match', () => {
  const c = {
    today: TODAY,
    projects: [{ id: 1, name: 'A', parent_id: null }, { id: 2, name: 'B', parent_id: 1 }, { id: 3, name: 'C', parent_id: 2 }, { id: 4, name: 'a', parent_id: null }],
    tasks: [1, 2, 3, 4].map((p) => ({ id: p, title: 't', notes: '', project_id: p, due_date: null, priority: 1, labels: [] })),
  };
  assert.deepEqual(ids('##A', c), [1, 2, 3, 4]);
  assert.deepEqual(ids('#A', c), [1, 4]);
  assert.deepEqual(ids('##B', c), [2, 3]);
});

test('the same time sorts all-day first, then by urgency', () => {
  const t = (id, time, priority) => ({ id, title: 't', notes: '', due_date: TODAY, due_time: time, priority, labels: [] });
  const c = { today: TODAY, tasks: [t(1, '10:00', 4), t(2, null, 1), t(3, '09:00', 1), t(4, '10:00', 1), t(5, null, 3)] };
  assert.deepEqual(ids('today', c), [5, 2, 3, 1, 4]);
});

test('NO_DATE rows from the database count as no date', () => {
  const c = { today: TODAY, tasks: [{ id: 1, title: 'x', notes: '', due_date: '9999-12-31', priority: 1, labels: [] }] };
  assert.deepEqual(ids('no date', c), [1]);
  assert.deepEqual(ids('due after: 2026-01-01', c), []);
});
