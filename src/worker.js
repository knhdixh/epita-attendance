import { TAGS, STATUSES } from '../public/stats.js';
import { buildFeed, NO_DATE } from './homework.js';
import { buildTimetableFeed } from './timetable-feed.js';
import { GCAL_SCOPE, encrypt, deleteGcalEvent, resync, disconnect } from './gcal.js';
import { HttpError } from './http.js';
import { HOMEWORK_COLUMNS, createHomework, updateHomework, planHomework, completeTask, uncompleteTask, deleteTask } from './homework-store.js';
import { moveTasks } from './placement.js';
import {
  createRow, updateRow, deleteRow, reorderRows, listComments, createComment, updateComment, deleteComment,
  createReminder, updateReminder, deleteReminder, taskDataQueries, hydrate, hydrateRows, listCompletions,
} from './tasks.js';
import { deleteAccount } from './account.js';
import { saveBreak, removeBreak } from './breaks-store.js';
import { handlePasskeyAuth, listPasskeys, deletePasskey, isPasskeyOnly } from './passkeys.js';
import { createCatchup, setRecordingUrl } from './catchups.js';
import { userFromToken, tokenAllows, listTokens, createToken, revokeToken } from './tokens.js';
import { buildPlan } from '../public/plan.js';
import { focusSummary } from '../public/focus.js';
import { FOCUS_COLUMNS, focusRow, focusSettings, saveFocusSettings, logFocus, listFocus, deleteFocus } from './focus.js';
import { isValidTimeZone } from './homework.js';
import { habitQueries, habitRow, createHabit, updateHabit, deleteHabit, logHabit, reorderHabits, LOG_DAYS } from './habits.js';
import { COUNTDOWN_COLUMNS, createCountdown, updateCountdown, deleteCountdown } from './countdowns.js';
import { habitStats, scheduleLabel, keyInZone, diffDays, addDays } from '../public/habits.js';
import { occurrence } from '../public/countdowns.js';
import {
  notifySettings, saveNotifySettings, subscribe, unsubscribe, deviceCount, setFocusPush, sendTest, sendDueNotifications,
} from './notify.js';
import { pushEnabled } from './push.js';
import {
  MAX_ICS_BYTES, listCalendars, addLink, addFile, replaceFile, syncCalendar, updateCalendar, removeCalendar,
  syncDueCalendars, wipeCalendars,
} from './calendars.js';

export { syncDueCalendars };

const SESSION_COOKIE = 'sid';
const STATE_COOKIE = 'oauth_state';
const SESSION_TTL_MS = 30 * 24 * 3600 * 1000;
const FOCUS_DATA_DAYS = 56;

const json = (data, init = {}) => Response.json(data, init);

function parseCookies(header) {
  const out = {};
  for (const part of (header || '').split(';')) {
    const eq = part.indexOf('=');
    if (eq > 0) out[part.slice(0, eq).trim()] = decodeURIComponent(part.slice(eq + 1).trim());
  }
  return out;
}

function cookie(name, value, maxAgeMs, secure) {
  return [`${name}=${encodeURIComponent(value)}`, 'Path=/', 'HttpOnly', 'SameSite=Lax',
    `Max-Age=${Math.floor(maxAgeMs / 1000)}`, secure ? 'Secure' : ''].filter(Boolean).join('; ');
}

function randomToken(bytes = 32) {
  const a = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...a)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function redirect(location, cookies = []) {
  const headers = new Headers({ Location: location });
  for (const c of cookies) headers.append('Set-Cookie', c);
  return new Response(null, { status: 302, headers });
}

// ---------------------------------------------------------------- auth

const APP_SESSION_PREFIX = 'a80s_';
const PKCE_CHALLENGE_RE = /^[A-Za-z0-9_-]{43}$/;

async function insertAppCode(db, code, userId, challenge, expiresAt) {
  await db.batch([
    db.prepare('DELETE FROM app_codes WHERE expires_at < ?').bind(Date.now()),
    db.prepare('INSERT INTO app_codes (code, user_id, challenge, expires_at) VALUES (?, ?, ?, ?)').bind(code, userId, challenge, expiresAt),
  ]);
}

async function sha256Base64Url(str) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(str));
  const bytes = new Uint8Array(d);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function currentUser(request, env) {
  let sid = parseCookies(request.headers.get('Cookie'))[SESSION_COOKIE];
  if (!sid) {
    const auth = request.headers.get('Authorization') || '';
    const m = /^Bearer\s+(a80s_[A-Za-z0-9_-]{20,})$/.exec(auth);
    if (m) sid = m[1];
  }
  if (!sid) return null;
  const row = await env.DB.prepare(`SELECT u.*, s.expires_at AS session_expires_at FROM sessions s JOIN users u ON u.id = s.user_id
    WHERE s.id = ? AND s.expires_at > ?`).bind(sid, Date.now()).first();
  if (!row) return null;
  const { session_expires_at: expiresAt, ...user } = row;
  if (sid.startsWith(APP_SESSION_PREFIX)) {
    const now = Date.now();
    if (expiresAt - now < 15 * 24 * 3600 * 1000) {
      await env.DB.prepare('UPDATE sessions SET expires_at = ? WHERE id = ?').bind(now + SESSION_TTL_MS, sid).run();
    }
  }
  return { ...user, sessionId: sid };
}

async function upsertUser(env, profile) {
  return await env.DB.prepare(`INSERT INTO users (google_sub, email, name, picture, created_at)
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT (google_sub) DO UPDATE SET email = excluded.email, name = excluded.name, picture = excluded.picture
    RETURNING id`).bind(profile.sub, profile.email ?? null, profile.name ?? null, profile.picture ?? null, Date.now()).first();
}

async function signIn(env, profile, secure) {
  const user = await upsertUser(env, profile);
  return startSession(env, user.id, secure);
}

// Creates a session for a user and returns its Set-Cookie header.
async function startSession(env, userId, secure) {
  const sid = randomToken();
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE user_id = ? AND expires_at < ?').bind(userId, Date.now()),
    env.DB.prepare('INSERT INTO sessions (id, user_id, expires_at) VALUES (?, ?, ?)')
      .bind(sid, userId, Date.now() + SESSION_TTL_MS),
  ]);
  return cookie(SESSION_COOKIE, sid, SESSION_TTL_MS, secure);
}

async function startAppSession(env, userId) {
  const sid = APP_SESSION_PREFIX + randomToken(32);
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE user_id = ? AND expires_at < ?').bind(userId, Date.now()),
    // The native app posts reminders itself. The TWA it replaces registered Web Push (app = 1); drop those so
    // reminders don't arrive twice. Browser subscriptions (app = 0) stay.
    env.DB.prepare('DELETE FROM push_subscriptions WHERE user_id = ? AND app = 1').bind(userId),
    env.DB.prepare('INSERT INTO sessions (id, user_id, expires_at) VALUES (?, ?, ?)')
      .bind(sid, userId, Date.now() + SESSION_TTL_MS),
  ]);
  return sid;
}

const googleEnabled = (env) => Boolean(env.GOOGLE_CLIENT_ID && env.GOOGLE_CLIENT_SECRET);
const devLoginEnabled = (env) => env.DEV_LOGIN === '1' || env.DEV_LOGIN === 'true';

async function handleAuth(request, env, url) {
  const secure = url.protocol === 'https:';
  const redirectUri = `${url.origin}/auth/google/callback`;

  if (url.pathname === '/auth/config') {
    return json({ google: googleEnabled(env), dev: devLoginEnabled(env), passkeys: true, signup: !(env.ALLOWED_DOMAIN || '').trim() });
  }

  if (url.pathname.startsWith('/auth/passkey/')) {
    return handlePasskeyAuth(request, env, url, { secure, cookie, parseCookies, randomToken, currentUser, startSession, startAppSession });
  }

  // Both sign-in and "connect Google Calendar" use this flow; the state cookie records which.
  const startOAuth = (purpose, params, extraState) => {
    const state = randomToken(16);
    const target = new URL('https://accounts.google.com/o/oauth2/v2/auth');
    target.search = new URLSearchParams({
      client_id: env.GOOGLE_CLIENT_ID, redirect_uri: redirectUri, response_type: 'code', state, ...params,
    });
    const stateVal = extraState ? `${purpose}.${state}.${extraState}` : `${purpose}.${state}`;
    return redirect(target.toString(), [cookie(STATE_COOKIE, stateVal, 10 * 60 * 1000, secure)]);
  };

  if (url.pathname === '/auth/google') {
    if (!googleEnabled(env)) return new Response('Google sign-in is not configured.', { status: 503 });
    return startOAuth('login', { scope: 'openid email profile', prompt: 'select_account' });
  }

  if (url.pathname === '/auth/app/google') {
    if (!googleEnabled(env)) return new Response('Google sign-in is not configured.', { status: 503 });
    const challenge = url.searchParams.get('challenge');
    if (!challenge || !PKCE_CHALLENGE_RE.test(challenge)) return new Response('Invalid challenge.', { status: 400 });
    return startOAuth('app', { scope: 'openid email profile', prompt: 'select_account' }, challenge);
  }

  if (url.pathname === '/auth/google/calendar') {
    const user = await currentUser(request, env);
    if (!user) return redirect('/');
    if (!googleEnabled(env) || !env.TOKEN_KEY) return redirect('/?gcal_error=config#settings');
    const isApp = url.searchParams.get('app') === '1';
    return startOAuth(isApp ? 'calendar-app' : 'calendar', {
      scope: `openid email ${GCAL_SCOPE}`, access_type: 'offline', prompt: 'consent',
      include_granted_scopes: 'true', login_hint: user.email || '',
    });
  }

  if (url.pathname === '/auth/google/callback') {
    const clearState = cookie(STATE_COOKIE, '', 0, secure);
    const [purpose, expected, challenge] = (parseCookies(request.headers.get('Cookie'))[STATE_COOKIE] || '').split('.');
    const isGcal = purpose === 'calendar' || purpose === 'calendar-app';
    const fail = (code) => {
      if (purpose === 'app') return redirect(`com.knhdixh.attendance80:/auth?error=${code}`, [clearState]);
      if (purpose === 'calendar-app') return redirect(`com.knhdixh.attendance80:/gcal?error=${code}`, [clearState]);
      if (purpose === 'calendar') return redirect(`/?gcal_error=${code}#settings`, [clearState]);
      return redirect(`/?auth_error=${code}`, [clearState]);
    };
    const { code, state, error } = Object.fromEntries(url.searchParams);
    if (error) return fail(encodeURIComponent(error));
    if (!code || !state || state !== expected) return fail('state');
    try {
      const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          code, client_id: env.GOOGLE_CLIENT_ID, client_secret: env.GOOGLE_CLIENT_SECRET,
          redirect_uri: redirectUri, grant_type: 'authorization_code',
        }),
      });
      if (!tokenRes.ok) throw new Error(`token exchange failed: ${tokenRes.status} ${await tokenRes.text()}`);
      const tokens = await tokenRes.json();
      const infoRes = await fetch('https://openidconnect.googleapis.com/v1/userinfo', {
        headers: { Authorization: `Bearer ${tokens.access_token}` },
      });
      if (!infoRes.ok) throw new Error(`userinfo failed: ${infoRes.status}`);
      const info = await infoRes.json();

      if (isGcal) {
        const user = await currentUser(request, env);
        if (!user) return redirect(purpose === 'calendar-app' ? 'com.knhdixh.attendance80:/gcal?error=session' : '/', [clearState]);
        if (!String(tokens.scope || '').split(' ').includes(GCAL_SCOPE)) return fail('scope');
        if (!tokens.refresh_token) return fail('refresh');
        // Reconnecting keeps calendar_id and event ids, so events aren't duplicated. Unsynced
        // homework is pushed by the client-driven resync that follows the redirect.
        await env.DB.prepare(`INSERT INTO gcal_links (user_id, email, refresh_token, access_token, access_expires, connected_at)
          VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT (user_id) DO UPDATE SET email = excluded.email, refresh_token = excluded.refresh_token,
            access_token = excluded.access_token, access_expires = excluded.access_expires, last_error = NULL`)
          .bind(user.id, info.email ?? null, await encrypt(env, tokens.refresh_token), tokens.access_token,
            Date.now() + tokens.expires_in * 1000, Date.now()).run();
        return redirect(purpose === 'calendar-app' ? 'com.knhdixh.attendance80:/gcal?result=connected' : '/?gcal=connected#settings', [clearState]);
      }

      const domain = (env.ALLOWED_DOMAIN || '').trim().toLowerCase();
      if (!info.email_verified || (domain && !String(info.email).toLowerCase().endsWith('@' + domain))) {
        return fail('domain');
      }
      const user = await upsertUser(env, info);
      if (purpose === 'app') {
        const appCode = randomToken(24);
        await insertAppCode(env.DB, appCode, user.id, challenge, Date.now() + 2 * 60 * 1000);
        return redirect(`com.knhdixh.attendance80:/auth?code=${appCode}`, [clearState]);
      }
      return redirect('/', [clearState, await startSession(env, user.id, secure)]);
    } catch (e) {
      console.error(e);
      return fail('google');
    }
  }

  if (url.pathname === '/auth/dev' && devLoginEnabled(env)) {
    const email = url.searchParams.get('email') || 'dev@example.com';
    return redirect('/', [await signIn(env, { sub: 'dev:' + email, email, name: email.split('@')[0] }, secure)]);
  }

  if (url.pathname === '/auth/app/dev' && devLoginEnabled(env)) {
    const email = url.searchParams.get('email') || 'dev@example.com';
    const challenge = url.searchParams.get('challenge');
    if (!challenge || !PKCE_CHALLENGE_RE.test(challenge)) return new Response('Invalid challenge.', { status: 400 });
    const user = await upsertUser(env, { sub: 'dev:' + email, email, name: email.split('@')[0] });
    const code = randomToken(24);
    await insertAppCode(env.DB, code, user.id, challenge, Date.now() + 2 * 60 * 1000);
    return redirect(`com.knhdixh.attendance80:/auth?code=${code}`);
  }

  if (url.pathname === '/auth/app/token' && request.method === 'POST') {
    try {
      const body = await readJson(request);
      const { code, verifier } = body || {};
      if (!code || !verifier) throw new Error();
      const row = await env.DB.prepare('DELETE FROM app_codes WHERE code = ? RETURNING user_id, challenge, expires_at').bind(code).first();
      if (!row || row.expires_at < Date.now()) throw new Error();
      const expectedChallenge = await sha256Base64Url(verifier);
      if (expectedChallenge !== row.challenge) throw new Error();
      const token = await startAppSession(env, row.user_id);
      return json({ token });
    } catch {
      return json({ error: 'Sign-in expired, please try again.' }, { status: 400 });
    }
  }

  if (url.pathname === '/auth/app/web') {
    const code = url.searchParams.get('code');
    if (!code) return new Response('Missing code', { status: 400 });
    const row = await env.DB.prepare('DELETE FROM app_codes WHERE code = ? RETURNING user_id, challenge, expires_at').bind(code).first();
    if (!row || row.expires_at < Date.now() || !row.challenge?.startsWith('web:')) {
      return new Response('Sign-in link expired.', { status: 400 });
    }
    const next = row.challenge.slice(4);
    if (next !== '/auth/google/calendar' && next !== '/auth/google/calendar?app=1') {
      return new Response('Invalid destination.', { status: 400 });
    }
    return redirect(next, [await startSession(env, row.user_id, secure)]);
  }

  if (url.pathname === '/auth/logout' && request.method === 'POST') {
    let sid = parseCookies(request.headers.get('Cookie'))[SESSION_COOKIE];
    if (!sid) {
      const m = /^Bearer\s+(a80s_[A-Za-z0-9_-]{20,})$/.exec(request.headers.get('Authorization') || '');
      if (m) sid = m[1];
    }
    if (sid) await env.DB.prepare('DELETE FROM sessions WHERE id = ?').bind(sid).run();
    return new Response(JSON.stringify({ ok: true }), {
      headers: { 'Content-Type': 'application/json', 'Set-Cookie': cookie(SESSION_COOKIE, '', 0, secure) },
    });
  }

  return new Response('Not found', { status: 404 });
}

// ---------------------------------------------------------------- API

async function readJson(request, limit = 64 * 1024) {
  const len = Number(request.headers.get('Content-Length') || 0);
  if (len > limit) throw new HttpError(413, 'Request too large.');
  const text = await request.text();
  if (text.length > limit) throw new HttpError(413, 'Request too large.');
  try { return JSON.parse(text || '{}'); } catch { throw new HttpError(400, 'Invalid JSON.'); }
}

async function feedToken(env, user) {
  if (user.feed_token) return user.feed_token;
  const token = randomToken(24);
  await env.DB.prepare('UPDATE users SET feed_token = ? WHERE id = ? AND feed_token IS NULL').bind(token, user.id).run();
  return (await env.DB.prepare('SELECT feed_token FROM users WHERE id = ?').bind(user.id).first()).feed_token;
}

async function handleFeed(env, url) {
  // /feed/<token>.ics is homework, /feed/<token>/timetable.ics the timetable. Both use the same private token.
  const m = url.pathname.match(/^\/feed\/([A-Za-z0-9_-]{20,})(\.ics|\/timetable\.ics)$/);
  const user = m && await env.DB.prepare('SELECT id FROM users WHERE feed_token = ?').bind(m[1]).first();
  if (!user) return new Response('Not found', { status: 404 });
  if (m[2] === '/timetable.ics') return timetableFeed(env, url, user);
  const [homework, courses] = await env.DB.batch([
    // Upcoming homework plus the last 60 days, so the feed stays small.
    env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE user_id = ? AND due_date != '${NO_DATE}' AND due_at > ? ORDER BY due_at`)
      .bind(user.id, Date.now() - 60 * 86400000),
    env.DB.prepare('SELECT id, name FROM courses WHERE user_id = ?').bind(user.id),
  ]);
  const names = Object.fromEntries(courses.results.map((c) => [c.id, c.name]));
  return new Response(buildFeed(homework.results, names, { host: url.hostname }), {
    headers: {
      'Content-Type': 'text/calendar; charset=utf-8',
      'Content-Disposition': 'inline; filename="homework.ics"',
      'Cache-Control': 'private, max-age=300',
    },
  });
}

async function timetableFeed(env, url, user) {
  // ?groups=12,15 keeps the sessions of those calendars (picked under the link in Settings); none means all.
  const groups = [...new Set((url.searchParams.get('groups') || '').split(',').map(Number))].filter((n) => Number.isInteger(n) && n > 0);
  // The whole imported calendar (a semester is ~100 KB), not just upcoming sessions.
  const [events, courses, cals] = await env.DB.batch([
    env.DB.prepare(`SELECT uid, course_id, summary, location, description, start_at, end_at,
        (SELECT json_group_array(source_id) FROM event_sources WHERE event_id = events.id) AS cals
      FROM events WHERE user_id = ? ORDER BY start_at`).bind(user.id),
    env.DB.prepare('SELECT id, tracked FROM courses WHERE user_id = ?').bind(user.id),
    env.DB.prepare('SELECT id, name FROM calendar_sources WHERE user_id = ? ORDER BY created_at, id').bind(user.id),
  ]);
  const names = cals.results.filter((c) => groups.includes(c.id)).map((c) => c.name);
  const name = names.length && names.length < cals.results.length ? `Timetable (${names.join(' + ')})` : 'Timetable';
  const list = events.results.map((e) => ({ ...e, cals: JSON.parse(e.cals) }));
  return new Response(buildTimetableFeed(list, courses.results, { host: url.hostname, groups, name }), {
    headers: {
      'Content-Type': 'text/calendar; charset=utf-8',
      'Content-Disposition': 'inline; filename="timetable.ics"',
      'Cache-Control': 'private, max-age=300',
    },
  });
}

// Digital Asset Links: lets the Android app (android/, a Trusted Web Activity) open this site full screen.
function assetLinks(env) {
  const fingerprints = (env.ANDROID_CERT_SHA256 || '').split(',').map((f) => f.trim().toUpperCase()).filter(Boolean);
  if (!env.ANDROID_PACKAGE || !fingerprints.length) return new Response('Not found', { status: 404 });
  return json([{
    // use_as_origin: the app may open a postMessage channel to the page as this origin (the focus timer bridge).
    relation: [
      'delegate_permission/common.handle_all_urls',
      'delegate_permission/common.use_as_origin',
      'delegate_permission/common.get_login_creds',
    ],
    target: { namespace: 'android_app', package_name: env.ANDROID_PACKAGE, sha256_cert_fingerprints: fingerprints },
  }], { headers: { 'Cache-Control': 'public, max-age=3600' } });
}

// Habit check-ins are kept by local day; a day early covers every time zone.
const logsSince = () => new Date(Date.now() - (LOG_DAYS + 1) * 86400000).toISOString().slice(0, 10);

// For /api/plan: each active habit as of today in tz.
function planHabits(habits, logs, { now, tz }) {
  const today = keyInZone(now, tz);
  return habits.filter((h) => !h.archived).map((h) => {
    const counts = new Map(logs.filter((l) => l.habit_id === h.id).map((l) => [l.day, l.count]));
    const s = habitStats(h, counts, { today, start: keyInZone(h.created_at, tz) });
    return {
      id: h.id, name: h.name, schedule: scheduleLabel(h.schedule), target: h.target, unit: h.unit,
      today: s.today, done: s.todayDone, due: s.due, streak: s.streak, streakUnit: s.unit, best: s.best,
      rate30: s.rate == null ? null : Math.round(s.rate * 100) / 100, progress: s.progress,
      last7: Array.from({ length: 7 }, (_, i) => addDays(today, i - 6)).map((day) => ({ day, count: counts.get(day) || 0 })),
    };
  });
}

// For /api/plan: countdowns with their next date and the days to it (negative: days since) in tz. All-day dates are
// the user's local midnight; reading them six hours later keeps them on their day across DST changes.
function planCountdowns(list, { now, tz }) {
  const today = keyInZone(now, tz);
  return list.map((c) => {
    const { at } = occurrence(c, now);
    const date = keyInZone(c.all_day ? at + 6 * 3600000 : at, tz);
    return { id: c.id, title: c.title, date, at, allDay: Boolean(c.all_day), repeat: c.repeat, pinned: Boolean(c.pinned), days: diffDays(date, today), note: c.note };
  }).sort((a, b) => (a.days < 0) - (b.days < 0) || Math.abs(a.days) - Math.abs(b.days));
}

const validTag = (t) => Object.hasOwn(TAGS, t);
const validStatus = (s) => s === null || STATUSES.includes(s);

async function handleApi(request, env, url, user) {
  const { method } = request;
  const path = url.pathname;
  const DB = env.DB;
  let m;

  if (path === '/api/data' && method === 'GET') {
    const all = await DB.batch([
      DB.prepare('SELECT id, name, tracked, tag, attendance, recording_url FROM courses WHERE user_id = ? ORDER BY name').bind(user.id),
      DB.prepare(`SELECT id, course_id, summary, location, description, start_at, end_at, status, tag, note,
          (SELECT json_group_array(source_id) FROM event_sources WHERE event_id = events.id) AS cals
        FROM events WHERE user_id = ? ORDER BY start_at`).bind(user.id),
      DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE user_id = ? ORDER BY due_at`).bind(user.id),
      DB.prepare('SELECT email, calendar_id, last_error FROM gcal_links WHERE user_id = ?').bind(user.id),
      DB.prepare('SELECT id, label, start_date, end_date, changes, created_at FROM breaks WHERE user_id = ? ORDER BY start_date').bind(user.id),
      DB.prepare(`SELECT source_id, at, source, ok, http_status, duration_ms, sessions, added, removed, error FROM calendar_syncs
        WHERE user_id = ? ORDER BY id DESC LIMIT 20`).bind(user.id),
      // The last eight weeks of focus (Today, homework totals, the week chart); the Focus page loads the year.
      DB.prepare(`SELECT ${FOCUS_COLUMNS} FROM focus_sessions WHERE user_id = ? AND started_at >= ? ORDER BY started_at`)
        .bind(user.id, Date.now() - FOCUS_DATA_DAYS * 86400000),
      ...habitQueries(env, user, logsSince()),
      DB.prepare(`SELECT ${COUNTDOWN_COLUMNS} FROM countdowns WHERE user_id = ? ORDER BY at`).bind(user.id),
      DB.prepare('SELECT COUNT(*) AS n FROM push_subscriptions WHERE user_id = ?').bind(user.id),
      ...taskDataQueries(env, user),
    ]);
    const [courses, events, homework, link, breaks, syncs, focus, habits, habitLogs, countdowns, devices] = all;
    const taskData = all.slice(11);
    const gcal = link.results[0];
    return json({
      user: { email: user.email, name: user.name, picture: user.picture, threshold: user.threshold, unit: user.unit },
      account: { google: !isPasskeyOnly(user), passkeys: await listPasskeys(env, user) },
      courses: courses.results,
      // cals: the calendars (groups) each session is in.
      events: events.results.map((e) => ({ ...e, cals: JSON.parse(e.cals).sort((a, b) => a - b) })),
      // Tasks without a date are left out unless the client asks (?tasks=1), so older clients see what they always did.
      homework: hydrate(homework.results, taskData, { dated: url.searchParams.get('tasks') !== '1' }),
      projects: taskData[0].results.map((p) => ({ ...p, favorite: Boolean(p.favorite), archived: Boolean(p.archived) })),
      sections: taskData[1].results,
      labels: taskData[2].results.map((l) => ({ ...l, favorite: Boolean(l.favorite) })),
      filters: taskData[3].results.map((f) => ({ ...f, favorite: Boolean(f.favorite) })),
      breaks: breaks.results.map((b) => ({ ...b, sessions: JSON.parse(b.changes).length, changes: undefined })),
      integrations: {
        feedUrl: `${url.origin}/feed/${await feedToken(env, user)}.ics`,
        gcalAvailable: googleEnabled(env) && Boolean(env.TOKEN_KEY),
        gcal: gcal ? { email: gcal.email, calendarId: gcal.calendar_id, error: gcal.last_error } : null,
      },
      calendars: await listCalendars(env, user.id),
      calendarSyncs: syncs.results,
      focus: { settings: focusSettings(user), sessions: focus.results.map(focusRow), since: Date.now() - FOCUS_DATA_DAYS * 86400000 },
      habits: { list: habits.results.map(habitRow), logs: habitLogs.results, since: logsSince() },
      countdowns: countdowns.results,
      // key: the VAPID public key browsers subscribe with (null: push isn't set up here); devices: subscriptions.
      notifications: { key: pushEnabled(env) ? env.VAPID_PUBLIC_KEY : null, settings: notifySettings(user), devices: devices.results[0].n },
    });
  }

  // The day plan (see public/plan.js) with the sessions, to-dos and courses it refers to, for scripts.
  // ?tz=Europe/Helsinki sets the day boundaries (default: the time zone of the request's location).
  if (path === '/api/plan' && method === 'GET') {
    const tz = [url.searchParams.get('tz'), request.cf?.timezone, 'Europe/Paris'].find((z) => z && isValidTimeZone(z));
    const now = Date.now();
    const [courses, events, homework, cals, focus, habits, habitLogs, countdowns] = await DB.batch([
      DB.prepare('SELECT id, name, tracked, tag, attendance, recording_url FROM courses WHERE user_id = ? ORDER BY name').bind(user.id),
      DB.prepare(`SELECT id, course_id, summary, location, start_at, end_at, status, tag, note,
          (SELECT json_group_array(source_id) FROM event_sources WHERE event_id = events.id) AS cals
        FROM events WHERE user_id = ? AND end_at > ? AND start_at < ? ORDER BY start_at`).bind(user.id, now - 14 * 86400000, now + 21 * 86400000),
      DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE user_id = ? AND due_date != ? AND (done = 0 OR due_at > ?) ORDER BY due_at`).bind(user.id, NO_DATE, now - 14 * 86400000),
      DB.prepare('SELECT id, name, attendance FROM calendar_sources WHERE user_id = ? ORDER BY created_at, id').bind(user.id),
      DB.prepare(`SELECT ${FOCUS_COLUMNS} FROM focus_sessions WHERE user_id = ? AND started_at >= ? ORDER BY started_at`)
        .bind(user.id, now - FOCUS_DATA_DAYS * 86400000),
      ...habitQueries(env, user, logsSince()),
      DB.prepare(`SELECT ${COUNTDOWN_COLUMNS} FROM countdowns WHERE user_id = ? ORDER BY at`).bind(user.id),
    ]);
    const evs = events.results.map((e) => ({ ...e, cals: JSON.parse(e.cals) }));
    const hw = homework.results.map(({ gcal_event_id: _, sync_error: __, ...h }) => h);
    return json({
      plan: buildPlan({ courses: courses.results, events: evs, homework: hw, now, tz }),
      courses: courses.results, calendars: cals.results.map((c) => ({ ...c, attendance: Boolean(c.attendance) })),
      sessions: evs, homework: hw,
      attendance: { threshold: user.threshold, unit: user.unit },
      // Focus (Pomodoro) time in the days of tz; the sessions themselves are at GET /api/focus.
      focus: { settings: focusSettings(user), ...focusSummary(focus.results.map(focusRow), { now, tz }) },
      habits: planHabits(habits.results.map(habitRow), habitLogs.results, { now, tz }),
      countdowns: planCountdowns(countdowns.results, { now, tz }),
    });
  }

  // Focus: ?since=<ms> lists sessions from then on (a year at most).
  if (path === '/api/focus' && method === 'GET') {
    const since = Math.max(Number(url.searchParams.get('since')) || 0, Date.now() - 400 * 86400000);
    return json({ sessions: await listFocus(env, user, since), since });
  }
  if (path === '/api/focus' && method === 'POST') return json(await logFocus(env, user, await readJson(request)));
  if ((m = path.match(/^\/api\/focus\/(\d+)$/)) && method === 'DELETE') return json(await deleteFocus(env, user, Number(m[1])));
  if (path === '/api/focus/settings' && method === 'PUT') return json({ settings: await saveFocusSettings(env, user, await readJson(request)) });

  // Habits: check-ins are PUT per local day ({ day: 'YYYY-MM-DD', count }, 0 clears the day).
  if (path === '/api/habits' && method === 'POST') return json(await createHabit(env, user, await readJson(request)));
  if (path === '/api/habits/order' && method === 'POST') return json(await reorderHabits(env, user, await readJson(request)));
  if ((m = path.match(/^\/api\/habits\/(\d+)$/)) && method === 'PATCH') return json(await updateHabit(env, user, Number(m[1]), await readJson(request)));
  if ((m = path.match(/^\/api\/habits\/(\d+)$/)) && method === 'DELETE') return json(await deleteHabit(env, user, Number(m[1])));
  if ((m = path.match(/^\/api\/habits\/(\d+)\/log$/)) && method === 'PUT') return json(await logHabit(env, user, Number(m[1]), await readJson(request)));

  if (path === '/api/countdowns' && method === 'POST') return json(await createCountdown(env, user, await readJson(request)));
  if ((m = path.match(/^\/api\/countdowns\/(\d+)$/)) && method === 'PATCH') return json(await updateCountdown(env, user, Number(m[1]), await readJson(request)));
  if ((m = path.match(/^\/api\/countdowns\/(\d+)$/)) && method === 'DELETE') return json(await deleteCountdown(env, user, Number(m[1])));

  // Notifications (Web Push): this device's subscription, what to be notified about, and the focus timer's end.
  if (path === '/api/push/subscribe' && method === 'POST') return json(await subscribe(env, user, await readJson(request)));
  if (path === '/api/push/unsubscribe' && method === 'POST') return json(await unsubscribe(env, user, await readJson(request)));
  if (path === '/api/push/test' && method === 'POST') return json(await sendTest(env, user));
  if (path === '/api/push/focus' && method === 'PUT') return json(await setFocusPush(env, user, await readJson(request)));
  if (path === '/api/notify/settings' && method === 'PUT') {
    return json({ settings: await saveNotifySettings(env, user, await readJson(request)), devices: await deviceCount(env, user) });
  }

  if (path === '/api/catchups' && method === 'POST') {
    return json(await createCatchup(env, user, await readJson(request), url.origin));
  }

  if (path === '/api/app/web-link' && method === 'POST') {
    if (!user.sessionId?.startsWith('a80s_')) throw new HttpError(403, 'App sessions only.');
    const body = await readJson(request);
    const next = body?.next;
    if (next !== '/auth/google/calendar' && next !== '/auth/google/calendar?app=1') {
      throw new HttpError(400, 'Invalid destination.');
    }
    const code = randomToken(24);
    await insertAppCode(DB, code, user.id, 'web:' + next, Date.now() + 60 * 1000);
    return json({ url: `/auth/app/web?code=${code}` });
  }

  if (path === '/api/tokens' && method === 'GET') return json({ tokens: await listTokens(env, user) });
  if (path === '/api/tokens' && method === 'POST') return json(await createToken(env, user, await readJson(request), randomToken));
  if ((m = path.match(/^\/api\/tokens\/(\d+)$/)) && method === 'DELETE') return json(await revokeToken(env, user, Number(m[1])));

  if ((m = path.match(/^\/api\/passkeys\/([A-Za-z0-9_-]{8,})$/)) && method === 'DELETE') {
    return json(await deletePasskey(env, user, m[1]));
  }

  if (path === '/api/breaks' && method === 'POST') {
    const r = await saveBreak(env, user, await readJson(request));
    return json({ break: r.break, sessions: r.simulation.sessions.length });
  }

  if ((m = path.match(/^\/api\/breaks\/(\d+)$/)) && method === 'DELETE') {
    return json(await removeBreak(env, user, Number(m[1])));
  }

  const task = async (hw) => (await hydrateRows(env, user, [hw]))[0];

  if (path === '/api/homework' && method === 'POST') {
    return json(await task(await createHomework(env, user, await readJson(request), url.origin)));
  }

  if (path === '/api/homework/plan' && method === 'POST') return json(await planHomework(env, user, await readJson(request)));

  if (path === '/api/homework/move' && method === 'POST') {
    const r = await moveTasks(env, user, await readJson(request));
    return json({ homework: await hydrateRows(env, user, r.homework) });
  }

  if ((m = path.match(/^\/api\/homework\/(\d+)$/)) && method === 'PATCH') {
    return json(await task(await updateHomework(env, user, Number(m[1]), await readJson(request), url.origin)));
  }

  if ((m = path.match(/^\/api\/homework\/(\d+)\/complete$/)) && method === 'POST') {
    const { occurrence } = await readJson(request);
    return json(await task(await completeTask(env, user, Number(m[1]), url.origin, Date.now(), occurrence)));
  }
  if ((m = path.match(/^\/api\/homework\/(\d+)\/uncomplete$/)) && method === 'POST') {
    const { occurrence } = await readJson(request);
    return json(await task(await uncompleteTask(env, user, Number(m[1]), url.origin, occurrence)));
  }

  if ((m = path.match(/^\/api\/homework\/(\d+)$/)) && method === 'DELETE') return json(await deleteTask(env, user, Number(m[1])));

  if ((m = path.match(/^\/api\/homework\/(\d+)\/comments$/))) {
    if (method === 'GET') return json(await listComments(env, user, Number(m[1])));
    if (method === 'POST') return json(await createComment(env, user, Number(m[1]), await readJson(request)));
  }
  if ((m = path.match(/^\/api\/comments\/(\d+)$/))) {
    if (method === 'PATCH') return json(await updateComment(env, user, Number(m[1]), await readJson(request)));
    if (method === 'DELETE') return json(await deleteComment(env, user, Number(m[1])));
  }
  if ((m = path.match(/^\/api\/homework\/(\d+)\/reminders$/)) && method === 'POST') return json(await createReminder(env, user, Number(m[1]), await readJson(request)));
  if ((m = path.match(/^\/api\/reminders\/(\d+)$/))) {
    if (method === 'PATCH') return json(await updateReminder(env, user, Number(m[1]), await readJson(request)));
    if (method === 'DELETE') return json(await deleteReminder(env, user, Number(m[1])));
  }

  if (path === '/api/completions' && method === 'GET') {
    return json(await listCompletions(env, user, Math.max(Number(url.searchParams.get('since')) || 0, Date.now() - 400 * 86400000)));
  }

  // Projects, sections, labels and filters share one shape: POST, PATCH /<id>, DELETE /<id>, POST /reorder.
  if ((m = path.match(/^\/api\/(projects|sections|labels|filters)(?:\/(\d+|reorder))?$/))) {
    const [, table, rest] = m;
    if (!rest && method === 'POST') return json(await createRow(env, user, table, await readJson(request)));
    if (rest === 'reorder' && method === 'POST') return json(await reorderRows(env, user, table, await readJson(request)));
    if (rest && rest !== 'reorder' && method === 'PATCH') return json(await updateRow(env, user, table, Number(rest), await readJson(request)));
    if (rest && rest !== 'reorder' && method === 'DELETE') return json(await deleteRow(env, user, table, Number(rest)));
  }

  if (path === '/api/gcal/resync' && method === 'POST') {
    const rows = (await DB.prepare('SELECT id, name FROM courses WHERE user_id = ?').bind(user.id).all()).results;
    return json(await resync(env, user.id, Object.fromEntries(rows.map((c) => [c.id, c.name])), url.origin));
  }

  if (path === '/api/gcal/disconnect' && method === 'POST') {
    await disconnect(env, user.id);
    return json({ ok: true });
  }

  if (path === '/api/feed/rotate' && method === 'POST') {
    await DB.prepare('UPDATE users SET feed_token = ? WHERE id = ?').bind(randomToken(24), user.id).run();
    return json({ ok: true });
  }

  // Calendars: { kind: 'link', url, from?, name? } or { kind: 'file', ics, fileName?, name? }.
  if (path === '/api/calendars' && method === 'POST') {
    const body = await readJson(request, MAX_ICS_BYTES + 64 * 1024);
    if (body.kind === 'link') return json(await addLink(env, user, body));
    if (body.kind === 'file') return json(await addFile(env, user, body));
    throw new HttpError(400, 'kind must be "link" or "file".');
  }

  if ((m = path.match(/^\/api\/calendars\/(\d+)$/)) && method === 'PATCH') {
    return json(await updateCalendar(env, user, Number(m[1]), await readJson(request)));
  }

  if ((m = path.match(/^\/api\/calendars\/(\d+)$/)) && method === 'DELETE') {
    return json(await removeCalendar(env, user, Number(m[1])));
  }

  if ((m = path.match(/^\/api\/calendars\/(\d+)\/sync$/)) && method === 'POST') {
    return json(await syncCalendar(env, user, Number(m[1])));
  }

  if ((m = path.match(/^\/api\/calendars\/(\d+)\/file$/)) && method === 'POST') {
    return json(await replaceFile(env, user, Number(m[1]), await readJson(request, MAX_ICS_BYTES + 64 * 1024)));
  }

  if ((m = path.match(/^\/api\/attendance\/(\d+)$/)) && method === 'PATCH') {
    const body = await readJson(request);
    const sets = [], vals = [];
    if ('status' in body) {
      if (!validStatus(body.status)) throw new HttpError(400, 'Invalid status.');
      sets.push('status = ?'); vals.push(body.status);
    }
    if ('tag' in body) {
      if (body.tag !== null && !validTag(body.tag)) throw new HttpError(400, 'Invalid tag.');
      sets.push('tag = ?'); vals.push(body.tag);
    }
    if ('note' in body) {
      if (typeof body.note !== 'string' || body.note.length > 2000) throw new HttpError(400, 'Invalid note.');
      sets.push('note = ?'); vals.push(body.note);
    }
    if (!sets.length) throw new HttpError(400, 'Nothing to update.');
    const r = await DB.prepare(`UPDATE events SET ${sets.join(', ')} WHERE id = ? AND user_id = ?`)
      .bind(...vals, Number(m[1]), user.id).run();
    if (!r.meta.changes) throw new HttpError(404, 'Event not found.');
    return json({ ok: true });
  }

  if (path === '/api/attendance/bulk' && method === 'POST') {
    const body = await readJson(request, 512 * 1024);
    if (!Array.isArray(body.ids) || !body.ids.every(Number.isInteger)) throw new HttpError(400, 'Invalid ids.');
    if (!validStatus(body.status)) throw new HttpError(400, 'Invalid status.');
    const r = await DB.prepare(`UPDATE events SET status = ?1 WHERE user_id = ?2
      AND id IN (SELECT value FROM json_each(?3))`).bind(body.status, user.id, JSON.stringify(body.ids)).run();
    return json({ updated: r.meta.changes });
  }

  if ((m = path.match(/^\/api\/courses\/(\d+)$/)) && method === 'PATCH') {
    const body = await readJson(request);
    const sets = [], vals = [];
    if ('tracked' in body) { sets.push('tracked = ?'); vals.push(body.tracked ? 1 : 0); }
    if ('recording_url' in body) {
      await setRecordingUrl(env, user, Number(m[1]), body.recording_url);
      if (sets.length === 0 && !('tag' in body)) return json({ ok: true });
    }
    if ('tag' in body) {
      if (!validTag(body.tag)) throw new HttpError(400, 'Invalid tag.');
      sets.push('tag = ?'); vals.push(body.tag);
    }
    if (!sets.length) throw new HttpError(400, 'Nothing to update.');
    const r = await DB.prepare(`UPDATE courses SET ${sets.join(', ')} WHERE id = ? AND user_id = ?`)
      .bind(...vals, Number(m[1]), user.id).run();
    if (!r.meta.changes) throw new HttpError(404, 'Course not found.');
    return json({ ok: true });
  }

  if (path === '/api/courses/tracked' && method === 'POST') {
    // { updates: [{ id, tracked }] } — used by the group picker.
    const body = await readJson(request);
    if (!Array.isArray(body.updates)) throw new HttpError(400, 'Invalid updates.');
    const updates = body.updates.map((u) => ({ id: Number(u.id), tracked: u.tracked ? 1 : 0 }));
    await DB.prepare(`UPDATE courses SET tracked = (
        SELECT json_extract(value, '$.tracked') FROM json_each(?2) WHERE json_extract(value, '$.id') = courses.id)
      WHERE user_id = ?1 AND id IN (SELECT json_extract(value, '$.id') FROM json_each(?2))`)
      .bind(user.id, JSON.stringify(updates)).run();
    return json({ ok: true });
  }

  if (path === '/api/settings' && method === 'PATCH') {
    const body = await readJson(request);
    const threshold = Number(body.threshold);
    if (!(threshold > 0 && threshold <= 1)) throw new HttpError(400, 'Threshold must be between 0 and 100%.');
    if (!['sessions', 'hours'].includes(body.unit)) throw new HttpError(400, 'Invalid unit.');
    await DB.prepare('UPDATE users SET threshold = ?, unit = ? WHERE id = ?').bind(threshold, body.unit, user.id).run();
    return json({ ok: true });
  }

  if (path === '/api/account' && method === 'DELETE') {
    const body = await readJson(request);
    if (body.confirm !== 'DELETE') throw new HttpError(400, 'Type DELETE to confirm.');
    await deleteAccount(env, user);
    return new Response(JSON.stringify({ ok: true }), {
      headers: { 'Content-Type': 'application/json', 'Set-Cookie': cookie(SESSION_COOKIE, '', 0, url.protocol === 'https:') },
    });
  }

  if (path === '/api/data' && method === 'DELETE') {
    // Calendars go too: otherwise the next scheduled refresh would bring the sessions straight back.
    await wipeCalendars(env, user.id);
    return json({ ok: true });
  }

  throw new HttpError(404, 'Not found.');
}

// ---------------------------------------------------------------- entry

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // One address for everyone: sessions, OAuth callbacks and feed links all live on the canonical host.
    const canonical = env.CANONICAL_HOST;
    if (canonical && url.hostname !== canonical && url.hostname.endsWith('.workers.dev')) {
      url.hostname = canonical;
      return Response.redirect(url.toString(), request.method === 'GET' || request.method === 'HEAD' ? 301 : 308);
    }
    try {
      // Reject cross-site state-changing requests.
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        const origin = request.headers.get('Origin');
        if (origin && origin !== url.origin) throw new HttpError(403, 'Cross-origin request rejected.');
      }
      if (url.pathname === '/.well-known/assetlinks.json') return assetLinks(env);
      if (url.pathname.startsWith('/auth/')) return await handleAuth(request, env, url);
      if (url.pathname.startsWith('/feed/')) return await handleFeed(env, url);
      if (url.pathname.startsWith('/api/')) {
        const auth = request.headers.get('Authorization') || '';
        const appMatch = /^Bearer\s+(a80s_[A-Za-z0-9_-]{20,})$/.exec(auth);
        if (appMatch) {
          const user = await currentUser(request, env);
          if (!user) throw new HttpError(401, 'Not signed in.');
          if (url.pathname === '/api/me') return json({ user: { email: user.email, name: user.name, picture: user.picture } });
          return await handleApi(request, env, url, user);
        }
        const bearer = request.headers.get('Authorization');
        if (bearer) {
          const user = await userFromToken(env, bearer);
          if (!user) throw new HttpError(401, 'Invalid or revoked token.');
          if (!tokenAllows(user.tokenScope, request.method, url.pathname)) {
            throw new HttpError(403, user.tokenScope === 'read' && request.method !== 'GET'
              ? 'This token can only read. Create a read and write token in Settings.' : 'Tokens can\'t use this endpoint.');
          }
          return await handleApi(request, env, url, user);
        }
        const user = await currentUser(request, env);
        if (url.pathname === '/api/me') return json({ user: user && { email: user.email, name: user.name, picture: user.picture } });
        if (!user) throw new HttpError(401, 'Not signed in.');
        return await handleApi(request, env, url, user);
      }
      return env.ASSETS.fetch(request);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, { status: e.status });
      console.error(e);
      return json({ error: 'Internal error.' }, { status: 500 });
    }
  },

  // Cron (every minute): refresh the Zeus link that is most overdue, if any, and send the notifications due.
  async scheduled(controller, env, ctx) {
    ctx.waitUntil(syncDueCalendars(env).then((r) => r.due && console.log('calendar refresh', r)));
    ctx.waitUntil(sendDueNotifications(env, controller.scheduledTime).then((r) => r.sent && console.log('notifications', r)));
  },
};
