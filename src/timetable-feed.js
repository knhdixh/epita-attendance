import { icsEscape, icsUtc, fold } from './homework.js';
import { effectiveTag, TAGS } from '../public/stats.js';

// The user's timetable as a subscribable calendar: sessions of tracked courses only (so other groups and
// holidays stay out), with the attendance mark and tag in the description. It's built from the database on
// every request, so it follows re-imports, the Zeus refresh and attendance changes.
const STATUS_LABELS = { present: 'Attended', absent: 'Absent', excused: 'Excused' };

export function buildTimetableFeed(events, courses, { now = Date.now(), host = 'attendance80' } = {}) {
  const byId = new Map(courses.map((c) => [c.id, c]));
  const lines = [
    'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Attendance80//Timetable//EN', 'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH', 'X-WR-CALNAME:Timetable', 'REFRESH-INTERVAL;VALUE=DURATION:PT1H', 'X-PUBLISHED-TTL:PT1H',
  ];
  for (const ev of events) {
    const course = byId.get(ev.course_id);
    if (!course?.tracked) continue;
    const tag = effectiveTag(ev, course);
    const planned = ev.status === 'absent' && ev.start_at > now;
    const title = `${planned ? 'Skipping: ' : ''}${tag === 'exam' ? 'Exam: ' : ''}${ev.summary}`;
    const info = [
      ev.status ? (planned ? 'Planned absence' : STATUS_LABELS[ev.status]) : null,
      tag !== 'normal' ? TAGS[tag]?.label : null,
    ].filter(Boolean).join(' · ');
    const desc = [info, ev.note, ev.description].filter(Boolean).join('\n\n');
    lines.push('BEGIN:VEVENT', `UID:${icsEscape(ev.uid)}-timetable@${host}`, `DTSTAMP:${icsUtc(now)}`,
      `DTSTART:${icsUtc(ev.start_at)}`, `DTEND:${icsUtc(ev.end_at)}`, `SUMMARY:${icsEscape(title)}`);
    if (ev.location) lines.push(`LOCATION:${icsEscape(ev.location)}`);
    if (desc) lines.push(`DESCRIPTION:${icsEscape(desc)}`);
    lines.push('END:VEVENT');
  }
  lines.push('END:VCALENDAR');
  return lines.map(fold).join('\r\n') + '\r\n';
}
