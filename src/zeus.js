import { HttpError } from './http.js';

// Zeus (EPITA's timetable site) publishes a private subscription link per group, teacher or room:
// https://zeus.ionis-it.com/api/group/<id>/ics/<token>[?startDate=YYYY-MM-DD]. It needs no sign-in,
// so the Worker can fetch it on a schedule. Only that host and path are accepted, so a pasted link
// can't make the Worker fetch anything else.
const HOST = 'zeus.ionis-it.com';
const PATH = /^\/api\/(group|teacher|room)\/\d{1,12}\/ics\/[A-Za-z0-9._~-]{8,200}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const FETCH_TIMEOUT_MS = 20000;

export const validDate = (s) => typeof s === 'string' && DATE.test(s) && !Number.isNaN(Date.parse(s));

// Returns { url, from } with the link reduced to its canonical form, or throws a 400.
export function parseZeusLink(input) {
  let u;
  try { u = new URL(String(input ?? '').trim().replace(/^webcals?:\/\//i, 'https://')); } catch { u = null; }
  if (!u || u.protocol !== 'https:' || u.hostname !== HOST || !PATH.test(u.pathname)) {
    throw new HttpError(400, 'Paste the link from Zeus → Generate an ICS → Generate my link. It starts with https://zeus.ionis-it.com/api/');
  }
  const start = u.searchParams.get('startDate')?.slice(0, 10);
  return { url: `https://${HOST}${u.pathname}`, from: validDate(start) ? start : null };
}

// Downloads the calendar: { text, status }. Errors are worded for the user, since they're shown in Settings,
// and carry Zeus's HTTP status as `upstream` when it answered.
export async function fetchZeus(url, from, maxBytes) {
  const target = from ? `${url}?startDate=${from}` : url;
  let res;
  try {
    res = await fetch(target, {
      headers: { Accept: 'text/calendar', 'User-Agent': 'Attendance80 (+https://epita.knhdixh.com)' },
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    });
  } catch {
    throw new HttpError(502, 'Zeus didn\'t respond. It will be retried automatically.');
  }
  // Kept on errors too, for the sync log.
  const upstream = (e) => Object.assign(e, { upstream: res.status });
  if (res.status === 401 || res.status === 403 || res.status === 404) {
    throw upstream(new HttpError(400, 'Zeus no longer accepts this link. Generate a new one on Zeus and paste it again.'));
  }
  if (!res.ok) throw upstream(new HttpError(502, `Zeus returned an error (${res.status}). It will be retried automatically.`));
  if (Number(res.headers.get('Content-Length') || 0) > maxBytes) throw upstream(new HttpError(413, 'The calendar from Zeus is too large.'));
  const text = await res.text();
  if (text.length > maxBytes) throw upstream(new HttpError(413, 'The calendar from Zeus is too large.'));
  if (!text.includes('BEGIN:VCALENDAR')) throw upstream(new HttpError(502, 'Zeus sent something that isn\'t a calendar. It will be retried automatically.'));
  return { text, status: res.status };
}
