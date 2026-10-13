// Browser side of passkey sign-in. Converts between the server's JSON options and the WebAuthn API by
// hand (instead of PublicKeyCredential.parse*FromJSON) so it works on older Safari versions too.

const toBuf = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), (c) => c.charCodeAt(0)).buffer;
const toB64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

export const passkeysSupported = () => typeof window.PublicKeyCredential === 'function' && !!navigator.credentials?.create;

async function post(path, body) {
  let res;
  try {
    res = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
  } catch {
    throw new Error('Could not reach the server. Check your connection, or whether an ad/tracker blocker is blocking this site.');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

// The user closed the prompt, or no passkey exists: a quiet error message is enough.
function friendly(err) {
  if (err?.name === 'NotAllowedError') return new Error('Passkey request was cancelled or timed out.');
  if (err?.name === 'InvalidStateError') return new Error('This device already has a passkey for this account.');
  return err;
}

const deviceName = () => {
  const ua = navigator.userAgent;
  return /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad' : /Android/.test(ua) ? 'Android'
    : /Mac/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : 'This device';
};

// Creates a passkey: a new account when signed out (name required), or an extra passkey when signed in.
export async function registerPasskey(name) {
  const o = await post('/auth/passkey/register/options', { name });
  let cred;
  try {
    cred = await navigator.credentials.create({
      publicKey: {
        ...o,
        challenge: toBuf(o.challenge),
        user: { ...o.user, id: toBuf(o.user.id) },
        excludeCredentials: (o.excludeCredentials || []).map((c) => ({ ...c, id: toBuf(c.id) })),
      },
    });
  } catch (e) { throw friendly(e); }
  const r = cred.response;
  return post('/auth/passkey/register/verify', {
    deviceName: deviceName(),
    response: {
      id: cred.id, rawId: toB64url(cred.rawId), type: cred.type,
      authenticatorAttachment: cred.authenticatorAttachment ?? undefined,
      clientExtensionResults: cred.getClientExtensionResults(),
      response: {
        clientDataJSON: toB64url(r.clientDataJSON),
        attestationObject: toB64url(r.attestationObject),
        transports: r.getTransports?.() || [],
      },
    },
  });
}

export async function signInWithPasskey() {
  const o = await post('/auth/passkey/login/options');
  let cred;
  try {
    cred = await navigator.credentials.get({
      publicKey: { ...o, challenge: toBuf(o.challenge), allowCredentials: (o.allowCredentials || []).map((c) => ({ ...c, id: toBuf(c.id) })) },
    });
  } catch (e) { throw friendly(e); }
  const r = cred.response;
  return post('/auth/passkey/login/verify', {
    response: {
      id: cred.id, rawId: toB64url(cred.rawId), type: cred.type,
      authenticatorAttachment: cred.authenticatorAttachment ?? undefined,
      clientExtensionResults: cred.getClientExtensionResults(),
      response: {
        clientDataJSON: toB64url(r.clientDataJSON),
        authenticatorData: toB64url(r.authenticatorData),
        signature: toB64url(r.signature),
        userHandle: r.userHandle ? toB64url(r.userHandle) : undefined,
      },
    },
  });
}
