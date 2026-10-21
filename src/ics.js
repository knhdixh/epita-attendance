// Minimal iCalendar (RFC 5545) VEVENT parser. Handles line folding, escaping,
// UTC / floating / TZID / all-day dates, DTEND or DURATION. Recurring events (RRULE) are not expanded.

function unfold(text) {
  return text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').replace(/\n[ \t]/g, '');
}

function unescapeText(v) {
  return v.replace(/\\([\\;,nN])/g, (_, c) => (c === 'n' || c === 'N' ? '\n' : c));
}

function parseLine(line) {
  const colon = findValueColon(line);
  if (colon < 0) return null;
  const [name, ...rawParams] = line.slice(0, colon).split(';');
  const params = {};
  for (const p of rawParams) {
    const eq = p.indexOf('=');
    if (eq > 0) params[p.slice(0, eq).toUpperCase()] = p.slice(eq + 1).replace(/^"|"$/g, '');
  }
  return { name: name.toUpperCase(), params, value: line.slice(colon + 1) };
}

// First colon not inside a quoted parameter value.
function findValueColon(line) {
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    if (line[i] === '"') quoted = !quoted;
    else if (line[i] === ':' && !quoted) return i;
  }
  return -1;
}

// Offset (ms) of a time zone from UTC at a given instant.
function tzOffset(timeZone, utcMs) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone, hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).formatToParts(new Date(utcMs));
  const get = (t) => Number(parts.find((p) => p.type === t).value);
  const asUtc = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second'));
  return asUtc - utcMs;
}

export function parseDate(value, params = {}, defaultTz = 'Europe/Paris') {
  const m = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?$/.exec(value.trim());
  if (!m) return null;
  const [, y, mo, d, h = '0', mi = '0', s = '0', z] = m;
  const naive = Date.UTC(+y, +mo - 1, +d, +h, +mi, +s);
  if (z) return naive;
  let tz = params.TZID || defaultTz;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz });
  } catch {
    tz = defaultTz;
  }
  // Two passes to settle around DST transitions.
  let guess = naive - tzOffset(tz, naive);
  guess = naive - tzOffset(tz, guess);
  return guess;
}

// "PT1H45M", "P1D", "-PT15M" -> ms (null if not a duration).
export function parseDuration(value) {
  const m = /^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(value.trim());
  if (!m || value.trim().endsWith('P') || value.trim().endsWith('T')) return null;
  const [, sign, w = 0, d = 0, h = 0, mi = 0, s = 0] = m;
  const ms = ((((+w * 7 + +d) * 24 + +h) * 60 + +mi) * 60 + +s) * 1000;
  return sign === '-' ? -ms : ms;
}

// Drops the events that start before `since` (YYYYMMDD) before parsing: a calendar with years of history
// (Sisu sends every course since enrolment) would otherwise cost more CPU than a Worker invocation has.
// The comparison is on the date as written, so an event a day either side of the cutoff may stay.
function dropBefore(text, since) {
  const out = [];
  let i = text.indexOf('BEGIN:VEVENT');
  if (i < 0) return text;
  out.push(text.slice(0, i));
  while (i >= 0) {
    const next = text.indexOf('BEGIN:VEVENT', i + 12);
    const end = next < 0 ? text.length : next;
    const d = text.indexOf('\nDTSTART', i);
    const colon = d >= 0 && d < end ? text.indexOf(':', d) : -1;
    const date = colon >= 0 && colon < end ? text.slice(colon + 1, colon + 9) : null;
    if (!date || !/^\d{8}$/.test(date) || date >= since) out.push(text.slice(i, end));
    else if (next < 0) out.push('END:VCALENDAR\r\n');
    i = next;
  }
  return out.join('');
}

export function parseICS(text, { defaultTz = 'Europe/Paris', since = null } = {}) {
  if (since) text = dropBefore(text, since);
  const lines = unfold(text).split('\n');
  const events = [];
  let cur = null;
  let depth = 0; // nested components inside VEVENT (e.g. VALARM)
  let recurring = 0;

  for (const raw of lines) {
    const line = raw.trimEnd();
    if (!line) continue;
    if (line === 'BEGIN:VEVENT') { cur = {}; depth = 0; continue; }
    if (!cur) continue;
    if (line.startsWith('BEGIN:')) { depth++; continue; }
    if (line.startsWith('END:') && depth > 0) { depth--; continue; }
    if (line === 'END:VEVENT') {
      if (cur.rrule) recurring++;
      if (cur.uid && cur.start != null) {
        if (cur.end == null && cur.duration != null) cur.end = cur.start + cur.duration;
        if (cur.end == null) cur.end = cur.start + (cur.allDay ? 86400000 : 3600000);
        events.push({
          uid: cur.uid,
          summary: (cur.summary || '(untitled)').replace(/\s+/g, ' ').trim(),
          location: cur.location || '',
          description: cur.description || '',
          start: cur.start,
          end: cur.end,
          allDay: Boolean(cur.allDay),
          cancelled: cur.status === 'CANCELLED',
        });
      }
      cur = null;
      continue;
    }
    if (depth > 0) continue;
    const prop = parseLine(line);
    if (!prop) continue;
    switch (prop.name) {
      case 'UID': cur.uid = prop.value.trim(); break;
      case 'SUMMARY': cur.summary = unescapeText(prop.value); break;
      case 'LOCATION': cur.location = unescapeText(prop.value); break;
      case 'DESCRIPTION': cur.description = unescapeText(prop.value); break;
      case 'STATUS': cur.status = prop.value.trim().toUpperCase(); break;
      case 'RRULE': cur.rrule = prop.value; break;
      case 'DTSTART':
        cur.start = parseDate(prop.value, prop.params, defaultTz);
        cur.allDay = prop.params.VALUE === 'DATE' || /^\d{8}$/.test(prop.value.trim());
        break;
      case 'DTEND': cur.end = parseDate(prop.value, prop.params, defaultTz); break;
      case 'DURATION': cur.duration = parseDuration(prop.value); break;
    }
  }
  return { events, recurring };
}
