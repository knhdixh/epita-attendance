#!/usr/bin/env node
// Writes android-native/tools/fixtures/tasklang.json: the inputs of the task-language tests (recurrence, quick add,
// filters) as data, so the Kotlin ports run the same cases. Re-run it only when those test tables change.
import fs from 'node:fs';
const read = (f) => fs.readFileSync(f, 'utf8');
// The literal after `const NAME =` up to the closing `];` / `};` at the start of a line.
const grab = (src, name) => {
  const start = src.indexOf(`const ${name} = `);
  if (start < 0) throw new Error(`no ${name}`);
  const from = src.indexOf('=', start) + 1;
  const end = src.slice(from).search(/[\]}];\n/) + from + 1;
  const lines = src.slice(from).split('\n');
  const literal = lines[0].trimEnd().endsWith(';') ? lines[0] : src.slice(from, end);
  return new Function(`const TODAY = '2026-11-03'; return (${literal.replace(/;\s*$/, '')});`)();
};
const rec = read('test/recurrence.test.js'), qa = read('test/quickadd.test.js'), fl = read('test/filters.test.js');
const errorsRec = new Function(`return ${rec.slice(rec.indexOf("for (const text of [") + 'for (const text of '.length, rec.indexOf("]) {", rec.indexOf("for (const text of [")) + 1)}`)();
const filterCtx = new Function(`const TODAY='2026-11-03'; return ${fl.slice(fl.indexOf('const ctx = ') + 12, fl.indexOf('\n};', fl.indexOf('const ctx = ')) + 2)}`)();
const out = {
  today: '2026-11-03',
  recurrence: {
    steps: grab(rec, 'STEPS'),
    formats: grab(rec, 'FORMATS').slice(0, -1).map(([t]) => t),
    errors: errorsRec,
    days: grab(rec, 'DAYS').map(([t]) => t),
    // [text, due]: "every month" and friends keep the day they started on only where they would drift
    drift: [['every month', '2026-01-31'], ['every month', '2026-01-15'], ['every year', '2028-02-29'], ['every year', '2026-03-01'], ['every 2 weeks', '2026-11-03']],
    // series run for 400 steps from 31 Jan 2026
    series: ['every day', 'every weekday', 'every 3rd friday', 'every month on the 31st', 'every other week', 'every year', 'every! 3 days', 'every last day', 'every 2 months on the 3rd friday'],
  },
  quickadd: {
    inputs: grab(qa, 'CASES').map(([t]) => t).concat([
      '!!! ??? ...', '#', '@', '/', 'every', 'x'.repeat(60), 'Buy milk in a month', 'x next month', 'x in 1 year', 'x tomorrow', 'x in 2 days', 'x in 1 week',
      'x mon', 'x tue', 'x wed', 'x thu', 'x fri', 'x sat', 'x sun', 'Tom', 'tom', 'Call Tom tom', 'Pay rent every month on the 31st',
      'Éveil #Écoles /Étape 1 @café p2', 'Gym every day until dec 2026', 'Task 9.30 pm', 'Task at 7',
    ]),
    todays: ['2026-01-31', '2028-01-31', '2026-12-15', '2028-02-29', '2026-12-31', '2026-03-28', '2026-10-25', '2026-11-02', '2026-11-08'],
    todayInputs: ['x in a month', 'x in 1 month', 'x next month', 'x in 1 year', 'x tomorrow', 'x in 2 days', 'x in 1 week', 'x mon', 'x fri', 'x sun', 'x next week', 'x weekend', 'x every day', 'x every month'],
    names: grab(qa, 'NAMES'),
    named: grab(qa, 'NAMED').map(([t]) => t),
    phrases: ['fri', 'jan 27', 'on friday', '27 jan', 'in 3 days', 'nonsense', 'next week', 'tom', 'Tom', '2026-02-30', 'by 3 nov', 'due 2 nov', 'weekend', 'tonight'],
    phraseToday: ['2026-11-03', '2026-01-10', '2026-01-27', '2026-01-28'],
    minutes: ['45m', '2h', '1h30m', '1.5h', '1,5h', '90 min', '1 hour 30 min', '2 hours', '1d', '0m', 'abc', '', '5', '1h 30', '100000m'],
  },
  filters: {
    ctx: filterCtx,
    queries: grab(fl, 'CASES').map(([q]) => q).concat(['today, overdue, p1', 'today,overdue', '##Reports & p4', 'search: ""', '#Work | #Home | @urgent']),
    errors: new Function(`return ${fl.slice(fl.indexOf('const ERRORS = ') + 15, fl.indexOf('];', fl.indexOf('const ERRORS = ')) + 1)}`)(),
    todays: ['2026-11-03', '2026-12-31', '2026-03-28', '2026-02-28', '2026-11-10'],
    todayQueries: ['today', 'tomorrow', 'overdue', 'next 7 days', 'next 1 day', 'due before: fri', 'no date', 'date: jan 27'],
  },
};
fs.writeFileSync('android-native/tools/fixtures/tasklang.json', JSON.stringify(out, null, 1) + '\n');
console.log(Object.fromEntries(Object.entries(out).map(([k, v]) => [k, Object.fromEntries(Object.entries(v).map(([a, b]) => [a, Array.isArray(b) ? b.length : typeof b]))])));
