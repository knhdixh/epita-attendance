import { parseICS } from './ics.js';
import { splitGroup, TAGS, STATUSES } from '../public/stats.js';
import { buildFeed } from './homework.js';
import { GCAL_SCOPE, encrypt, deleteGcalEvent, resync, disconnect } from './gcal.js';
import { HttpError } from './http.js';
import { HOMEWORK_COLUMNS, createHomework, updateHomework } from './homework-store.js';
import { chat, usage as aiUsage } from './assistant.js';
import { saveAiKey, deleteAiKey } from './llm.js';
import { deleteAccount } from './account.js';
import { saveBreak, removeBreak } from './breaks-store.js';
import { handlePasskeyAuth, listPasskeys, deletePasskey, isPasskeyOnly } from './passkeys.js';

const SESSION_COOKIE = 'sid';
const STATE_COOKIE = 'oauth_state';
const SESSION_TTL_MS = 30 * 24 * 3600 * 1000;
const MAX_ICS_BYTES = 5 * 1024 * 1024;
const NON_CLASS = /\b(vacances|holidays?|jour f[ée]ri[ée]|f[ée]ri[ée]|cong[ée]s?)\b/i;

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

export const courseKey = (summary) => summary.toLowerCase().replace(/\s+/g, ' ').trim();

// ---------------------------------------------------------------- auth

async function currentUser(request, env) {
  const sid = parseCookies(request.headers.get('Cookie'))[SESSION_COOKIE];
  if (!sid) return null;
  const user = await env.DB.prepare(`SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id
    WHERE s.id = ? AND s.expires_at > ?`).bind(sid, Date.now()).first();
  return user ? { ...user, sessionId: sid } : null;
}

async function signIn(env, profile, secure) {
  const user = await env.DB.prepare(`INSERT INTO users (google_sub, email, name, picture, created_at)
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT (google_sub) DO UPDATE SET email = excluded.email, name = excluded.name, picture = excluded.picture
    RETURNING id`).bind(profile.sub, profile.email ?? null, profile.name ?? null, profile.picture ?? null, Date.now()).first();
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

const googleEnabled = (env) => Boolean(env.GOOGLE_CLIENT_ID && env.GOOGLE_CLIENT_SECRET);
const devLoginEnabled = (env) => env.DEV_LOGIN === '1' || env.DEV_LOGIN === 'true';

async function handleAuth(request, env, url) {
  const secure = url.protocol === 'https:';
  const redirectUri = `${url.origin}/auth/google/callback`;

  if (url.pathname === '/auth/config') {
    return json({ google: googleEnabled(env), dev: devLoginEnabled(env), passkeys: true, signup: !(env.ALLOWED_DOMAIN || '').trim() });
  }

  if (url.pathname.startsWith('/auth/passkey/')) {
    return handlePasskeyAuth(request, env, url, { secure, cookie, parseCookies, randomToken, currentUser, startSession });
  }

  // Both sign-in and "connect Google Calendar" use this flow; the state cookie records which.
  const startOAuth = (purpose, params) => {
    const state = randomToken(16);
    const target = new URL('https://accounts.google.com/o/oauth2/v2/auth');
    target.search = new URLSearchParams({
      client_id: env.GOOGLE_CLIENT_ID, redirect_uri: redirectUri, response_type: 'code', state, ...params,
    });
    return redirect(target.toString(), [cookie(STATE_COOKIE, `${purpose}.${state}`, 10 * 60 * 1000, secure)]);
  };

  if (url.pathname === '/auth/google') {
    if (!googleEnabled(env)) return new Response('Google sign-in is not configured.', { status: 503 });
    return startOAuth('login', { scope: 'openid email profile', prompt: 'select_account' });
  }

  if (url.pathname === '/auth/google/calendar') {
    const user = await currentUser(request, env);
    if (!user) return redirect('/');
    if (!googleEnabled(env) || !env.TOKEN_KEY) return redirect('/?gcal_error=config#settings');
    return startOAuth('calendar', {
      scope: `openid email ${GCAL_SCOPE}`, access_type: 'offline', prompt: 'consent',
      include_granted_scopes: 'true', login_hint: user.email || '',
    });
  }

  if (url.pathname === '/auth/google/callback') {
    const clearState = cookie(STATE_COOKIE, '', 0, secure);
    const [purpose, expected] = (parseCookies(request.headers.get('Cookie'))[STATE_COOKIE] || '').split('.');
    const fail = (code) => redirect(purpose === 'calendar' ? `/?gcal_error=${code}#settings` : `/?auth_error=${code}`, [clearState]);
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

      if (purpose === 'calendar') {
        const user = await currentUser(request, env);
        if (!user) return redirect('/', [clearState]);
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
        return redirect('/?gcal=connected#settings', [clearState]);
      }

      const domain = (env.ALLOWED_DOMAIN || '').trim().toLowerCase();
      if (!info.email_verified || (domain && !String(info.email).toLowerCase().endsWith('@' + domain))) {
        return fail('domain');
      }
      return redirect('/', [clearState, await signIn(env, info, secure)]);
    } catch (e) {
      console.error(e);
      return fail('google');
    }
  }

  if (url.pathname === '/auth/dev' && devLoginEnabled(env)) {
    const email = url.searchParams.get('email') || 'dev@example.com';
    return redirect('/', [await signIn(env, { sub: 'dev:' + email, email, name: email.split('@')[0] }, secure)]);
  }

  if (url.pathname === '/auth/logout' && request.method === 'POST') {
    const sid = parseCookies(request.headers.get('Cookie'))[SESSION_COOKIE];
    if (sid) await env.DB.prepare('DELETE FROM sessions WHERE id = ?').bind(sid).run();
    return new Response(JSON.stringify({ ok: true }), {
      headers: { 'Content-Type': 'application/json', 'Set-Cookie': cookie(SESSION_COOKIE, '', 0, secure) },
    });
  }

  return new Response('Not found', { status: 404 });
}

// ---------------------------------------------------------------- import

// Imports (or re-imports) an ICS file. Events are matched by UID so attendance marks
// survive uploading an updated calendar. Events missing from the new file are removed
// unless the user already marked, tagged or annotated them.
async function importIcs(env, userId, text) {
  const { events, recurring } = parseICS(text);
  const seen = new Map();
  let cancelled = 0;
  for (const ev of events) {
    if (ev.cancelled) { cancelled++; continue; }
    if (!seen.has(ev.uid)) seen.set(ev.uid, ev);
  }
  if (!seen.size) throw new HttpError(400, 'No events found in this file.');

  const courses = new Map();
  const rows = [];
  for (const ev of seen.values()) {
    const key = courseKey(ev.summary);
    if (!courses.has(key)) {
      // Holidays and group-specific sessions (other groups) start untracked.
      const tracked = NON_CLASS.test(ev.summary) || splitGroup(ev.summary) ? 0 : 1;
      courses.set(key, { key, name: ev.summary, tracked });
    }
    rows.push({ key, uid: ev.uid, summary: ev.summary, location: ev.location,
      description: ev.description, start: ev.start, end: ev.end });
  }

  const existing = await env.DB.prepare('SELECT uid FROM events WHERE user_id = ?').bind(userId).all();
  const existingUids = new Set(existing.results.map((r) => r.uid));
  const importStamp = Date.now();

  // json_each keeps this to a handful of queries regardless of calendar size.
  const results = await env.DB.batch([
    env.DB.prepare(`INSERT INTO courses (user_id, key, name, tracked, tag)
      SELECT ?1, json_extract(value, '$.key'), json_extract(value, '$.name'), json_extract(value, '$.tracked'), 'normal'
      FROM json_each(?2) WHERE true
      ON CONFLICT (user_id, key) DO NOTHING`).bind(userId, JSON.stringify([...courses.values()])),
    env.DB.prepare(`INSERT INTO events (user_id, course_id, uid, summary, location, description, start_at, end_at, seen_at)
      SELECT ?1, c.id, json_extract(e.value, '$.uid'), json_extract(e.value, '$.summary'),
        json_extract(e.value, '$.location'), json_extract(e.value, '$.description'),
        json_extract(e.value, '$.start'), json_extract(e.value, '$.end'), ?3
      FROM json_each(?2) e JOIN courses c ON c.user_id = ?1 AND c.key = json_extract(e.value, '$.key')
      WHERE true
      ON CONFLICT (user_id, uid) DO UPDATE SET course_id = excluded.course_id, summary = excluded.summary,
        location = excluded.location, description = excluded.description, start_at = excluded.start_at,
        end_at = excluded.end_at, seen_at = excluded.seen_at`).bind(userId, JSON.stringify(rows), importStamp),
    env.DB.prepare(`DELETE FROM events WHERE user_id = ? AND seen_at <> ?
      AND status IS NULL AND tag IS NULL AND note = ''`).bind(userId, importStamp),
    env.DB.prepare(`DELETE FROM courses WHERE user_id = ?
      AND NOT EXISTS (SELECT 1 FROM events WHERE events.course_id = courses.id)`).bind(userId),
  ]);

  const added = rows.filter((r) => !existingUids.has(r.uid)).length;
  return {
    total: rows.length, added, updated: rows.length - added,
    removed: results[2].meta.changes, cancelled, recurringIgnored: recurring,
  };
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
  const m = url.pathname.match(/^\/feed\/([A-Za-z0-9_-]{20,})\.ics$/);
  const user = m && await env.DB.prepare('SELECT id FROM users WHERE feed_token = ?').bind(m[1]).first();
  if (!user) return new Response('Not found', { status: 404 });
  const [homework, courses] = await env.DB.batch([
    // Upcoming homework plus the last 60 days, so the feed stays small.
    env.DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE user_id = ? AND due_at > ? ORDER BY due_at`)
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

const validTag = (t) => Object.hasOwn(TAGS, t);
const validStatus = (s) => s === null || STATUSES.includes(s);

async function handleApi(request, env, url, user) {
  const { method } = request;
  const path = url.pathname;
  const DB = env.DB;
  let m;

  if (path === '/api/data' && method === 'GET') {
    const [courses, events, homework, link, breaks] = await DB.batch([
      DB.prepare('SELECT id, name, tracked, tag FROM courses WHERE user_id = ? ORDER BY name').bind(user.id),
      DB.prepare(`SELECT id, course_id, summary, location, description, start_at, end_at, status, tag, note
        FROM events WHERE user_id = ? ORDER BY start_at`).bind(user.id),
      DB.prepare(`SELECT ${HOMEWORK_COLUMNS} FROM homework WHERE user_id = ? ORDER BY due_at`).bind(user.id),
      DB.prepare('SELECT email, calendar_id, last_error FROM gcal_links WHERE user_id = ?').bind(user.id),
      DB.prepare('SELECT id, label, start_date, end_date, changes, created_at FROM breaks WHERE user_id = ? ORDER BY start_date').bind(user.id),
    ]);
    const gcal = link.results[0];
    return json({
      user: { email: user.email, name: user.name, picture: user.picture, threshold: user.threshold, unit: user.unit },
      account: { google: !isPasskeyOnly(user), passkeys: await listPasskeys(env, user) },
      courses: courses.results,
      events: events.results,
      homework: homework.results,
      breaks: breaks.results.map((b) => ({ ...b, sessions: JSON.parse(b.changes).length, changes: undefined })),
      integrations: {
        feedUrl: `${url.origin}/feed/${await feedToken(env, user)}.ics`,
        gcalAvailable: googleEnabled(env) && Boolean(env.TOKEN_KEY),
        gcal: gcal ? { email: gcal.email, calendarId: gcal.calendar_id, error: gcal.last_error } : null,
      },
      assistant: env.AI ? await aiUsage(env, user) : null,
    });
  }

  if (path === '/api/ai-key' && method === 'PUT') {
    // Tests the key against the provider before saving it (encrypted).
    return json({ key: await saveAiKey(env, user, await readJson(request)), usage: await aiUsage(env, user) });
  }

  if (path === '/api/ai-key' && method === 'DELETE') {
    await deleteAiKey(env, user);
    return json({ usage: await aiUsage(env, user) });
  }

  if (path === '/api/assistant/chat' && method === 'POST') {
    return json(await chat(env, user, await readJson(request, 128 * 1024), url.origin));
  }

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

  if (path === '/api/homework' && method === 'POST') {
    return json(await createHomework(env, user, await readJson(request), url.origin));
  }

  if ((m = path.match(/^\/api\/homework\/(\d+)$/)) && method === 'PATCH') {
    return json(await updateHomework(env, user, Number(m[1]), await readJson(request), url.origin));
  }

  if ((m = path.match(/^\/api\/homework\/(\d+)$/)) && method === 'DELETE') {
    const hw = await DB.prepare('DELETE FROM homework WHERE id = ? AND user_id = ? RETURNING gcal_event_id')
      .bind(Number(m[1]), user.id).first();
    if (!hw) throw new HttpError(404, 'Homework not found.');
    await deleteGcalEvent(env, user.id, hw.gcal_event_id);
    return json({ ok: true });
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

  if (path === '/api/import' && method === 'POST') {
    const body = await readJson(request, MAX_ICS_BYTES);
    if (typeof body.ics !== 'string') throw new HttpError(400, 'Missing "ics" text.');
    return json(await importIcs(env, user.id, body.ics));
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
    await DB.batch([
      DB.prepare('DELETE FROM breaks WHERE user_id = ?').bind(user.id),
      DB.prepare('DELETE FROM events WHERE user_id = ?').bind(user.id),
      DB.prepare('DELETE FROM courses WHERE user_id = ?').bind(user.id),
    ]);
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
      if (url.pathname.startsWith('/auth/')) return await handleAuth(request, env, url);
      if (url.pathname.startsWith('/feed/')) return await handleFeed(env, url);
      if (url.pathname.startsWith('/api/')) {
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
};
