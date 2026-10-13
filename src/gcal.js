// Google Calendar push sync: homework is mirrored into a calendar the app creates in the
// user's Google account (scope calendar.app.created, so it can't see any other calendar).
import { buildGcalEvent } from './homework.js';

export const GCAL_SCOPE = 'https://www.googleapis.com/auth/calendar.app.created';
const API = 'https://www.googleapis.com/calendar/v3';
const CALENDAR_NAME = 'Homework (Attendance80)';
export const RESYNC_BATCH = 30; // stays well under the Workers free-plan subrequest limit (50)

// ---------------------------------------------------------------- token encryption

async function aesKey(env) {
  if (!env.TOKEN_KEY) throw new Error('TOKEN_KEY is not configured.');
  const raw = Uint8Array.from(atob(env.TOKEN_KEY), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

export async function encrypt(env, text) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await aesKey(env), new TextEncoder().encode(text)));
  const all = new Uint8Array(iv.length + ct.length);
  all.set(iv); all.set(ct, iv.length);
  return btoa(String.fromCharCode(...all));
}

export async function decrypt(env, b64) {
  const all = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: all.slice(0, 12) }, await aesKey(env), all.slice(12));
  return new TextDecoder().decode(pt);
}

// ---------------------------------------------------------------- API plumbing

class GoogleError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

export const getLink = (env, userId) =>
  env.DB.prepare('SELECT * FROM gcal_links WHERE user_id = ?').bind(userId).first();

async function accessToken(env, link) {
  if (link.access_token && link.access_expires > Date.now() + 60000) return link.access_token;
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: env.GOOGLE_CLIENT_ID, client_secret: env.GOOGLE_CLIENT_SECRET,
      refresh_token: await decrypt(env, link.refresh_token), grant_type: 'refresh_token',
    }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg = data.error === 'invalid_grant'
      ? 'Google access was revoked or expired. Reconnect Google Calendar.'
      : `Google token refresh failed (${res.status}).`;
    throw new GoogleError(401, msg);
  }
  link.access_token = data.access_token;
  link.access_expires = Date.now() + data.expires_in * 1000;
  await env.DB.prepare('UPDATE gcal_links SET access_token = ?, access_expires = ? WHERE user_id = ?')
    .bind(link.access_token, link.access_expires, link.user_id).run();
  return link.access_token;
}

async function gapi(env, link, method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: { Authorization: `Bearer ${await accessToken(env, link)}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return null;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new GoogleError(res.status, data.error?.message || `Google Calendar error ${res.status}`);
  return data;
}

async function ensureCalendar(env, link, tz) {
  if (link.calendar_id) return link.calendar_id;
  const cal = await gapi(env, link, 'POST', '/calendars', { summary: CALENDAR_NAME, timeZone: tz || 'Europe/Paris' });
  link.calendar_id = cal.id;
  await env.DB.prepare('UPDATE gcal_links SET calendar_id = ? WHERE user_id = ?').bind(cal.id, link.user_id).run();
  return cal.id;
}

async function recordLinkError(env, link, message) {
  await env.DB.prepare('UPDATE gcal_links SET last_error = ? WHERE user_id = ?').bind(message, link.user_id).run();
}

// ---------------------------------------------------------------- sync operations

async function pushEvent(env, link, hw, courseName, appUrl) {
  const event = buildGcalEvent(hw, courseName, appUrl);
  const calId = encodeURIComponent(await ensureCalendar(env, link, hw.tz));
  if (hw.gcal_event_id) {
    try {
      return (await gapi(env, link, 'PUT', `/calendars/${calId}/events/${encodeURIComponent(hw.gcal_event_id)}`, event)).id;
    } catch (e) {
      if (e.status !== 404 && e.status !== 410) throw e; // event was deleted in Google: recreate it
    }
  }
  return (await gapi(env, link, 'POST', `/calendars/${calId}/events`, event)).id;
}

// Creates/updates the Google event for one homework row and records the outcome on the row.
// Never throws: sync problems are stored and shown in the UI rather than failing the save.
export async function syncHomework(env, userId, hw, courseName, appUrl, link) {
  link ??= await getLink(env, userId);
  if (!link) return hw;
  let eventId = hw.gcal_event_id, error = null;
  try {
    try {
      eventId = await pushEvent(env, link, hw, courseName, appUrl);
    } catch (e) {
      if (e.status !== 404 || !link.calendar_id) throw e;
      // The whole calendar was deleted in Google: make a new one and try once more.
      link.calendar_id = null;
      await env.DB.prepare('UPDATE gcal_links SET calendar_id = NULL WHERE user_id = ?').bind(userId).run();
      eventId = await pushEvent(env, link, { ...hw, gcal_event_id: null }, courseName, appUrl);
    }
    if (link.last_error) { link.last_error = null; await recordLinkError(env, link, null); }
  } catch (e) {
    console.error('gcal sync', e);
    error = e.message || 'Google Calendar sync failed.';
    if (e.status === 401 || e.status === 403) await recordLinkError(env, link, error);
  }
  await env.DB.prepare('UPDATE homework SET gcal_event_id = ?, sync_error = ? WHERE id = ? AND user_id = ?')
    .bind(eventId, error, hw.id, userId).run();
  return { ...hw, gcal_event_id: eventId, sync_error: error };
}

export async function deleteGcalEvent(env, userId, eventId) {
  const link = await getLink(env, userId);
  if (!link?.calendar_id || !eventId) return;
  try {
    await gapi(env, link, 'DELETE', `/calendars/${encodeURIComponent(link.calendar_id)}/events/${encodeURIComponent(eventId)}`);
  } catch (e) {
    if (e.status !== 404 && e.status !== 410) console.error('gcal delete', e);
  }
}

// Pushes homework that has never been synced (or failed last time), a batch at a time.
// Returns how many are still pending so the client can call again.
export async function resync(env, userId, courseNames, appUrl) {
  const link = await getLink(env, userId);
  if (!link) return { synced: 0, remaining: 0 };
  const pendingSql = `FROM homework WHERE user_id = ? AND (gcal_event_id IS NULL OR sync_error IS NOT NULL)`;
  const rows = (await env.DB.prepare(`SELECT * ${pendingSql} ORDER BY due_at DESC LIMIT ?`).bind(userId, RESYNC_BATCH).all()).results;
  let synced = 0, failed = 0;
  for (const hw of rows) {
    const r = await syncHomework(env, userId, hw, courseNames[hw.course_id], appUrl, link);
    if (r.sync_error) failed++; else synced++;
    if (r.sync_error && link.last_error) break; // auth problem: stop hammering Google
  }
  const { n } = await env.DB.prepare(`SELECT COUNT(*) AS n ${pendingSql}`).bind(userId).first();
  // Rows that just failed are still "pending"; don't make the client loop forever on them.
  return { synced, failed, remaining: failed ? 0 : n };
}

export async function disconnect(env, userId) {
  const link = await getLink(env, userId);
  if (!link) return;
  try {
    await fetch('https://oauth2.googleapis.com/revoke', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ token: await decrypt(env, link.refresh_token) }),
    });
  } catch (e) {
    console.error('gcal revoke', e);
  }
  await env.DB.batch([
    env.DB.prepare('DELETE FROM gcal_links WHERE user_id = ?').bind(userId),
    env.DB.prepare('UPDATE homework SET gcal_event_id = NULL, sync_error = NULL WHERE user_id = ?').bind(userId),
  ]);
}
