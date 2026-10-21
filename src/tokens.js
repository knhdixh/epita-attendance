// Personal access tokens: `Authorization: Bearer a80_…` for scripts such as a Claude Code repo. A token can read
// (GET /api/plan, GET /api/data) or also write homework, catch-ups and attendance marks; it can never manage
// calendars, tokens, keys or the account. Only a SHA-256 of the token is stored; the token is shown once.
import { HttpError } from './http.js';

const MAX_TOKENS = 10;
const PREFIX = 'a80_';

export async function sha256(text) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

const READ = [['GET', /^\/api\/(plan|data)$/]];
const WRITE = [
  ['POST', /^\/api\/homework$/], ['PATCH', /^\/api\/homework\/\d+$/], ['DELETE', /^\/api\/homework\/\d+$/],
  ['POST', /^\/api\/catchups$/], ['PATCH', /^\/api\/attendance\/\d+$/],
];
export const tokenAllows = (scope, method, path) =>
  [...READ, ...(scope === 'write' ? WRITE : [])].some(([m, re]) => m === method && re.test(path));

// The user a bearer token belongs to (with `tokenScope`), or null. Last use is recorded at most once an hour.
export async function userFromToken(env, header) {
  const m = /^Bearer\s+(a80_[A-Za-z0-9_-]{20,})$/.exec(header || '');
  if (!m) return null;
  const hash = await sha256(m[1]);
  const row = await env.DB.prepare(`SELECT u.*, t.id AS token_id, t.scope AS token_scope, t.last_used_at AS token_used
    FROM api_tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ?`).bind(hash).first();
  if (!row) return null;
  const now = Date.now();
  if (!row.token_used || now - row.token_used > 3600000) {
    await env.DB.prepare('UPDATE api_tokens SET last_used_at = ? WHERE id = ?').bind(now, row.token_id).run();
  }
  const { token_id: id, token_scope: tokenScope, token_used: _, ...user } = row;
  return { ...user, tokenId: id, tokenScope };
}

export async function listTokens(env, user) {
  const r = await env.DB.prepare('SELECT id, name, scope, created_at, last_used_at FROM api_tokens WHERE user_id = ? ORDER BY id')
    .bind(user.id).all();
  return r.results;
}

export async function createToken(env, user, body, randomToken) {
  const name = typeof body.name === 'string' ? body.name.trim().replace(/\s+/g, ' ') : '';
  if (!name || name.length > 60) throw new HttpError(400, 'Give the token a name (60 characters at most).');
  if (!['read', 'write'].includes(body.scope)) throw new HttpError(400, 'scope must be "read" or "write".');
  const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM api_tokens WHERE user_id = ?').bind(user.id).first();
  if (n >= MAX_TOKENS) throw new HttpError(400, `You can have up to ${MAX_TOKENS} tokens. Revoke one first.`);
  const token = PREFIX + randomToken(32);
  const row = await env.DB.prepare(`INSERT INTO api_tokens (user_id, name, token_hash, scope, created_at) VALUES (?, ?, ?, ?, ?)
    RETURNING id, name, scope, created_at, last_used_at`).bind(user.id, name, await sha256(token), body.scope, Date.now()).first();
  return { ...row, token };
}

export async function revokeToken(env, user, id) {
  const r = await env.DB.prepare('DELETE FROM api_tokens WHERE id = ? AND user_id = ?').bind(id, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, 'Token not found.');
  return { ok: true };
}
