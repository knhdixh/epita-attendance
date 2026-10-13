import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseICS, parseDate } from '../src/ics.js';

test('parses UTC events, unfolds lines and unescapes text', () => {
  const ics = [
    'BEGIN:VCALENDAR', 'BEGIN:VEVENT', 'UID:a', 'SUMMARY:Algo & Data', ' Structures',
    'LOCATION:KB404\\, KB604 B', 'DTSTART:20261013T123000Z', 'DTEND:20261013T143000Z',
    'BEGIN:VALARM', 'DESCRIPTION:ignored', 'END:VALARM', 'END:VEVENT', 'END:VCALENDAR',
  ].join('\r\n');
  const { events } = parseICS(ics);
  assert.equal(events.length, 1);
  assert.equal(events[0].summary, 'Algo & DataStructures');
  assert.equal(events[0].location, 'KB404, KB604 B');
  assert.equal(events[0].start, Date.UTC(2026, 9, 13, 12, 30));
  assert.equal(events[0].end - events[0].start, 2 * 3600000);
  assert.equal(events[0].description, '');
});

test('TZID and floating times are converted with DST', () => {
  // 10:00 Paris in October (CEST, UTC+2) and in December (CET, UTC+1)
  assert.equal(parseDate('20261013T100000', { TZID: 'Europe/Paris' }), Date.UTC(2026, 9, 13, 8));
  assert.equal(parseDate('20261210T100000', {}), Date.UTC(2026, 11, 10, 9));
  assert.equal(parseDate('20261210T100000', { TZID: 'Bogus/Zone' }), Date.UTC(2026, 11, 10, 9));
});

test('skips cancelled flag through and counts RRULEs', () => {
  const ics = 'BEGIN:VEVENT\nUID:x\nSTATUS:CANCELLED\nRRULE:FREQ=WEEKLY\nDTSTART;VALUE=DATE:20261013\nEND:VEVENT\n';
  const { events, recurring } = parseICS(ics);
  assert.equal(events[0].cancelled, true);
  assert.equal(events[0].end - events[0].start, 86400000);
  assert.equal(recurring, 1);
});
