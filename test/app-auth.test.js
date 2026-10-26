import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { d1 } from './d1-shim.js';
import worker from '../src/worker.js';
import { appOrigins } from '../src/passkeys.js';
import { createToken } from '../src/tokens.js';

function setup() {
  const env = {
    DB: d1(),
    DEV_LOGIN: '1',
    ANDROID_PACKAGE: 'com.knhdixh.attendance80',
    ANDROID_CERT_SHA256: '30:8B:9E:2E:FD:D5:A7:79:32:66:B0:60:76:EF:8F:C3:73:EA:12:22:E6:CD:B9:3C:1D:10:A1:82:EF:A8:95:7D',
  };
  env.DB.raw.exec("INSERT INTO users (id, google_sub, email, name, created_at, threshold, unit) VALUES (1, 'google:123', 'student@epita.fr', 'Student', 0, 0.8, 'sessions')");
  return { env, db: env.DB.raw, user: { id: 1, email: 'student@epita.fr', name: 'Student', threshold: 0.8, unit: 'sessions' } };
}

test('code exchange success: valid code and verifier yields app session token', async () => {
  const { env, db } = setup();
  const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
  const code = 'code_test_1234567890';
  db.exec(`INSERT INTO app_codes (code, user_id, challenge, expires_at) VALUES ('${code}', 1, '${challenge}', ${Date.now() + 120000})`);

  const res = await worker.fetch(new Request('https://app.test/auth/app/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, verifier }),
  }), env);

  assert.equal(res.status, 200);
  const data = await res.json();
  assert.match(data.token, /^a80s_[A-Za-z0-9_-]{20,}$/);

  // Token is stored in sessions table
  const session = db.prepare('SELECT * FROM sessions WHERE id = ?').get(data.token);
  assert.ok(session);
  assert.equal(session.user_id, 1);

  // Single use: code is deleted
  const codeRow = db.prepare('SELECT * FROM app_codes WHERE code = ?').get(code);
  assert.equal(codeRow, undefined);
});

test('code exchange: wrong verifier is rejected with 400 and code is deleted', async () => {
  const { env, db } = setup();
  const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
  const code = 'code_wrong_verifier';
  db.exec(`INSERT INTO app_codes (code, user_id, challenge, expires_at) VALUES ('${code}', 1, '${challenge}', ${Date.now() + 120000})`);

  const res = await worker.fetch(new Request('https://app.test/auth/app/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, verifier: 'wrong_verifier_123456789012345678901234567' }),
  }), env);

  assert.equal(res.status, 400);
  assert.deepEqual(await res.json(), { error: 'Sign-in expired, please try again.' });
  // Code consumed
  assert.equal(db.prepare('SELECT * FROM app_codes WHERE code = ?').get(code), undefined);
});

test('code exchange: reused code is rejected with 400', async () => {
  const { env, db } = setup();
  const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
  const code = 'code_reuse_test';
  db.exec(`INSERT INTO app_codes (code, user_id, challenge, expires_at) VALUES ('${code}', 1, '${challenge}', ${Date.now() + 120000})`);

  const req = () => worker.fetch(new Request('https://app.test/auth/app/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, verifier }),
  }), env);

  const res1 = await req();
  assert.equal(res1.status, 200);

  const res2 = await req();
  assert.equal(res2.status, 400);
  assert.deepEqual(await res2.json(), { error: 'Sign-in expired, please try again.' });
});

test('code exchange: expired code is rejected with 400', async () => {
  const { env, db } = setup();
  const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
  const code = 'code_expired_test';
  db.exec(`INSERT INTO app_codes (code, user_id, challenge, expires_at) VALUES ('${code}', 1, '${challenge}', ${Date.now() - 1000})`);

  const res = await worker.fetch(new Request('https://app.test/auth/app/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, verifier }),
  }), env);

  assert.equal(res.status, 400);
  assert.deepEqual(await res.json(), { error: 'Sign-in expired, please try again.' });
});

test('bearer a80s_ reaches /api/data and /api/me', async () => {
  const { env, db } = setup();
  const token = 'a80s_test_bearer_reach_data_12345';
  db.exec(`INSERT INTO sessions (id, user_id, expires_at) VALUES ('${token}', 1, ${Date.now() + 3600000})`);

  const resData = await worker.fetch(new Request('https://app.test/api/data', {
    headers: { Authorization: `Bearer ${token}` },
  }), env);
  assert.equal(resData.status, 200);
  const data = await resData.json();
  assert.equal(data.user.email, 'student@epita.fr');

  const resMe = await worker.fetch(new Request('https://app.test/api/me', {
    headers: { Authorization: `Bearer ${token}` },
  }), env);
  assert.equal(resMe.status, 200);
  const me = await resMe.json();
  assert.equal(me.user.email, 'student@epita.fr');
  assert.equal(me.user.name, 'Student');
});

test('bearer a80s_ is not restricted by tokenAllows', async () => {
  const { env, db, user } = setup();
  // Personal token (write scope) cannot call PATCH /api/settings
  const rnd = () => 'x'.repeat(30) + Math.random().toString(36).slice(2);
  const personal = await createToken(env, user, { name: 'Script Token', scope: 'write' }, rnd);

  const resPersonal = await worker.fetch(new Request('https://app.test/api/settings', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${personal.token}` },
    body: JSON.stringify({ threshold: 0.85, unit: 'hours' }),
  }), env);
  assert.equal(resPersonal.status, 403);

  // App session bearer can call PATCH /api/settings
  const appToken = 'a80s_app_unrestricted_settings_token';
  db.exec(`INSERT INTO sessions (id, user_id, expires_at) VALUES ('${appToken}', 1, ${Date.now() + 3600000})`);

  const resApp = await worker.fetch(new Request('https://app.test/api/settings', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${appToken}` },
    body: JSON.stringify({ threshold: 0.85, unit: 'hours' }),
  }), env);
  assert.equal(resApp.status, 200);
  assert.deepEqual(await resApp.json(), { ok: true });

  const updatedUser = db.prepare('SELECT threshold, unit FROM users WHERE id = 1').get();
  assert.equal(updatedUser.threshold, 0.85);
  assert.equal(updatedUser.unit, 'hours');
});

test('expired session is 401', async () => {
  const { env, db } = setup();
  const token = 'a80s_expired_session_token_12345';
  db.exec(`INSERT INTO sessions (id, user_id, expires_at) VALUES ('${token}', 1, ${Date.now() - 5000})`);

  const resData = await worker.fetch(new Request('https://app.test/api/data', {
    headers: { Authorization: `Bearer ${token}` },
  }), env);
  assert.equal(resData.status, 401);
  assert.deepEqual(await resData.json(), { error: 'Not signed in.' });

  const resMe = await worker.fetch(new Request('https://app.test/api/me', {
    headers: { Authorization: `Bearer ${token}` },
  }), env);
  assert.equal(resMe.status, 401);
  assert.deepEqual(await resMe.json(), { error: 'Not signed in.' });
});

test('logout deletes the session row', async () => {
  const { env, db } = setup();
  const token = 'a80s_logout_session_token_123456';
  db.exec(`INSERT INTO sessions (id, user_id, expires_at) VALUES ('${token}', 1, ${Date.now() + 3600000})`);

  const resLogout = await worker.fetch(new Request('https://app.test/auth/logout', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  }), env);
  assert.equal(resLogout.status, 200);
  assert.deepEqual(await resLogout.json(), { ok: true });

  assert.equal(db.prepare('SELECT * FROM sessions WHERE id = ?').get(token), undefined);

  const resData = await worker.fetch(new Request('https://app.test/api/data', {
    headers: { Authorization: `Bearer ${token}` },
  }), env);
  assert.equal(resData.status, 401);
});

test('sliding expiry: pushes expires_at to now + 30 days when under 15 days left', async () => {
  const { env, db } = setup();
  const token = 'a80s_sliding_expiry_session_12345';
  const now = Date.now();
  // 10 days left (< 15 days)
  const initialExpiry = now + 10 * 86400000;
  db.exec(`INSERT INTO sessions (id, user_id, expires_at) VALUES ('${token}', 1, ${initialExpiry})`);

  await worker.fetch(new Request('https://app.test/api/me', {
    headers: { Authorization: `Bearer ${token}` },
  }), env);

  const row1 = db.prepare('SELECT expires_at FROM sessions WHERE id = ?').get(token);
  // Should be extended to roughly now + 30 days
  assert.ok(row1.expires_at >= now + 29 * 86400000);

  // Now set expires_at to 20 days in the future (>= 15 days left)
  const twentyDaysLater = now + 20 * 86400000;
  db.exec(`UPDATE sessions SET expires_at = ${twentyDaysLater} WHERE id = '${token}'`);

  await worker.fetch(new Request('https://app.test/api/me', {
    headers: { Authorization: `Bearer ${token}` },
  }), env);

  const row2 = db.prepare('SELECT expires_at FROM sessions WHERE id = ?').get(token);
  assert.equal(row2.expires_at, twentyDaysLater);
});

test('web-link allow-list and single use', async () => {
  const { env, db, user } = setup();
  const appToken = 'a80s_web_link_test_session_123456';
  db.exec(`INSERT INTO sessions (id, user_id, expires_at) VALUES ('${appToken}', 1, ${Date.now() + 3600000})`);

  // Personal token cannot use web-link
  const rnd = () => 'x'.repeat(30) + Math.random().toString(36).slice(2);
  const personal = await createToken(env, user, { name: 'Personal', scope: 'write' }, rnd);
  const resPersonal = await worker.fetch(new Request('https://app.test/api/app/web-link', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${personal.token}` },
    body: JSON.stringify({ next: '/auth/google/calendar' }),
  }), env);
  assert.equal(resPersonal.status, 403);

  // Disallowed destination is rejected
  const resBadDest = await worker.fetch(new Request('https://app.test/api/app/web-link', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${appToken}` },
    body: JSON.stringify({ next: 'https://attacker.com' }),
  }), env);
  assert.equal(resBadDest.status, 400);

  // Allowed destination returns url
  const resOk = await worker.fetch(new Request('https://app.test/api/app/web-link', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${appToken}` },
    body: JSON.stringify({ next: '/auth/google/calendar?app=1' }),
  }), env);
  assert.equal(resOk.status, 200);
  const { url } = await resOk.json();
  assert.match(url, /^\/auth\/app\/web\?code=/);

  // GET the web link -> redirects to next and starts cookie session
  const resFollow = await worker.fetch(new Request(`https://app.test${url}`), env);
  assert.equal(resFollow.status, 302);
  assert.equal(resFollow.headers.get('Location'), '/auth/google/calendar?app=1');
  assert.match(resFollow.headers.get('Set-Cookie') || '', /sid=/);

  // Single use: following the web link second time fails
  const resFollowAgain = await worker.fetch(new Request(`https://app.test${url}`), env);
  assert.equal(resFollowAgain.status, 400);
});

test('apk-key-hash origin computed correctly from a known fingerprint', () => {
  const fp = '30:8B:9E:2E:FD:D5:A7:79:32:66:B0:60:76:EF:8F:C3:73:EA:12:22:E6:CD:B9:3C:1D:10:A1:82:EF:A8:95:7D';
  // Compute independently using node:crypto
  const hex = fp.replace(/:/g, '');
  const expectedB64 = crypto.createHash('sha256'); // Wait, the fp is already the sha256 bytes!
  const rawBytes = Buffer.from(hex, 'hex');
  const expectedOrigin = `android:apk-key-hash:${rawBytes.toString('base64url')}`;

  const origins = appOrigins({ ANDROID_CERT_SHA256: fp });
  assert.deepEqual(origins, [expectedOrigin]);
  assert.equal(expectedOrigin, 'android:apk-key-hash:MIueLv3Vp3kyZrBgdu-Pw3PqEiLmzbk8HRChgu-olX0');
});

test('assetlinks includes get_login_creds relation', async () => {
  const { env } = setup();
  const res = await worker.fetch(new Request('https://app.test/.well-known/assetlinks.json'), env);
  assert.equal(res.status, 200);
  const links = await res.json();
  assert.ok(links[0].relation.includes('delegate_permission/common.get_login_creds'));
  assert.ok(links[0].relation.includes('delegate_permission/common.handle_all_urls'));
  assert.ok(links[0].relation.includes('delegate_permission/common.use_as_origin'));
});

test('GET /auth/app/dev sets up code and redirects to app custom scheme', async () => {
  const { env, db } = setup();
  const verifier = 'test_dev_verifier_12345678901234567890123';
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');

  const res = await worker.fetch(new Request(`https://app.test/auth/app/dev?email=devuser@epita.fr&challenge=${challenge}`), env);
  assert.equal(res.status, 302);
  const location = res.headers.get('Location');
  assert.match(location, /^com\.knhdixh\.attendance80:\/auth\?code=([A-Za-z0-9_-]{20,})$/);

  const code = new URL(location).searchParams.get('code');
  assert.ok(code);

  // Exchange at /auth/app/token
  const tokenRes = await worker.fetch(new Request('https://app.test/auth/app/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, verifier }),
  }), env);
  assert.equal(tokenRes.status, 200);
  const { token } = await tokenRes.json();
  assert.match(token, /^a80s_/);

  const devUser = db.prepare("SELECT * FROM users WHERE email = 'devuser@epita.fr'").get();
  assert.ok(devUser);
  assert.equal(devUser.name, 'devuser');
});
