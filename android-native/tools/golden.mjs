#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

import * as stats from '../../public/stats.js';
import * as breaks from '../../public/breaks.js';
import * as plan from '../../public/plan.js';
import * as focus from '../../public/focus.js';

const ROOT_DIR = process.cwd();
const FIXTURES_DIR = path.join(ROOT_DIR, 'android-native/tools/fixtures');
const GOLDEN_DIR = path.join(ROOT_DIR, 'android-native/app/src/test/resources/golden');

if (!fs.existsSync(GOLDEN_DIR)) {
  fs.mkdirSync(GOLDEN_DIR, { recursive: true });
}

// -------------------------------------------------------------
// Load fixtures
// -------------------------------------------------------------
const semesterFixture = JSON.parse(fs.readFileSync(path.join(FIXTURES_DIR, 'semester.json'), 'utf8'));
const focusFixture = JSON.parse(fs.readFileSync(path.join(FIXTURES_DIR, 'focus.json'), 'utf8'));
const groupsFixture = JSON.parse(fs.readFileSync(path.join(FIXTURES_DIR, 'groups.json'), 'utf8'));

// -------------------------------------------------------------
// Load app.js scope functions via VM sandbox
// -------------------------------------------------------------
const appCode = fs.readFileSync(path.join(ROOT_DIR, 'public/app.js'), 'utf8');
const famSlice = appCode.slice(appCode.indexOf('function groupFamilies()'), appCode.indexOf('async function boot()'));
const scopeSlice = appCode.slice(appCode.indexOf('function applyScope()'), appCode.indexOf('async function renderLogin()'));

function createScopeSandbox(initialData = { courses: [], calendars: [], allEvents: [], events: [] }, initialScope = []) {
  const sandbox = {
    splitGroup: stats.splitGroup,
    state: {
      data: { ...initialData },
      scope: [...initialScope]
    },
    localStorage: {
      getItem: () => null,
      setItem: () => {}
    },
    render: () => {}
  };
  vm.createContext(sandbox);
  vm.runInContext(`${famSlice}\n${scopeSlice}`, sandbox);
  return sandbox;
}

// -------------------------------------------------------------
// 1. STATS GOLDEN
// -------------------------------------------------------------
console.log('Generating stats_golden.json...');
const splitGroupResults = groupsFixture.testStrings.map((str) => ({
  input: str,
  output: stats.splitGroup(str)
}));

const coursesById = Object.fromEntries(semesterFixture.courses.map((c) => [c.id, c]));
const statsRefNow = semesterFixture.timePoints.find((tp) => tp.name === 'normal_day_paris').now;

const computeStatsCases = [];
for (const threshold of [0.8, 0.7]) {
  for (const unit of ['sessions', 'hours']) {
    for (const now of [statsRefNow, semesterFixture.events[0].start_at, semesterFixture.events.at(-1).end_at]) {
      // All events
      computeStatsCases.push({
        description: `all_events_thresh_${threshold}_unit_${unit}_now_${now}`,
        threshold,
        unit,
        now,
        result: stats.computeStats(semesterFixture.events, coursesById, { threshold, unit, now })
      });
      // Single course: Algo (id 1)
      const algoEvents = semesterFixture.events.filter((e) => e.course_id === 1);
      computeStatsCases.push({
        description: `algo_events_thresh_${threshold}_unit_${unit}_now_${now}`,
        threshold,
        unit,
        now,
        result: stats.computeStats(algoEvents, coursesById, { threshold, unit, now })
      });
      // Sisu course: attendance 0 (id 6)
      const sisuEvents = semesterFixture.events.filter((e) => e.course_id === 6);
      computeStatsCases.push({
        description: `sisu_events_thresh_${threshold}_unit_${unit}_now_${now}`,
        threshold,
        unit,
        now,
        result: stats.computeStats(sisuEvents, coursesById, { threshold, unit, now })
      });
    }
  }
}

const statsByCourseCases = [];
for (const threshold of [0.8, 0.7]) {
  for (const unit of ['sessions', 'hours']) {
    statsByCourseCases.push({
      threshold,
      unit,
      now: statsRefNow,
      result: stats.statsByCourse(semesterFixture.courses, semesterFixture.events, { threshold, unit, now: statsRefNow })
    });
  }
}

const statsGolden = {
  splitGroupResults,
  computeStatsCases,
  statsByCourseCases
};
fs.writeFileSync(path.join(GOLDEN_DIR, 'stats_golden.json'), JSON.stringify(statsGolden, null, 2));

// -------------------------------------------------------------
// 2. BREAKS GOLDEN
// -------------------------------------------------------------
console.log('Generating breaks_golden.json...');
const testDates = ['2026-09-01', '2026-10-14', '2026-10-25', '2026-10-26', '2026-11-01', '2026-12-04', 'invalid-date', '2026-02-30'];
const dateHelperResults = {
  isDate: testDates.map((d) => ({ input: d, output: breaks.isDate(d) })),
  addDays: testDates.filter(breaks.isDate).flatMap((d) => [-7, -1, 0, 1, 3, 7, 14].map((n) => ({ date: d, n, output: breaks.addDays(d, n) }))),
  weekdayOf: testDates.filter(breaks.isDate).map((d) => ({ input: d, output: breaks.weekdayOf(d) })),
  dayDiff: [
    { a: '2026-10-12', b: '2026-10-12', output: breaks.dayDiff('2026-10-12', '2026-10-12') },
    { a: '2026-10-12', b: '2026-10-19', output: breaks.dayDiff('2026-10-12', '2026-10-19') },
    { a: '2026-10-25', b: '2026-11-01', output: breaks.dayDiff('2026-10-25', '2026-11-01') },
    { a: '2026-11-01', b: '2026-10-25', output: breaks.dayDiff('2026-11-01', '2026-10-25') }
  ],
  makeDayKey: semesterFixture.timePoints.map((tp) => ({
    tz: tp.tz,
    ms: tp.now,
    output: breaks.makeDayKey(tp.tz)(tp.now)
  }))
};

const breaksCases = [];
for (const tp of [
  semesterFixture.timePoints.find((x) => x.name === 'normal_day_paris'),
  semesterFixture.timePoints.find((x) => x.name === 'normal_day_helsinki'),
  semesterFixture.timePoints.find((x) => x.name === 'dst_fall_paris_day')
]) {
  for (const threshold of [0.8, 0.7]) {
    for (const unit of ['sessions', 'hours']) {
      const m = breaks.breakModel({
        courses: semesterFixture.courses,
        events: semesterFixture.events,
        threshold,
        unit,
        now: tp.now,
        tz: tp.tz
      });

      // Break simulations
      const simRanges = [
        ['2026-10-15', '2026-10-15'], // single day
        ['2026-10-16', '2026-10-18'], // weekend / no class
        ['2026-10-19', '2026-10-25'], // normal week
        ['2026-10-26', '2026-11-01'], // holiday week
        ['2026-11-09', '2026-11-15'], // week with exam (Algo on Nov 12)
        ['2026-10-19', '2026-11-01']  // two weeks
      ];
      const simulations = simRanges.map(([from, to]) => ({
        from,
        to,
        result: breaks.simulateBreak(m, from, to)
      }));

      // Find break windows
      const windowSearches = [
        { days: 3, from: '2026-10-15', to: '2026-12-04' },
        { days: 5, from: '2026-10-15', to: '2026-12-04' },
        { days: 9, from: '2026-10-15', to: '2026-12-04' },
        { days: 30, from: '2026-10-15', to: '2026-12-04' } // triggers longestSafe
      ].map((params) => ({
        params,
        result: breaks.findBreakWindows(m, params)
      }));

      // Longest safe
      const longest = breaks.longestSafe(m, '2026-10-15', '2026-12-04');

      // Week overview
      const weekOverview4 = breaks.weekOverview(m, 4);
      const weekOverview12 = breaks.weekOverview(m, 12);

      // Start day grid
      const grid3 = breaks.startDayGrid(m, 3);
      const grid7 = breaks.startDayGrid(m, 7);

      breaksCases.push({
        timePoint: tp.name,
        tz: tp.tz,
        now: tp.now,
        threshold,
        unit,
        modelSummary: {
          today: m.today,
          lastDay: m.lastDay,
          holidays: [...m.holidays].sort(),
          noSkipCourseIds: m.noSkip.map((c) => c.id)
        },
        simulations,
        windowSearches,
        longest,
        weekOverview4,
        weekOverview12,
        grid3,
        grid7
      });
    }
  }
}

const breaksGolden = {
  dateHelperResults,
  breaksCases
};
fs.writeFileSync(path.join(GOLDEN_DIR, 'breaks_golden.json'), JSON.stringify(breaksGolden, null, 2));

// -------------------------------------------------------------
// 3. PLAN GOLDEN
// -------------------------------------------------------------
console.log('Generating plan_golden.json...');
const localMidnightResults = [];
for (const tp of semesterFixture.timePoints) {
  const mid = plan.localMidnight(tp.now, tp.tz);
  localMidnightResults.push({
    name: tp.name,
    tz: tp.tz,
    now: tp.now,
    midnight: mid
  });
}

const atHourResults = [];
for (const tp of semesterFixture.timePoints) {
  const mid = plan.localMidnight(tp.now, tp.tz);
  atHourResults.push({
    name: tp.name,
    tz: tp.tz,
    midnight: mid,
    at8: plan.localMidnight(mid, tp.tz) /* internal atHour via buildPlan testing below */
  });
}

const effortOfResults = semesterFixture.homework.map((h) => ({
  id: h.id,
  title: h.title,
  effort: plan.effortOf(h)
}));

const buildPlanCases = [];
for (const tp of [
  semesterFixture.timePoints.find((x) => x.name === 'normal_day_paris'),
  semesterFixture.timePoints.find((x) => x.name === 'normal_day_helsinki'),
  semesterFixture.timePoints.find((x) => x.name === 'dst_spring_paris_night'),
  semesterFixture.timePoints.find((x) => x.name === 'dst_spring_paris_day'),
  semesterFixture.timePoints.find((x) => x.name === 'dst_fall_paris_night'),
  semesterFixture.timePoints.find((x) => x.name === 'dst_fall_paris_day')
]) {
  const p = plan.buildPlan({
    courses: semesterFixture.courses,
    events: semesterFixture.events,
    homework: semesterFixture.homework,
    now: tp.now,
    tz: tp.tz
  });
  buildPlanCases.push({
    name: tp.name,
    now: tp.now,
    tz: tp.tz,
    plan: p
  });
}

const planGolden = {
  localMidnightResults,
  effortOfResults,
  buildPlanCases
};
fs.writeFileSync(path.join(GOLDEN_DIR, 'plan_golden.json'), JSON.stringify(planGolden, null, 2));

// -------------------------------------------------------------
// 4. FOCUS TIMER GOLDEN
// -------------------------------------------------------------
console.log('Generating focus_timer_golden.json...');
const cleanSettingsResults = focusFixture.rawSettingsCases.map((c) => ({
  name: c.name,
  input: c.raw,
  output: focus.cleanSettings(c.raw)
}));

const cleanTagsResults = [
  ['#coding', 'Coding', 'CODING', '#java', ' kotlin '],
  ['a', 'b', 'c', 'd', 'e', 'f', 'g'],
  null,
  []
].map((tags) => ({
  input: tags,
  output: focus.cleanTags(tags)
}));

const cleanTasksResults = [
  ['Task 1', '  Task 1  ', 'TASK 1', 'Task 2'],
  Array.from({ length: 40 }, (_, i) => `Task ${i}`)
].map((tasks) => ({
  input: tasks,
  output: tasks ? focus.cleanSettings({ tasks }).tasks : []
}));

const defaultSettings = focus.DEFAULT_SETTINGS;
const timerOperations = [];

for (const tf of focusFixture.timers) {
  const t = tf.timer;
  const now = tf.now;

  const remNow = focus.remaining(t, now);
  const rem5m = focus.remaining(t, now + 5 * 60000);
  const len = focus.length(t);
  const progNow = focus.progress(t, now);
  const dueNow = focus.isDue(t, now);
  const nextLen = focus.nextLength(t, defaultSettings);

  const startRes = focus.start(t, defaultSettings, now, 'new-run-start');
  const pauseRes = focus.pause(t, now);
  const resumeRes = focus.resume(t, now + 5 * 60000);
  const adjustPlus5 = focus.adjust(t, defaultSettings, 5, now);
  const adjustMinus5 = focus.adjust(t, defaultSettings, -5, now);
  const setLength30 = focus.setLength(t, defaultSettings, 30);
  const finishNormal = focus.finish(t, defaultSettings, now, 'finish-run-1', { early: false });
  const finishEarly = focus.finish(t, defaultSettings, now, 'finish-run-2', { early: true });
  const stopRes = focus.stop(t, now);

  timerOperations.push({
    name: tf.name,
    timer: t,
    now,
    remainingNow: remNow,
    remaining5m: rem5m,
    length: len,
    progressNow: progNow,
    isDueNow: dueNow,
    nextLength: nextLen,
    start: startRes,
    pause: pauseRes,
    resume: resumeRes,
    adjustPlus5,
    adjustMinus5,
    setLength30,
    finishNormal,
    finishEarly,
    stop: stopRes
  });
}

const focusTimerGolden = {
  cleanSettingsResults,
  cleanTagsResults,
  cleanTasksResults,
  timerOperations
};
fs.writeFileSync(path.join(GOLDEN_DIR, 'focus_timer_golden.json'), JSON.stringify(focusTimerGolden, null, 2));

// -------------------------------------------------------------
// 5. FOCUS STATS GOLDEN
// -------------------------------------------------------------
console.log('Generating focus_stats_golden.json...');
const refNow = focusFixture.refNow;

const dailyTotalsObj = {};
for (const [k, v] of focus.dailyTotals(focusFixture.yearSessions)) {
  dailyTotalsObj[k] = v;
}

const minutesByHwObj = {};
for (const [k, v] of focus.minutesByHomework(focusFixture.yearSessions)) {
  minutesByHwObj[k] = v;
}

const focusStats7 = focus.focusStats(focusFixture.yearSessions, { now: refNow, range: 7 });
const focusStats30 = focus.focusStats(focusFixture.yearSessions, { now: refNow, range: 30 });
const focusStats365 = focus.focusStats(focusFixture.yearSessions, { now: refNow, range: 365 });

// Remove non-serializable 'day' function
delete focusStats7.day;
delete focusStats30.day;
delete focusStats365.day;

const focusSummaryParis = focus.focusSummary(focusFixture.yearSessions, { now: refNow, tz: 'Europe/Paris' });
const focusSummaryHelsinki = focus.focusSummary(focusFixture.yearSessions, { now: refNow, tz: 'Europe/Helsinki' });

const focusStatsGolden = {
  dailyTotals: dailyTotalsObj,
  minutesByHomework: minutesByHwObj,
  focusStats7,
  focusStats30,
  focusStats365,
  focusSummaryParis,
  focusSummaryHelsinki
};
fs.writeFileSync(path.join(GOLDEN_DIR, 'focus_stats_golden.json'), JSON.stringify(focusStatsGolden, null, 2));

// -------------------------------------------------------------
// 6. GROUPS GOLDEN
// -------------------------------------------------------------
console.log('Generating groups_golden.json...');
const sandbox = createScopeSandbox(
  {
    courses: groupsFixture.courses,
    calendars: groupsFixture.calendars,
    allEvents: semesterFixture.events,
    events: semesterFixture.events
  },
  []
);

const groupFamiliesResult = sandbox.groupFamilies();

const applyScopeResults = groupsFixture.scopeCases.map((sc) => {
  const sb = createScopeSandbox(
    {
      courses: semesterFixture.courses,
      calendars: semesterFixture.calendars,
      allEvents: semesterFixture.events,
      events: semesterFixture.events
    },
    sc.scope
  );
  sb.applyScope();
  return {
    name: sc.name,
    inputScope: sc.scope,
    effectiveScope: sb.state.scope,
    eventCount: sb.state.data.events.length,
    eventIds: sb.state.data.events.map((e) => e.id)
  };
});

const courseCalendarsResult = {};
sandbox.applyScope();
for (const [cid, set] of sandbox.state.data.courseCals) {
  courseCalendarsResult[cid] = [...set].sort((a, b) => a - b);
}

const groupLabelResults = groupsFixture.labelCases.map((lc) => ({
  name: lc.name,
  inputIds: lc.ids,
  label: sandbox.groupLabel(lc.ids)
}));

const scopeNoteResults = groupsFixture.scopeCases.map((sc) => {
  const sb = createScopeSandbox(
    {
      courses: semesterFixture.courses,
      calendars: semesterFixture.calendars,
      allEvents: semesterFixture.events,
      events: semesterFixture.events
    },
    sc.scope
  );
  sb.applyScope();
  return {
    name: sc.name,
    scope: sb.state.scope,
    note: sb.scopeNote()
  };
});

const toggleScopeResults = groupsFixture.toggleSequences.map((seq) => {
  const sb = createScopeSandbox(
    {
      courses: semesterFixture.courses,
      calendars: semesterFixture.calendars,
      allEvents: semesterFixture.events,
      events: semesterFixture.events
    },
    []
  );
  const steps = [];
  for (const val of seq) {
    sb.toggleScope(val);
    steps.push({
      action: val,
      resultingScope: [...sb.state.scope]
    });
  }
  return {
    sequence: seq,
    steps
  };
});

const groupsGolden = {
  groupFamiliesResult,
  applyScopeResults,
  courseCalendarsResult,
  groupLabelResults,
  scopeNoteResults,
  toggleScopeResults
};
fs.writeFileSync(path.join(GOLDEN_DIR, 'groups_golden.json'), JSON.stringify(groupsGolden, null, 2));

console.log('All golden files generated successfully!');
