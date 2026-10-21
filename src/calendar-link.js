import { HttpError } from './http.js';

// Calendar links the Worker re-imports on a schedule. Zeus (EPITA's timetable site) publishes a private
// subscription link per group, teacher or room: https://zeus.ionis-it.com/api/group/<id>/ics/<token>[?startDate=…].
// Zeus links are reduced to that canonical form; any other https (or webcal) .ics link is kept as given.
// Links to IP addresses, localhost or with credentials are refused, so a pasted link can only reach a public site.
const ZEUS_HOST = 'zeus.ionis-it.com';
const ZEUS_PATH = /^\/api\/(group|teacher|room)\/(\d{1,12})\/ics\/[A-Za-z0-9._~-]{8,200}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const FETCH_TIMEOUT_MS = 20000;
const MAX_URL = 2000;

export const validDate = (s) => typeof s === 'string' && DATE.test(s) && !Number.isNaN(Date.parse(s));

const BAD_LINK = 'Paste a calendar link that starts with https:// or webcal://, e.g. Zeus → Generate an ICS → Generate my link.';

// Returns { url, from, zeus, name }: the link to store, Zeus's startDate if it had one, and a default name.
export function parseCalendarLink(input) {
  const raw = String(input ?? '').trim();
  let u;
  try { u = new URL(raw.replace(/^webcals?:\/\//i, 'https://')); } catch { u = null; }
  if (!u || u.protocol !== 'https:' || raw.length > MAX_URL) throw new HttpError(400, BAD_LINK);

  if (u.hostname === ZEUS_HOST) {
    const m = u.pathname.match(ZEUS_PATH);
    if (!m) throw new HttpError(400, 'That Zeus link isn\'t a calendar link. On Zeus, use Generate an ICS → Generate my link.');
    const start = u.searchParams.get('startDate')?.slice(0, 10);
    return { url: `https://${ZEUS_HOST}${u.pathname}`, from: validDate(start) ? start : null, zeus: true, name: `Zeus ${m[1]} ${m[2]}` };
  }

  const host = u.hostname.toLowerCase();
  const ipLiteral = /^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.startsWith('[');
  if (u.username || u.password || ipLiteral || host === 'localhost' || host.endsWith('.localhost') || !host.includes('.')) {
    throw new HttpError(400, BAD_LINK);
  }
  u.hash = '';
  // Sisu share links: https://sisu.tuni.fi/ilmo/api/calendar-share/<uuid>
  const sisu = /(^|\.)sisu\./.test(host) && u.pathname.includes('/calendar-share/');
  return { url: u.toString(), from: null, zeus: false, sisu, name: sisu ? `Sisu (${host.split('.').slice(1).join('.')})` : host.replace(/^www\./, '') };
}

const isZeus = (url) => new URL(url).hostname === ZEUS_HOST;

// Downloads a calendar: { text, status }. Errors are worded for the user, since they're shown in Settings,
// and carry the site's HTTP status as `upstream` when it answered.
export async function fetchCalendar(url, from, maxBytes) {
  const zeus = isZeus(url);
  const site = zeus ? 'Zeus' : 'The calendar site';
  const target = zeus && from ? `${url}?startDate=${from}` : url;
  let res;
  try {
    res = await fetch(target, {
      headers: { Accept: 'text/calendar', 'User-Agent': 'Attendance80 (+https://epita.knhdixh.com)' },
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      redirect: 'follow',
    });
  } catch {
    throw new HttpError(502, `${site} didn't respond. It will be retried automatically.`);
  }
  // Kept on errors too, for the sync log.
  const upstream = (e) => Object.assign(e, { upstream: res.status });
  if (res.status === 401 || res.status === 403 || res.status === 404 || res.status === 410) {
    throw upstream(new HttpError(400, zeus
      ? 'Zeus no longer accepts this link. Generate a new one on Zeus and add it again.'
      : `This link no longer works (${res.status}). Check it in the calendar app that made it.`));
  }
  if (!res.ok) throw upstream(new HttpError(502, `${site} returned an error (${res.status}). It will be retried automatically.`));
  if (Number(res.headers.get('Content-Length') || 0) > maxBytes) throw upstream(new HttpError(413, 'This calendar is too large (5 MB at most).'));
  const text = await res.text();
  if (text.length > maxBytes) throw upstream(new HttpError(413, 'This calendar is too large (5 MB at most).'));
  if (!text.includes('BEGIN:VCALENDAR')) {
    throw upstream(new HttpError(zeus ? 502 : 400, zeus
      ? 'Zeus sent something that isn\'t a calendar. It will be retried automatically.'
      : 'This link doesn\'t lead to a calendar (.ics). Copy the iCal/ICS link, not the web page.'));
  }
  return { text, status: res.status };
}
