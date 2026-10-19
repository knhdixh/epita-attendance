// Attendance maths shared by the browser and the Worker. Pure functions, no DOM.

export const TAGS = {
  normal: { label: 'Normal', counts: true, mustAttend: false },
  mandatory: { label: 'Mandatory', counts: true, mustAttend: true },
  exam: { label: 'Exam', counts: true, mustAttend: true },
  optional: { label: 'Optional', counts: false, mustAttend: false },
};

export const STATUSES = ['present', 'absent', 'excused'];

// "GR3 - French for Fall 26 T1" -> { group: "GR3", family: "French for Fall 26 T1" }
export function splitGroup(name) {
  const m = /^(GR?\s?\d+)\s*[-–—]\s*(.+)$/i.exec(name.trim());
  return m ? { group: m[1].toUpperCase().replace(/\s/g, ''), family: m[2].trim() } : null;
}

export const effectiveTag = (ev, course) => ev.tag || course?.tag || 'normal';

const weightOf = (ev, unit) => (unit === 'hours' ? (ev.end_at - ev.start_at) / 3600000 : 1);

// Stats for a set of events. Unmarked future sessions are assumed attended;
// sessions marked "absent" in the future count as planned skips.
// Excused absences and optional sessions are left out of the total.
export function computeStats(events, coursesById, { threshold = 0.8, unit = 'sessions', now = Date.now() } = {}) {
  const s = {
    total: 0, present: 0, absent: 0, plannedAbsent: 0, excused: 0, optional: 0,
    pastMarked: 0, pastPresent: 0, unmarkedPast: 0, upcoming: 0, sessions: 0,
    missedMustAttend: [], maxWeight: 0,
  };
  for (const ev of events) {
    const course = coursesById[ev.course_id];
    const tag = effectiveTag(ev, course);
    const w = weightOf(ev, unit);
    const past = ev.start_at <= now;
    s.sessions++;
    if (!TAGS[tag]?.counts) { s.optional += w; continue; }
    if (ev.status === 'excused') { s.excused += w; continue; }
    s.total += w;
    s.maxWeight = Math.max(s.maxWeight, w);
    if (ev.status === 'absent') {
      s.absent += w;
      if (!past) s.plannedAbsent += w;
      if (TAGS[tag].mustAttend) s.missedMustAttend.push(ev);
    }
    if (past) {
      if (ev.status === 'present') { s.pastPresent += w; s.pastMarked += w; }
      else if (ev.status === 'absent') s.pastMarked += w;
      else s.unmarkedPast++;
    } else {
      s.upcoming++;
    }
  }
  s.present = s.total - s.absent;
  s.allowedAbsence = s.total * (1 - threshold);
  s.remaining = s.allowedAbsence - s.absent;
  // Whole skippable sessions left (or hours, rounded down to the half hour).
  s.remainingDisplay = unit === 'hours'
    ? Math.floor((s.remaining + 1e-9) * 2) / 2
    : Math.floor(s.remaining + 1e-9);
  s.projectedRate = s.total ? (s.total - s.absent) / s.total : 1;
  s.currentRate = s.pastMarked ? s.pastPresent / s.pastMarked : null;
  s.level = s.remaining < -1e-9 ? 'fail'
    : s.remaining < Math.max(1, s.maxWeight) - 1e-9 || s.missedMustAttend.length ? 'risk'
    : 'ok';
  return s;
}

export function statsByCourse(courses, events, opts) {
  const byId = Object.fromEntries(courses.map((c) => [c.id, c]));
  const groups = new Map(courses.map((c) => [c.id, []]));
  for (const ev of events) groups.get(ev.course_id)?.push(ev);
  const perCourse = courses.map((c) => ({ course: c, stats: computeStats(groups.get(c.id), byId, opts) }));
  const tracked = new Set(courses.filter((c) => c.tracked).map((c) => c.id));
  const overall = computeStats(events.filter((e) => tracked.has(e.course_id)), byId, opts);
  return { perCourse, overall };
}
