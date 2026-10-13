// Pure helpers for homework: validation, Google Calendar event payloads and the .ics feed.
import { parseDate } from './ics.js';

export const REMIND_OPTIONS = [0, 30, 60, 180, 1440, 2880, 10080];
const TIMED_EVENT_MINUTES = 30; // a timed homework shows as a 30-min block ending at the deadline

export function isValidTimeZone(tz) {
  try { new Intl.DateTimeFormat('en-US', { timeZone: tz }); return true; } catch { return false; }
}

// Deadline instant: the due time, or the end of the due day for all-day homework.
export function dueInstant(dueDate, dueTime, tz) {
  const compact = dueDate.replace(/-/g, '');
  return dueTime
    ? parseDate(`${compact}T${dueTime.replace(':', '')}00`, { TZID: tz })
    : parseDate(`${compact}T235959`, { TZID: tz }) + 999;
}

function nextDay(date) {
  const d = new Date(date + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

// Google all-day reminders count back from 00:00 of the day, which is a useless time to be
// notified. For all-day homework a reminder "N days before" fires at 18:00 N days before,
// and "at deadline" fires at the start of the due day.
export function reminderMinutes(hw) {
  if (hw.remind_minutes == null) return null;
  if (hw.due_time) return hw.remind_minutes;
  if (hw.remind_minutes === 0) return 0;
  return Math.ceil(hw.remind_minutes / 1440) * 1440 - 18 * 60;
}

export function eventTitle(hw, courseName) {
  return `${hw.done ? '✅' : '📚'} ${hw.title}${courseName ? ` · ${courseName}` : ''}`;
}

export function buildGcalEvent(hw, courseName, appUrl) {
  const minutes = reminderMinutes(hw);
  const event = {
    summary: eventTitle(hw, courseName),
    description: [hw.notes, courseName && `Course: ${courseName}`, appUrl && `Manage: ${appUrl}/#homework`]
      .filter(Boolean).join('\n\n'),
    transparency: 'transparent',
    reminders: { useDefault: false, overrides: !hw.done && minutes != null ? [{ method: 'popup', minutes }] : [] },
    extendedProperties: { private: { attendance80HomeworkId: String(hw.id) } },
  };
  if (hw.due_time) {
    event.start = { dateTime: new Date(hw.due_at - TIMED_EVENT_MINUTES * 60000).toISOString(), timeZone: hw.tz };
    event.end = { dateTime: new Date(hw.due_at).toISOString(), timeZone: hw.tz };
  } else {
    event.start = { date: hw.due_date };
    event.end = { date: nextDay(hw.due_date) };
  }
  return event;
}

// ---------------------------------------------------------------- .ics feed

const icsEscape = (s) => String(s).replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n');
const icsUtc = (ms) => new Date(ms).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');

// Fold lines at 75 octets (RFC 5545 §3.1) without splitting multi-byte characters.
function fold(line) {
  const enc = new TextEncoder();
  if (enc.encode(line).length <= 75) return line;
  const out = [];
  let cur = '', size = 0;
  for (const ch of line) {
    const n = enc.encode(ch).length;
    if (size + n > (out.length ? 74 : 75)) { out.push(cur); cur = ''; size = 0; }
    cur += ch; size += n;
  }
  out.push(cur);
  return out.join('\r\n ');
}

export function buildFeed(homework, courseNames, { now = Date.now(), host = 'attendance80' } = {}) {
  const lines = [
    'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Attendance80//Homework//EN', 'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH', 'X-WR-CALNAME:Homework', 'REFRESH-INTERVAL;VALUE=DURATION:PT1H', 'X-PUBLISHED-TTL:PT1H',
  ];
  for (const hw of homework) {
    const courseName = courseNames[hw.course_id];
    lines.push('BEGIN:VEVENT', `UID:homework-${hw.id}@${host}`, `DTSTAMP:${icsUtc(hw.updated_at || now)}`);
    if (hw.due_time) {
      lines.push(`DTSTART:${icsUtc(hw.due_at - TIMED_EVENT_MINUTES * 60000)}`, `DTEND:${icsUtc(hw.due_at)}`);
    } else {
      lines.push(`DTSTART;VALUE=DATE:${hw.due_date.replace(/-/g, '')}`, `DTEND;VALUE=DATE:${nextDay(hw.due_date).replace(/-/g, '')}`);
    }
    lines.push(`SUMMARY:${icsEscape(eventTitle(hw, courseName))}`, 'TRANSP:TRANSPARENT');
    const desc = [hw.notes, courseName && `Course: ${courseName}`].filter(Boolean).join('\n\n');
    if (desc) lines.push(`DESCRIPTION:${icsEscape(desc)}`);
    const minutes = reminderMinutes(hw);
    if (!hw.done && minutes != null) {
      lines.push('BEGIN:VALARM', 'ACTION:DISPLAY', `DESCRIPTION:${icsEscape(hw.title)}`, `TRIGGER:-PT${minutes}M`, 'END:VALARM');
    }
    lines.push('END:VEVENT');
  }
  lines.push('END:VCALENDAR');
  return lines.map(fold).join('\r\n') + '\r\n';
}
