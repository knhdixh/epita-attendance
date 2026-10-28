// Notifications by Web Push (src/push.js): class reminders, habit reminders and the end of a focus-timer phase.
// They reach every browser and Android app install the user turned them on in, even when the page is closed.
// The cron (every minute) collects what is due in the coming minute and sends each at its time.
import { HttpError } from './http.js';
import { isValidTimeZone } from './homework.js';
import { sendPush, pushEnabled } from './push.js';
import { reminderDay } from '../public/habits.js';

const MIN = 60000;
export const CLASS_LEADS = [0, 5, 10, 15, 20, 30, 45, 60, 90, 120];
export const DEFAULT_NOTIFY = { classes: true, classLead: 10, habits: true, focus: true, tz: 'Europe/Paris' };
const LATE = 5 * MIN;          // a reminder whose time passed less than this ago (a missed cron run) still goes
const FOCUS_GRACE = 2000;      // an open page moves the timer on at the end of the phase; give it a moment first
const MAX_SUBS = 20;

export function cleanNotify(raw) {
  const s = { ...DEFAULT_NOTIFY };
  if (!raw || typeof raw !== 'object') return s;
  for (const k of ['classes', 'habits', 'focus']) if (typeof raw[k] === 'boolean') s[k] = raw[k];
  if (CLASS_LEADS.includes(raw.classLead)) s.classLead = raw.classLead;
  if (typeof raw.tz === 'string' && isValidTimeZone(raw.tz)) s.tz = raw.tz;
  return s;
}

export const notifySettings = (user) => {
  try { return cleanNotify(JSON.parse(user.notify_settings || 'null')); } catch { return cleanNotify(null); }
};

export async function saveNotifySettings(env, user, body) {
  const s = cleanNotify({ ...notifySettings(user), ...body });
  await env.DB.prepare('UPDATE users SET notify_settings = ? WHERE id = ?').bind(JSON.stringify(s), user.id).run();
  return s;
}

// ---------------------------------------------------------------- subscriptions

const KEY_RE = /^[A-Za-z0-9_-]+$/;

export async function subscribe(env, user, b, now = Date.now()) {
  let endpoint;
  try { endpoint = new URL(b.endpoint); } catch { throw new HttpError(400, 'Invalid endpoint.'); }
  const { p256dh, auth } = b.keys || {};
  if (endpoint.protocol !== 'https:' || b.endpoint.length > 1000) throw new HttpError(400, 'Invalid endpoint.');
  if (!(typeof p256dh === 'string' && KEY_RE.test(p256dh) && p256dh.length >= 80 && p256dh.length <= 100)
    || !(typeof auth === 'string' && KEY_RE.test(auth) && auth.length >= 16 && auth.length <= 32)) throw new HttpError(400, 'Invalid keys.');
  const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM push_subscriptions WHERE user_id = ? AND endpoint != ?').bind(user.id, b.endpoint).first();
  if (n >= MAX_SUBS) throw new HttpError(400, 'Too many devices. Turn notifications off on one you no longer use.');
  // An endpoint belongs to one browser: if someone else signs in there, it moves to them.
  await env.DB.prepare(`INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, app, created_at) VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT (endpoint) DO UPDATE SET user_id = excluded.user_id, p256dh = excluded.p256dh, auth = excluded.auth, app = excluded.app`)
    .bind(user.id, b.endpoint, p256dh, auth, b.app ? 1 : 0, now).run();
  return { ok: true, devices: await deviceCount(env, user) };
}

export async function unsubscribe(env, user, b) {
  await env.DB.prepare('DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ?').bind(user.id, String(b.endpoint || '')).run();
  return { ok: true, devices: await deviceCount(env, user) };
}

export const deviceCount = async (env, user) =>
  (await env.DB.prepare('SELECT COUNT(*) AS n FROM push_subscriptions WHERE user_id = ?').bind(user.id).first()).n;

// The page's focus timer: { at, title, body } while a phase runs, { at: null } otherwise.
export async function setFocusPush(env, user, b, now = Date.now()) {
  if (b.at === null) {
    await env.DB.prepare('UPDATE users SET focus_push_at = NULL, focus_push = NULL WHERE id = ?').bind(user.id).run();
    return { ok: true };
  }
  const str = (v) => (typeof v === 'string' ? v.slice(0, 160) : '');
  if (!(Number.isSafeInteger(b.at) && b.at > now - MIN && b.at < now + 5 * 3600000) || !str(b.title)) throw new HttpError(400, 'Invalid focus notification.');
  await env.DB.prepare('UPDATE users SET focus_push_at = ?, focus_push = ? WHERE id = ?')
    .bind(b.at, JSON.stringify({ title: str(b.title), body: str(b.body) }), user.id).run();
  return { ok: true };
}

// ---------------------------------------------------------------- sending

async function sendToUser(env, subs, message, opts) {
  let sent = 0;
  for (const sub of subs) {
    const r = await sendPush(env, sub, message, opts);
    if (r === 'gone') await env.DB.prepare('DELETE FROM push_subscriptions WHERE id = ?').bind(sub.id).run();
    if (r === 'ok') sent++;
  }
  return sent;
}

export async function sendTest(env, user, opts = {}) {
  if (!pushEnabled(env)) throw new HttpError(503, 'Notifications aren\'t set up on this server.');
  const subs = (await env.DB.prepare('SELECT * FROM push_subscriptions WHERE user_id = ?').bind(user.id).all()).results;
  if (!subs.length) throw new HttpError(400, 'Turn on notifications on this device first.');
  const sent = await sendToUser(env, subs, { title: 'Notifications work', body: 'This is how class, habit and focus reminders will look.', tag: 'a80-test', url: '/#settings' }, opts);
  return { sent, devices: subs.length };
}

const timeIn = (ms, tz) => new Date(ms).toLocaleTimeString('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit' });

function classMessage(e, now, tz) {
  const mins = Math.round((e.start_at - now) / MIN);
  const name = e.course_name || e.summary;
  return {
    title: mins <= 0 ? `${name} starts now` : `${name} in ${mins} min`,
    body: [`${timeIn(e.start_at, tz)}–${timeIn(e.end_at, tz)}`, e.location].filter(Boolean).join(' · '),
    tag: `a80-class-${e.id}`, url: '/#today',
  };
}

// Everything due between now (minus LATE, for missed runs) and the next run, in the order to send it.
export async function collectDue(env, now) {
  const DB = env.DB;
  const until = now + MIN;
  const [users, subs, focus] = await DB.batch([
    DB.prepare('SELECT id, notify_settings FROM users WHERE id IN (SELECT user_id FROM push_subscriptions)'),
    DB.prepare('SELECT id, user_id, endpoint, p256dh, auth, app FROM push_subscriptions'),
    DB.prepare('SELECT id, focus_push_at, focus_push FROM users WHERE focus_push_at IS NOT NULL AND focus_push_at < ?').bind(until),
  ]);
  const settings = new Map(users.results.map((u) => [u.id, notifySettings(u)]));
  const subsOf = new Map();
  for (const s of subs.results) {
    if (!subsOf.has(s.user_id)) subsOf.set(s.user_id, []);
    subsOf.get(s.user_id).push(s);
  }
  const due = [];

  // Focus: phases that ended more than LATE ago are dropped (the page will have caught up by itself).
  for (const u of focus.results) {
    const s = settings.get(u.id);
    const to = (subsOf.get(u.id) || []).filter((x) => !x.app);
    if (u.focus_push_at < now - LATE || !s?.focus || !to.length) {
      await DB.prepare('UPDATE users SET focus_push_at = NULL, focus_push = NULL WHERE id = ? AND focus_push_at = ?').bind(u.id, u.focus_push_at).run();
      continue;
    }
    due.push({ kind: 'focus', at: u.focus_push_at + FOCUS_GRACE, user: u.id, stamp: u.focus_push_at, text: u.focus_push, subs: to });
  }

  // Classes: tracked courses only, and not the ones you plan to skip or are excused from.
  const classUsers = [...settings].filter(([, s]) => s.classes).map(([id]) => id);
  if (classUsers.length) {
    const maxLead = Math.max(...CLASS_LEADS) * MIN;
    const rows = (await DB.prepare(`SELECT e.id, e.user_id, e.summary, e.location, e.start_at, e.end_at, c.name AS course_name
      FROM events e JOIN courses c ON c.id = e.course_id
      WHERE e.user_id IN (SELECT value FROM json_each(?1)) AND e.start_at > ?2 AND e.start_at <= ?3
        AND c.tracked = 1 AND (e.status IS NULL OR e.status = 'present')`)
      .bind(JSON.stringify(classUsers), now - MIN, until + maxLead).all()).results;
    for (const e of rows) {
      const s = settings.get(e.user_id);
      const at = e.start_at - s.classLead * MIN;
      if (at >= now - LATE && at < until) due.push({ kind: 'class', at, user: e.user_id, key: `class:${e.id}:${e.start_at}`, event: e, tz: s.tz, subs: subsOf.get(e.user_id) });
    }
  }

  // Habits with a reminder, not done yet today.
  const habitUsers = [...settings].filter(([, s]) => s.habits).map(([id]) => id);
  if (habitUsers.length) {
    const rows = (await DB.prepare(`SELECT h.id, h.user_id, h.name, h.days, h.remind_at, h.tz, h.created_at,
        (SELECT json_group_array(day) FROM habit_checks WHERE habit_id = h.id AND day >= ?2) AS checks
      FROM habits h WHERE h.remind_at IS NOT NULL AND h.user_id IN (SELECT value FROM json_each(?1))`)
      .bind(JSON.stringify(habitUsers), new Date(now - 3 * 86400000).toISOString().slice(0, 10)).all()).results;
    for (const r of rows) {
      // Reminders are on whole minutes and the cron runs at the start of each one, so "now" is the right time.
      // The key has created_at because ids are reused after a delete.
      const h = { ...r, checks: JSON.parse(r.checks) };
      const day = reminderDay(h, now, LATE);
      if (day) due.push({ kind: 'habit', at: now, user: h.user_id, key: `habit:${h.id}:${h.created_at}:${day}`, habit: h, day, subs: subsOf.get(h.user_id) });
    }
  }
  return due.sort((a, b) => a.at - b.at);
}

function habitMessage(h, day) {
  return {
    title: h.name, body: 'Time for your habit. Tap Done when it\'s done.', tag: `a80-habit-${h.id}`, url: '/#habits',
    habit: { id: h.id, day }, actions: [{ action: 'habit-done', title: 'Done' }],
  };
}

// Run by the cron. `sleep` and `fetcher` are replaced in the tests.
export async function sendDueNotifications(env, now = Date.now(), { sleep = (ms) => new Promise((r) => setTimeout(r, ms)), clock = Date.now, fetcher } = {}) {
  if (!pushEnabled(env)) return { sent: 0 };
  const due = await collectDue(env, now);
  let sent = 0;
  for (const item of due) {
    const wait = item.at - clock();
    if (wait > 0 && wait <= MIN + FOCUS_GRACE) await sleep(wait);
    let message;
    if (item.kind === 'focus') {
      // Claimed at send time: if an open page moved the timer on meanwhile, this is gone and nothing is sent.
      const row = await env.DB.prepare(`UPDATE users SET focus_push_at = NULL, focus_push = NULL WHERE id = ? AND focus_push_at = ?
        AND focus_push IS ? RETURNING id`).bind(item.user, item.stamp, item.text).first();
      if (!row) continue;
      message = { ...JSON.parse(item.text), tag: 'a80-focus', url: '/#focus' };
    } else {
      const claimed = await env.DB.prepare('INSERT INTO push_sent (user_id, key, at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING RETURNING key')
        .bind(item.user, item.key, now).first();
      if (!claimed) continue;
      message = item.kind === 'class' ? classMessage(item.event, Math.max(now, item.at), item.tz) : habitMessage(item.habit, item.day);
    }
    sent += await sendToUser(env, item.subs, message, { ttl: item.kind === 'focus' ? 600 : 1800, fetcher });
  }
  await env.DB.prepare('DELETE FROM push_sent WHERE at < ?').bind(now - 3 * 86400000).run();
  return { due: due.length, sent };
}
