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

test('challenge validation in /auth/app/google and /auth/app/dev', async () => {
  const { env } = setup();
  env.GOOGLE_CLIENT_ID = 'gid';
  env.GOOGLE_CLIENT_SECRET = 'gsecret';

  // Bad challenges (length != 43, invalid chars)
  const badChallenges = [
    'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjX.', // dot
    'dBjftJeZ4CVP+mB92K27uhbUJU1p1r_wW1gFWFOEjXk', // plus
    'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjX=', // equal
    'too_short',
  ];

  for (const c of badChallenges) {
    const resGoogle = await worker.fetch(new Request(`https://app.test/auth/app/google?challenge=${c}`), env);
    assert.equal(resGoogle.status, 400);

    const resDev = await worker.fetch(new Request(`https://app.test/auth/app/dev?challenge=${c}`), env);
    assert.equal(resDev.status, 400);
  }

  // Valid 43-character base64url challenge
  const validChal = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
  const resGoogleOk = await worker.fetch(new Request(`https://app.test/auth/app/google?challenge=${validChal}`), env);
  assert.equal(resGoogleOk.status, 302);

  const resDevOk = await worker.fetch(new Request(`https://app.test/auth/app/dev?challenge=${validChal}`), env);
  assert.equal(resDevOk.status, 302);
});

test('expired app_codes are deleted on insert of a new code', async () => {
  const { env, db } = setup();
  db.exec(`INSERT INTO app_codes (code, user_id, challenge, expires_at) VALUES ('expired_code', 1, 'chal', ${Date.now() - 1000})`);
  assert.ok(db.prepare("SELECT * FROM app_codes WHERE code = 'expired_code'").get());

  const validChal = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
  const res = await worker.fetch(new Request(`https://app.test/auth/app/dev?challenge=${validChal}`), env);
  assert.equal(res.status, 302);

  assert.equal(db.prepare("SELECT * FROM app_codes WHERE code = 'expired_code'").get(), undefined);
});

test('google callback with purpose=app creates app_code with challenge and redirects to app', async () => {
  const { env, db } = setup();
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => {
      const u = new URL(url);
      if (u.pathname === '/token') {
        return new Response(JSON.stringify({ access_token: 'g_access_1', expires_in: 3600 }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      if (u.pathname.endsWith('/userinfo')) {
        return new Response(JSON.stringify({ sub: 'google:sub99', email: 'student99@epita.fr', email_verified: true, name: 'Student 99' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      return new Response('Not found', { status: 404 });
    };

    const challenge = 'test_challenge_1234567890123456789012345678';
    const stateCookie = `oauth_state=app.expectedstate.${challenge}`;
    const res = await worker.fetch(new Request('https://app.test/auth/google/callback?code=gcode123&state=expectedstate', {
      headers: { Cookie: stateCookie },
    }), env);

    assert.equal(res.status, 302);
    const loc = res.headers.get('Location');
    assert.match(loc, /^com\.knhdixh\.attendance80:\/auth\?code=([A-Za-z0-9_-]{20,})$/);
    const code = new URL(loc).searchParams.get('code');
    const row = db.prepare('SELECT * FROM app_codes WHERE code = ?').get(code);
    assert.ok(row);
    assert.equal(row.challenge, challenge);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('google callback purpose=app redirects errors to app scheme', async () => {
  const { env } = setup();
  env.ALLOWED_DOMAIN = 'epita.fr';
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => {
      const u = new URL(url);
      if (u.pathname === '/token') {
        return new Response(JSON.stringify({ access_token: 'g_access_2', expires_in: 3600 }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      if (u.pathname.endsWith('/userinfo')) {
        return new Response(JSON.stringify({ sub: 'google:subother', email: 'other@gmail.com', email_verified: true, name: 'Other' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      return new Response('Not found', { status: 404 });
    };

    // State mismatch
    const resState = await worker.fetch(new Request('https://app.test/auth/google/callback?code=gcode123&state=wrongstate', {
      headers: { Cookie: 'oauth_state=app.correctstate.chal1234567890123456789012345678901234567890' },
    }), env);
    assert.equal(resState.status, 302);
    assert.equal(resState.headers.get('Location'), 'com.knhdixh.attendance80:/auth?error=state');

    // Domain mismatch
    const resDomain = await worker.fetch(new Request('https://app.test/auth/google/callback?code=gcode123&state=correctstate', {
      headers: { Cookie: 'oauth_state=app.correctstate.chal1234567890123456789012345678901234567890' },
    }), env);
    assert.equal(resDomain.status, 302);
    assert.equal(resDomain.headers.get('Location'), 'com.knhdixh.attendance80:/auth?error=domain');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('google callback calendar-app redirects to gcal custom scheme', async () => {
  const { env } = setup();
  env.TOKEN_KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(7)));
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => {
      const u = new URL(url);
      if (u.pathname === '/token') {
        return new Response(JSON.stringify({ access_token: 'g_access_3', refresh_token: 'ref1', scope: 'https://www.googleapis.com/auth/calendar.app.created', expires_in: 3600 }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      if (u.pathname.endsWith('/userinfo')) {
        return new Response(JSON.stringify({ sub: 'google:123', email: 'student@epita.fr', email_verified: true }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      return new Response('Not found', { status: 404 });
    };

    // With active session
    const sid = 'a80s_validsessiontoken12345';
    env.DB.raw.exec(`INSERT INTO sessions (id, user_id, expires_at) VALUES ('${sid}', 1, ${Date.now() + 60000})`);

    const res = await worker.fetch(new Request('https://app.test/auth/google/callback?code=gcode123&state=st123', {
      headers: {
        Cookie: `oauth_state=calendar-app.st123; sid=${sid}`,
      },
    }), env);
    assert.equal(res.status, 302);
    assert.equal(res.headers.get('Location'), 'com.knhdixh.attendance80:/gcal?result=connected');

    // Without session -> redirects to gcal custom scheme with error=session
    const resNoSession = await worker.fetch(new Request('https://app.test/auth/google/callback?code=gcode123&state=st123', {
      headers: {
        Cookie: 'oauth_state=calendar-app.st123',
      },
    }), env);
    assert.equal(resNoSession.status, 302);
    assert.equal(resNoSession.headers.get('Location'), 'com.knhdixh.attendance80:/gcal?error=session');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('passkey verify with X-A80-App: 1 returns app token and no session cookie', async () => {
  const { env, db } = setup();
  const rpID = 'app.test';
  const origin = 'https://app.test';
  const challenge = 'dGVzdC1jaGFsbGVuZ2UtMTIzNDU';

  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = publicKey.export({ format: 'jwk' });
  const x = Buffer.from(jwk.x, 'base64url');
  const y = Buffer.from(jwk.y, 'base64url');
  const coseKey = Buffer.concat([
    Buffer.from([0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20]),
    x,
    Buffer.from([0x22, 0x58, 0x20]),
    y,
  ]);

  const credId = Buffer.from('cred_app_test').toString('base64url');
  db.exec(`INSERT INTO passkeys (id, user_id, public_key, counter, transports, backed_up, name, created_at)
    VALUES ('${credId}', 1, '${coseKey.toString('base64url')}', 0, '["internal"]', 0, 'Pixel', ${Date.now()})`);
  db.exec(`INSERT INTO auth_challenges (id, challenge, kind, user_id, expires_at)
    VALUES ('ch_login', '${challenge}', 'login', 1, ${Date.now() + 60000})`);

  const rpIdHash = crypto.createHash('sha256').update(rpID).digest();
  const flags = Buffer.from([0x01]);
  const counter = Buffer.alloc(4);
  counter.writeUInt32BE(1, 0);
  const authenticatorData = Buffer.concat([rpIdHash, flags, counter]);

  const clientDataJSON = Buffer.from(JSON.stringify({ type: 'webauthn.get', challenge, origin, crossOrigin: false }));
  const clientDataHash = crypto.createHash('sha256').update(clientDataJSON).digest();
  const signature = crypto.sign('sha256', Buffer.concat([authenticatorData, clientDataHash]), privateKey);

  const res = await worker.fetch(new Request('https://app.test/auth/passkey/login/verify', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-A80-App': '1',
      'Cookie': 'pk_chal=ch_login',
    },
    body: JSON.stringify({
      response: {
        id: credId,
        rawId: credId,
        response: {
          authenticatorData: authenticatorData.toString('base64url'),
          clientDataJSON: clientDataJSON.toString('base64url'),
          signature: signature.toString('base64url'),
        },
        type: 'public-key',
      },
    }),
  }), env);

  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.ok, true);
  assert.match(body.token, /^a80s_/);
  const setCookie = res.headers.get('Set-Cookie') || '';
  assert.ok(!setCookie.includes('sid='));
  assert.ok(!setCookie.includes('a80s='));
});

test('native sign-in drops the user\'s TWA push subscriptions (app = 1) and nothing else', async () => {
  const { env, db } = setup();
  db.exec("INSERT INTO users (id, google_sub, email, name, created_at, threshold, unit) VALUES (2, 'google:456', 'other@epita.fr', 'Other', 0, 0.8, 'sessions')");
  const sub = (user, endpoint, app) => db.prepare(
    'INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, app, created_at) VALUES (?, ?, ?, ?, ?, 0)').run(user, endpoint, 'p', 'a', app);
  sub(1, 'https://push.test/twa', 1);
  sub(1, 'https://push.test/browser', 0);
  sub(2, 'https://push.test/other-twa', 1);

  const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
  db.exec(`INSERT INTO app_codes (code, user_id, challenge, expires_at) VALUES ('code_push_cleanup', 1, '${challenge}', ${Date.now() + 120000})`);
  const res = await worker.fetch(new Request('https://app.test/auth/app/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: 'code_push_cleanup', verifier }),
  }), env);
  assert.equal(res.status, 200);

  const left = db.prepare('SELECT endpoint FROM push_subscriptions ORDER BY endpoint').all().map((r) => r.endpoint);
  assert.deepEqual(left, ['https://push.test/browser', 'https://push.test/other-twa']);
});

test('a failed code exchange leaves push subscriptions alone', async () => {
  const { env, db } = setup();
  db.prepare('INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, app, created_at) VALUES (1, ?, ?, ?, 1, 0)').run('https://push.test/twa', 'p', 'a');
  const res = await worker.fetch(new Request('https://app.test/auth/app/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: 'nope', verifier: 'x' }),
  }), env);
  assert.equal(res.status, 400);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM push_subscriptions').get().n, 1);
});
