import { test } from 'node:test';
import assert from 'node:assert/strict';
import { d1 } from './d1-shim.js';
import { encryptPayload, vapidAuth, b64u, unb64u } from '../src/push.js';
import { subscribe, setFocusPush, saveNotifySettings, sendDueNotifications, cleanNotify } from '../src/notify.js';
import { createHabit, logHabit, updateHabit } from '../src/habits.js';
import * as H from '../public/habits.js';

const MIN = 60000;

test('push: aes128gcm encryption matches RFC 8291 appendix A', async () => {
  const out = await encryptPayload('When I grow up, I want to be a watermelon', {
    p256dh: 'BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4',
    auth: 'BTBZMqHH6r4Tts7J_aSIgg',
  }, {
    local: {
      pub: unb64u('BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8'),
      d: unb64u('yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw'),
    },
    salt: unb64u('DGv6ra1nlYgDCS1FRnbzlw'),
  });
  assert.equal(b64u(out), 'DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN');
});

async function vapidEnv() {
  const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const pub = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey));
  const jwk = await crypto.subtle.exportKey('jwk', pair.privateKey);
  return { env: { VAPID_PUBLIC_KEY: b64u(pub), VAPID_PRIVATE_KEY: jwk.d, CANONICAL_HOST: 'epita.knhdixh.com' }, pair };
}

// A browser's subscription keys, so payloads can be encrypted to it.
async function browserKeys() {
  const pair = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  return { p256dh: b64u(await crypto.subtle.exportKey('raw', pair.publicKey)), auth: b64u(crypto.getRandomValues(new Uint8Array(16))) };
}

test('push: the VAPID token is signed for the push service origin and verifies with the public key', async () => {
  const { env, pair } = await vapidEnv();
  const header = await vapidAuth('https://fcm.googleapis.com/fcm/send/abc', env, 1_700_000_000_000);
  const [, jwt, k] = /^vapid t=([^,]+), k=(.+)$/.exec(header);
  assert.equal(k, env.VAPID_PUBLIC_KEY);
  const [h, p, sig] = jwt.split('.');
  const claims = JSON.parse(new TextDecoder().decode(unb64u(p)));
  assert.deepEqual(claims, { aud: 'https://fcm.googleapis.com', exp: 1_700_000_000 + 12 * 3600, sub: 'https://epita.knhdixh.com' });
  const ok = await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, pair.publicKey, unb64u(sig), new TextEncoder().encode(`${h}.${p}`));
  assert.ok(ok);
});

test('habits: reminder days follow the account time zone', () => {
  // 2026-10-28 07:30 UTC is 09:30 in Helsinki (Wednesday).
  const now = Date.UTC(2026, 9, 28, 7, 30);
  const h = { remind_at: '09:30' };
  assert.equal(H.reminderDay(h, now, 'Europe/Helsinki'), '2026-10-28');
  assert.equal(H.reminderDay(h, now + 4 * MIN, 'Europe/Helsinki'), '2026-10-28', 'a missed cron run still sends it');
  assert.equal(H.reminderDay(h, now + 5 * MIN, 'Europe/Helsinki'), null);
  assert.equal(H.reminderDay(h, now - MIN, 'Europe/Helsinki'), null);
  assert.equal(H.reminderDay(h, now, 'Europe/Paris'), null, '08:30 in Paris');
  assert.equal(H.reminderDay({ remind_at: null }, now, 'Europe/Helsinki'), null);
});

async function setup() {
  const DB = d1();
  const { env: keys } = await vapidEnv();
  const env = { DB, ...keys };
  DB.raw.exec("INSERT INTO users (id, google_sub, created_at) VALUES (1, 'g1', 0), (2, 'g2', 0)");
  return env;
}

const user = { id: 1 };

test('habits: the reminder time is stored, cleared and validated', async () => {
  const env = await setup();
  const h = await createHabit(env, user, { name: 'Read', remind_at: '21:00' });
  assert.equal(h.remind_at, '21:00');
  assert.equal((await updateHabit(env, user, h.id, { name: 'Read 20 pages' })).remind_at, '21:00', 'kept by other edits');
  assert.equal((await updateHabit(env, user, h.id, { remind_at: '' })).remind_at, null);
  assert.equal((await createHabit(env, user, { name: 'Run' })).remind_at, null);
  await assert.rejects(createHabit(env, user, { name: 'x', remind_at: '25:00' }), /reminder time/);
  await assert.rejects(updateHabit(env, user, h.id, { remind_at: '9:00' }), /reminder time/);
});

test('notify: settings are cleaned', () => {
  assert.deepEqual(cleanNotify({ classLead: 7, classes: 'yes', habits: false, tz: 'Nowhere' }),
    { classes: true, classLead: 10, habits: false, focus: true, tz: 'Europe/Paris' });
});

test('notify: subscriptions are validated and move to whoever signs in on that browser', async () => {
  const env = await setup();
  const keys = await browserKeys();
  await assert.rejects(subscribe(env, user, { endpoint: 'http://push.example/x', keys }), /endpoint/);
  await assert.rejects(subscribe(env, user, { endpoint: 'https://push.example/x', keys: { p256dh: 'short', auth: keys.auth } }), /keys/);
  assert.equal((await subscribe(env, user, { endpoint: 'https://push.example/x', keys })).devices, 1);
  await subscribe(env, { id: 2 }, { endpoint: 'https://push.example/x', keys });
  assert.equal(env.DB.raw.prepare('SELECT user_id FROM push_subscriptions').get().user_id, 2);
});

// A browser's keys, kept so its messages can be decrypted (RFC 8291, the receiving side).
const browsers = new Map();
async function browserFor(endpoint) {
  const pair = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const keys = { p256dh: b64u(await crypto.subtle.exportKey('raw', pair.publicKey)), auth: b64u(crypto.getRandomValues(new Uint8Array(16))) };
  browsers.set(endpoint, { ...keys, privateKey: pair.privateKey });
  return { endpoint, keys };
}
async function hkdf(salt, ikm, info, n) {
  const key = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info: new Uint8Array(info) }, key, n * 8));
}
async function decrypt(endpoint, body) {
  const b = browsers.get(endpoint);
  const salt = body.slice(0, 16), asPub = body.slice(21, 21 + body[20]), sealed = body.slice(21 + body[20]);
  const asKey = await crypto.subtle.importKey('raw', asPub, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: asKey }, b.privateKey, 256));
  const te = new TextEncoder();
  const ikm = await hkdf(unb64u(b.auth), shared, [...te.encode('WebPush: info\0'), ...unb64u(b.p256dh), ...asPub], 32);
  const cek = await crypto.subtle.importKey('raw', await hkdf(salt, ikm, te.encode('Content-Encoding: aes128gcm\0'), 16), 'AES-GCM', false, ['decrypt']);
  const nonce = await hkdf(salt, ikm, te.encode('Content-Encoding: nonce\0'), 12);
  const plain = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce }, cek, sealed));
  return JSON.parse(new TextDecoder().decode(plain.slice(0, plain.lastIndexOf(2))));
}

// A push service that records what it was sent, decrypted. 410 for endpoints containing "gone".
function pushService() {
  const calls = [];
  const fetcher = async (url, init) => {
    calls.push({ url, init, message: browsers.has(url) ? await decrypt(url, init.body) : null });
    return new Response('', { status: url.includes('gone') ? 410 : 201 });
  };
  return { calls, fetcher };
}

test('cron: class reminders go out once, at the chosen time, and skip planned absences', async () => {
  const env = await setup();
  const now = Date.UTC(2026, 9, 28, 8, 0);
  env.DB.raw.exec(`INSERT INTO courses (id, user_id, key, name, tracked) VALUES (1, 1, 'a', 'Algorithms', 1), (2, 1, 'b', 'Untracked', 0);
    INSERT INTO events (id, user_id, course_id, uid, summary, location, start_at, end_at, status) VALUES
      (1, 1, 1, 'u1', 'ALGO', 'Room 101', ${now + 15 * MIN}, ${now + 105 * MIN}, NULL),
      (2, 1, 1, 'u2', 'ALGO', '', ${now + 15 * MIN}, ${now + 60 * MIN}, 'absent'),
      (3, 1, 2, 'u3', 'X', '', ${now + 15 * MIN}, ${now + 60 * MIN}, NULL),
      (4, 1, 1, 'u4', 'ALGO', '', ${now + 40 * MIN}, ${now + 60 * MIN}, NULL);`);
  await subscribe(env, user, await browserFor('https://push.example/a'));
  await saveNotifySettings(env, { id: 1, notify_settings: null }, { classLead: 15, tz: 'Europe/Paris' });
  const svc = pushService();
  const opts = { sleep: async () => {}, clock: () => now, fetcher: svc.fetcher };
  assert.equal((await sendDueNotifications(env, now, opts)).sent, 1);
  assert.equal(svc.calls.length, 1);
  assert.equal(svc.calls[0].init.headers.Urgency, 'high');
  assert.deepEqual(svc.calls[0].message, { title: 'Algorithms in 15 min', body: '09:15–10:45 · Room 101', tag: 'a80-class-1', url: '/#today' });
  assert.equal((await sendDueNotifications(env, now + 30000, opts)).sent, 0, 'not twice');
  assert.equal((await sendDueNotifications(env, now + 25 * MIN, opts)).sent, 1, 'the next one');
});

test('cron: habit reminders only for habits due and not done; gone subscriptions are removed', async () => {
  const env = await setup();
  const now = Date.UTC(2026, 9, 28, 20, 0); // Wednesday, 21:00 in Paris (the default time zone)
  const read = await createHabit(env, user, { name: 'Read', icon: '📖', remind_at: '21:00' });
  const run = await createHabit(env, user, { name: 'Run', remind_at: '21:00' });
  await logHabit(env, user, run.id, { day: '2026-10-28', count: 1 }, now);
  await createHabit(env, user, { name: 'Gym', remind_at: '21:00', schedule: { type: 'days', days: [1, 5] } });
  const water = await createHabit(env, user, { name: 'Water', remind_at: '21:00', target: 8, unit: 'glasses' });
  await logHabit(env, user, water.id, { day: '2026-10-28', count: 3 }, now);
  const weekly = await createHabit(env, user, { name: 'Swim', remind_at: '21:00', schedule: { type: 'weekly', n: 1 } });
  await logHabit(env, user, weekly.id, { day: '2026-10-26', count: 1 }, now);
  await createHabit(env, user, { name: 'Later', remind_at: '21:30' });
  const off = await createHabit(env, user, { name: 'Archived', remind_at: '21:00' });
  await updateHabit(env, user, off.id, { archived: true });
  await subscribe(env, user, await browserFor('https://push.example/a'));
  await subscribe(env, user, { endpoint: 'https://push.example/gone', keys: await browserKeys() });
  const svc = pushService();
  const r = await sendDueNotifications(env, now, { sleep: async () => {}, clock: () => now, fetcher: svc.fetcher });
  assert.equal(r.due, 2, 'Read, and Water (3 of 8); not Run (done), Gym (Mon, Fri), Swim (done this week), Later or Archived');
  assert.equal(r.sent, 2);
  const messages = svc.calls.filter((c) => c.url === 'https://push.example/a').map((c) => c.message);
  assert.deepEqual(messages.find((m) => m.tag === `a80-habit-${read.id}`), {
    title: '📖 Read', body: 'Time for your habit. Tap Done when it\'s done.', tag: `a80-habit-${read.id}`, url: '/#habits',
    habit: { id: read.id, day: '2026-10-28', count: 1 }, actions: [{ action: 'habit-done', title: 'Done' }],
  });
  assert.equal(messages.find((m) => m.tag === `a80-habit-${water.id}`).habit.count, 8, 'Done fills the day');
  assert.equal(env.DB.raw.prepare('SELECT COUNT(*) AS n FROM push_subscriptions').get().n, 1);
  assert.equal((await sendDueNotifications(env, now + 2 * MIN, { sleep: async () => {}, clock: () => now, fetcher: pushService().fetcher })).sent, 0, 'not twice');
});

test('cron: the focus push waits for its time, skips the app, and is dropped if the page moved on', async () => {
  const env = await setup();
  const now = Date.UTC(2026, 9, 28, 8, 0);
  await subscribe(env, user, await browserFor('https://push.example/browser'));
  await subscribe(env, user, { endpoint: 'https://push.example/app', keys: await browserKeys(), app: true });
  await setFocusPush(env, user, { at: now + 20000, title: 'Focus done.', body: 'Time for a break.' }, now);
  const svc = pushService();
  const waits = [];
  const r = await sendDueNotifications(env, now, { sleep: async (ms) => { waits.push(ms); }, clock: () => now, fetcher: svc.fetcher });
  assert.equal(r.sent, 1);
  assert.deepEqual(waits, [22000]);
  assert.deepEqual(svc.calls.map((c) => c.url), ['https://push.example/browser']);
  assert.deepEqual(svc.calls[0].message, { title: 'Focus done.', body: 'Time for a break.', tag: 'a80-focus', url: '/#focus' });

  // The open page moved the timer on while the cron waited: nothing is sent.
  await setFocusPush(env, user, { at: now + 20000, title: 'Focus done.', body: '' }, now);
  const svc2 = pushService();
  await sendDueNotifications(env, now, {
    sleep: async () => { await setFocusPush(env, user, { at: now + 5 * MIN, title: 'Break over.', body: '' }, now); },
    clock: () => now, fetcher: svc2.fetcher,
  });
  assert.equal(svc2.calls.length, 0);
  await assert.rejects(setFocusPush(env, user, { at: now + 6 * 3600000, title: 'x' }, now), /Invalid/);
});
