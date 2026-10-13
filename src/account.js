// Deleting an account removes all of the user's data. Every table referencing users has ON DELETE CASCADE,
// except auth_challenges (short-lived, cleared here). Google Calendar access is revoked first.
import { disconnect } from './gcal.js';

export async function deleteAccount(env, user) {
  try { await disconnect(env, user.id); } catch (e) { console.error('gcal revoke during account deletion', e); }
  await env.DB.batch([
    env.DB.prepare('DELETE FROM auth_challenges WHERE user_id = ?').bind(user.id),
    env.DB.prepare('DELETE FROM users WHERE id = ?').bind(user.id),
  ]);
}
