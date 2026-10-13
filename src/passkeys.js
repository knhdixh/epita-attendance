// Passkey (WebAuthn) sign-in: create an account with a passkey, add passkeys to an existing account,
// sign in with any saved passkey. Cryptographic verification is done by @simplewebauthn/server.
import {
  generateRegistrationOptions, verifyRegistrationResponse,
  generateAuthenticationOptions, verifyAuthenticationResponse,
} from '@simplewebauthn/server';
import { HttpError } from './http.js';

const CHALLENGE_COOKIE = 'pk_chal';
const CHALLENGE_TTL_MS = 5 * 60 * 1000;
const RP_NAME = 'Attendance80';

const b64url = {
  encode: (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''),
  decode: (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), (c) => c.charCodeAt(0)),
};

// Passkey-only accounts have no Google identity.
export const isPasskeyOnly = (user) => String(user.google_sub || '').startsWith('passkey:');

async function saveChallenge(env, h, row) {
  const id = h.randomToken(18);
  await env.DB.batch([
    env.DB.prepare('DELETE FROM auth_challenges WHERE expires_at < ?').bind(Date.now()),
    env.DB.prepare(`INSERT INTO auth_challenges (id, challenge, kind, user_id, user_handle, name, expires_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)`).bind(id, row.challenge, row.kind, row.user_id ?? null, row.user_handle ?? null,
      row.name ?? null, Date.now() + CHALLENGE_TTL_MS),
  ]);
  return h.cookie(CHALLENGE_COOKIE, id, CHALLENGE_TTL_MS, h.secure);
}

// Single use: the row is deleted as it's read.
async function takeChallenge(env, h, request, kind) {
  const id = h.parseCookies(request.headers.get('Cookie'))[CHALLENGE_COOKIE];
  const row = id && await env.DB.prepare('DELETE FROM auth_challenges WHERE id = ? RETURNING *').bind(id).first();
  if (!row || row.kind !== kind || row.expires_at < Date.now()) throw new HttpError(400, 'This passkey request expired. Please try again.');
  return row;
}

async function readBody(request) {
  const text = await request.text();
  if (text.length > 64 * 1024) throw new HttpError(413, 'Request too large.');
  try { return JSON.parse(text || '{}'); } catch { throw new HttpError(400, 'Invalid JSON.'); }
}

const reply = (h, data, cookies = []) => {
  const headers = new Headers({ 'Content-Type': 'application/json' });
  for (const c of cookies) headers.append('Set-Cookie', c);
  return new Response(JSON.stringify(data), { headers });
};

// h = { secure, cookie, parseCookies, randomToken, currentUser, startSession }
export async function handlePasskeyAuth(request, env, url, h) {
  if (request.method !== 'POST') throw new HttpError(405, 'Method not allowed.');
  const rpID = url.hostname;
  const expectedOrigin = url.origin;
  const clearChallenge = h.cookie(CHALLENGE_COOKIE, '', 0, h.secure);
  const user = await h.currentUser(request, env);

  // ---- create an account, or add a passkey to the signed-in account
  if (url.pathname === '/auth/passkey/register/options') {
    const body = await readBody(request);
    let handle = user?.webauthn_id;
    let name = user ? (user.name || user.email || 'Me') : String(body.name || '').trim().slice(0, 60);
    if (!user) {
      if ((env.ALLOWED_DOMAIN || '').trim()) throw new HttpError(403, 'New accounts must sign in with Google here.');
      if (!name) throw new HttpError(400, 'Enter your name.');
    }
    handle ??= b64url.encode(crypto.getRandomValues(new Uint8Array(16)));
    const existing = user
      ? (await env.DB.prepare('SELECT id, transports FROM passkeys WHERE user_id = ?').bind(user.id).all()).results
      : [];
    const options = await generateRegistrationOptions({
      rpName: RP_NAME, rpID,
      userID: b64url.decode(handle), userName: user?.email || name, userDisplayName: name,
      attestationType: 'none',
      excludeCredentials: existing.map((p) => ({ id: p.id, transports: JSON.parse(p.transports) })),
      // Discoverable credentials let people sign in without typing a username.
      authenticatorSelection: { residentKey: 'required', userVerification: 'preferred' },
    });
    const c = await saveChallenge(env, h, { challenge: options.challenge, kind: 'register', user_id: user?.id, user_handle: handle, name });
    return reply(h, options, [c]);
  }

  if (url.pathname === '/auth/passkey/register/verify') {
    const body = await readBody(request);
    const ch = await takeChallenge(env, h, request, 'register');
    if (ch.user_id && ch.user_id !== user?.id) throw new HttpError(400, 'Signed-in account changed. Please try again.');
    let result;
    try {
      result = await verifyRegistrationResponse({
        response: body.response, expectedChallenge: ch.challenge, expectedOrigin, expectedRPID: rpID,
        requireUserVerification: false,
      });
    } catch (e) {
      throw new HttpError(400, `Passkey could not be verified: ${e.message}`);
    }
    if (!result.verified) throw new HttpError(400, 'Passkey could not be verified.');
    const { credential, credentialBackedUp } = result.registrationInfo;

    let userId = ch.user_id;
    const cookies = [clearChallenge];
    if (!userId) {
      // New passkey-only account.
      const created = await env.DB.prepare(`INSERT INTO users (google_sub, name, webauthn_id, created_at)
        VALUES (?, ?, ?, ?) RETURNING id`).bind(`passkey:${ch.user_handle}`, ch.name, ch.user_handle, Date.now()).first();
      userId = created.id;
      cookies.push(await h.startSession(env, userId, h.secure));
    } else {
      await env.DB.prepare('UPDATE users SET webauthn_id = COALESCE(webauthn_id, ?) WHERE id = ?').bind(ch.user_handle, userId).run();
    }
    await env.DB.prepare(`INSERT INTO passkeys (id, user_id, public_key, counter, transports, backed_up, name, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)`).bind(credential.id, userId, b64url.encode(credential.publicKey), credential.counter,
      JSON.stringify(credential.transports || body.response?.response?.transports || []), credentialBackedUp ? 1 : 0,
      String(body.deviceName || '').slice(0, 60), Date.now()).run();
    return reply(h, { ok: true, created: !ch.user_id }, cookies);
  }

  // ---- sign in with a saved passkey
  if (url.pathname === '/auth/passkey/login/options') {
    const options = await generateAuthenticationOptions({ rpID, userVerification: 'preferred' });
    return reply(h, options, [await saveChallenge(env, h, { challenge: options.challenge, kind: 'login' })]);
  }

  if (url.pathname === '/auth/passkey/login/verify') {
    const body = await readBody(request);
    const ch = await takeChallenge(env, h, request, 'login');
    const pk = body.response?.id && await env.DB.prepare('SELECT * FROM passkeys WHERE id = ?').bind(body.response.id).first();
    if (!pk) throw new HttpError(400, 'This passkey is not registered here. Create an account first, or use another sign-in method.');
    let result;
    try {
      result = await verifyAuthenticationResponse({
        response: body.response, expectedChallenge: ch.challenge, expectedOrigin, expectedRPID: rpID,
        credential: { id: pk.id, publicKey: b64url.decode(pk.public_key), counter: pk.counter, transports: JSON.parse(pk.transports) },
        requireUserVerification: false,
      });
    } catch (e) {
      throw new HttpError(400, `Passkey could not be verified: ${e.message}`);
    }
    if (!result.verified) throw new HttpError(400, 'Passkey could not be verified.');
    await env.DB.prepare('UPDATE passkeys SET counter = ?, last_used_at = ? WHERE id = ?')
      .bind(result.authenticationInfo.newCounter, Date.now(), pk.id).run();
    return reply(h, { ok: true }, [clearChallenge, await h.startSession(env, pk.user_id, h.secure)]);
  }

  throw new HttpError(404, 'Not found.');
}

// ---- account API: list / remove passkeys
export async function listPasskeys(env, user) {
  return (await env.DB.prepare(`SELECT id, name, backed_up, created_at, last_used_at FROM passkeys
    WHERE user_id = ? ORDER BY created_at`).bind(user.id).all()).results;
}

export async function deletePasskey(env, user, id) {
  const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM passkeys WHERE user_id = ?').bind(user.id).first();
  if (isPasskeyOnly(user) && n <= 1) throw new HttpError(400, "This is your only way to sign in. Add another passkey before removing this one.");
  const r = await env.DB.prepare('DELETE FROM passkeys WHERE id = ? AND user_id = ?').bind(id, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, 'Passkey not found.');
  return { ok: true };
}
