import { test } from 'node:test';
import assert from 'node:assert/strict';
import { breakModel, simulateBreak, findBreakWindows, weekOverview, longestSafe, addDays } from '../public/breaks.js';

// Tue 2026-10-13 08:00 Paris. Weekday classes at 10:00 Paris (08:00 UTC) for 8 weeks.
const NOW = Date.UTC(2026, 9, 13, 6);
const at = (date) => Date.parse(date + 'T08:00:00Z');
function calendar({ examOn } = {}) {
  const courses = [
    { id: 1, name: 'Algo', tracked: 1, tag: 'normal' },   // Mon + Thu
    { id: 2, name: 'Web', tracked: 1, tag: 'normal' },    // Wed
    { id: 3, name: 'Vacances', tracked: 0, tag: 'normal' },
  ];
  const events = [];
  let id = 1;
  for (let d = '2026-10-12'; d <= '2026-12-06'; d = addDays(d, 1)) {
    const wd = new Date(d + 'T00:00:00Z').getUTCDay();
    if (d >= '2026-10-26' && d <= '2026-11-01') continue;            // school holiday week
    if (wd === 1 || wd === 4) events.push({ id: id++, course_id: 1, start_at: at(d), end_at: at(d) + 2 * 3600000, status: null, tag: d === examOn ? 'exam' : null });
    if (wd === 3) events.push({ id: id++, course_id: 2, start_at: at(d), end_at: at(d) + 2 * 3600000, status: null, tag: null });
  }
  // Holiday as one all-day event spanning the week.
  events.push({ id: id++, course_id: 3, start_at: Date.parse('2026-10-25T22:00:00Z'), end_at: Date.parse('2026-11-01T23:00:00Z'), status: null, tag: null });
  return { courses, events };
}
const model = (cal) => breakModel({ ...cal, threshold: 0.8, unit: 'sessions', now: NOW, tz: 'Europe/Paris' });

test('simulateBreak: a week of Algo/Web skipped, counts and verdicts', () => {
  const m = model(calendar());
  // Algo: 14 sessions (7 weeks x 2), 2.8 allowed. Web: 7 sessions, 1.4 allowed.
  const sim = simulateBreak(m, '2026-11-02', '2026-11-08');
  assert.equal(sim.sessions.length, 3);
  const algo = sim.courses.find((c) => c.course.name === 'Algo');
  const web = sim.courses.find((c) => c.course.name === 'Web');
  assert.equal(algo.skipped, 2);
  assert.equal(algo.level, 'risk');      // 0.8 left
  assert.equal(web.level, 'risk');       // 0.4 left
  assert.equal(sim.level, 'risk');
  const two = simulateBreak(m, '2026-11-02', '2026-11-15');
  assert.equal(two.level, 'fail');       // 4 Algo, 2 Web
});

test('simulateBreak: exam inside the break is a conflict', () => {
  const m = model(calendar({ examOn: '2026-11-05' }));
  const sim = simulateBreak(m, '2026-11-05', '2026-11-05');
  assert.equal(sim.conflicts.length, 1);
  assert.equal(sim.level, 'fail');
});

test('findBreakWindows prefers the school-holiday week and avoids exams', () => {
  const m = model(calendar({ examOn: '2026-11-19' }));
  const { windows } = findBreakWindows(m, { days: 9, from: '2026-10-14', to: '2026-12-06' });
  assert.ok(windows.length > 0);
  // 9 days around the holiday week cost at most one session (e.g. Sat 24 Oct – Sun 1 Nov: 0 sessions).
  const best = [...windows].sort((a, b) => a.sessions - b.sessions)[0];
  assert.equal(best.sessions, 0);
  assert.ok(best.holidayDays >= 7);
  for (const w of windows) assert.ok(!(w.from <= '2026-11-19' && w.to >= '2026-11-19'), 'no window over the exam');
  // Results never overlap.
  for (const a of windows) for (const b of windows) if (a !== b) assert.ok(a.to < b.from || a.from > b.to);
});

test('no window long enough: longest safe break is reported', () => {
  const m = model(calendar());
  const r = findBreakWindows(m, { days: 30, from: '2026-11-02', to: '2026-12-06' });
  assert.equal(r.windows.length, 0);
  assert.ok(r.longest.days >= 7 && r.longest.days < 30);
  assert.deepEqual(longestSafe(m, '2026-11-02', '2026-12-06'), r.longest);
});

test('weekOverview: holiday week is free, normal weeks risky but possible', () => {
  const weeks = weekOverview(model(calendar()), 4);
  assert.equal(weeks[0].from, '2026-10-12');
  assert.equal(weeks[2].from, '2026-10-26');
  assert.equal(weeks[2].level, 'free');
  assert.equal(weeks[2].holidayDays, 7);
  assert.equal(weeks[1].level, 'risk');
});
