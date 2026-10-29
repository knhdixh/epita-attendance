// Web Push without a library: VAPID (RFC 8292) to identify the server to the push service, and aes128gcm
// payload encryption (RFC 8291) so only the browser can read the notification. Keys are base64url:
// VAPID_PUBLIC_KEY is the uncompressed P-256 point (65 bytes), VAPID_PRIVATE_KEY the private scalar (32 bytes).

const enc = new TextEncoder();

export const b64u = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
export function unb64u(s) {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}
const concat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let i = 0;
  for (const p of parts) { out.set(p, i); i += p.length; }
  return out;
};

// A P-256 key from its raw parts: `pub` the 65-byte point, `d` the 32-byte scalar (private keys only).
function importP256(pub, d, alg, usages) {
  const jwk = { kty: 'EC', crv: 'P-256', x: b64u(pub.slice(1, 33)), y: b64u(pub.slice(33, 65)), ext: true };
  if (d) jwk.d = b64u(d);
  return crypto.subtle.importKey('jwk', jwk, alg, false, usages);
}

async function hkdf(salt, ikm, info, length) {
  const key = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info }, key, length * 8));
}

// The request body for one subscription: salt, record size, the server's one-off public key, then the encrypted
// payload as a single record. `local` ({ pub, d }) and `salt` are only passed by the tests (RFC 8291 appendix A).
export async function encryptPayload(payload, { p256dh, auth }, { local, salt } = {}) {
  const uaPub = unb64u(p256dh), authSecret = unb64u(auth);
  let asPub, asPriv;
  if (local) {
    asPub = local.pub;
    asPriv = await importP256(local.pub, local.d, { name: 'ECDH', namedCurve: 'P-256' }, ['deriveBits']);
  } else {
    const pair = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
    asPub = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey));
    asPriv = pair.privateKey;
  }
  salt ??= crypto.getRandomValues(new Uint8Array(16));
  const uaKey = await importP256(uaPub, null, { name: 'ECDH', namedCurve: 'P-256' }, []);
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: uaKey }, asPriv, 256));

  const ikm = await hkdf(authSecret, shared, concat(enc.encode('WebPush: info\0'), uaPub, asPub), 32);
  const cek = await hkdf(salt, ikm, enc.encode('Content-Encoding: aes128gcm\0'), 16);
  const nonce = await hkdf(salt, ikm, enc.encode('Content-Encoding: nonce\0'), 12);
  const key = await crypto.subtle.importKey('raw', cek, 'AES-GCM', false, ['encrypt']);
  const body = typeof payload === 'string' ? enc.encode(payload) : payload;
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce }, key, concat(body, Uint8Array.of(2))));
  const header = new Uint8Array(21);
  header.set(salt);
  new DataView(header.buffer).setUint32(16, 4096);
  header[20] = asPub.length;
  return concat(header, asPub, sealed);
}

// The Authorization header for a push service: a JWT signed with the VAPID key, valid 12 hours.
export async function vapidAuth(endpoint, env, now = Date.now()) {
  const pub = unb64u(env.VAPID_PUBLIC_KEY);
  const key = await importP256(pub, unb64u(env.VAPID_PRIVATE_KEY), { name: 'ECDSA', namedCurve: 'P-256' }, ['sign']);
  const part = (o) => b64u(enc.encode(JSON.stringify(o)));
  const claims = { aud: new URL(endpoint).origin, exp: Math.floor(now / 1000) + 12 * 3600, sub: `https://${env.CANONICAL_HOST || 'epita.knhdixh.com'}` };
  const unsigned = `${part({ typ: 'JWT', alg: 'ES256' })}.${part(claims)}`;
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, enc.encode(unsigned));
  return `vapid t=${unsigned}.${b64u(sig)}, k=${env.VAPID_PUBLIC_KEY}`;
}

export const pushEnabled = (env) => Boolean(env.VAPID_PUBLIC_KEY && env.VAPID_PRIVATE_KEY);

// Sends one message to one subscription. Returns 'ok', 'gone' (the browser unsubscribed: delete it) or 'error'.
export async function sendPush(env, sub, message, { ttl = 3600, fetcher = fetch } = {}) {
  try {
    const res = await fetcher(sub.endpoint, {
      method: 'POST',
      headers: {
        Authorization: await vapidAuth(sub.endpoint, env),
        'Content-Encoding': 'aes128gcm',
        'Content-Type': 'application/octet-stream',
        TTL: String(ttl),
        Urgency: 'high',
      },
      body: await encryptPayload(JSON.stringify(message), sub),
    });
    if (res.status === 404 || res.status === 410) return 'gone';
    if (!res.ok) { console.error('push failed', res.status, await res.text().catch(() => '')); return 'error'; }
    return 'ok';
  } catch (e) {
    console.error('push failed', e);
    return 'error';
  }
}
