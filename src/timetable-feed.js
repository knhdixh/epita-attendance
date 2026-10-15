import { icsEscape, icsUtc, fold } from './homework.js';

// The user's timetable as a subscribable calendar: every session of a tracked course (so other groups and
// holidays stay out), exactly as Zeus describes it. It's built from the database on every request, so it
// follows re-imports and the Zeus refresh.
export function buildTimetableFeed(events, courses, { now = Date.now(), host = 'attendance80' } = {}) {
  const tracked = new Set(courses.filter((c) => c.tracked).map((c) => c.id));
  const lines = [
    'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Attendance80//Timetable//EN', 'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH', 'X-WR-CALNAME:Timetable', 'REFRESH-INTERVAL;VALUE=DURATION:PT1H', 'X-PUBLISHED-TTL:PT1H',
  ];
  for (const ev of events) {
    if (!tracked.has(ev.course_id)) continue;
    lines.push('BEGIN:VEVENT', `UID:${icsEscape(ev.uid)}-timetable@${host}`, `DTSTAMP:${icsUtc(now)}`,
      `DTSTART:${icsUtc(ev.start_at)}`, `DTEND:${icsUtc(ev.end_at)}`, `SUMMARY:${icsEscape(ev.summary)}`);
    if (ev.location) lines.push(`LOCATION:${icsEscape(ev.location)}`);
    if (ev.description) lines.push(`DESCRIPTION:${icsEscape(ev.description)}`);
    lines.push('END:VEVENT');
  }
  lines.push('END:VCALENDAR');
  return lines.map(fold).join('\r\n') + '\r\n';
}
