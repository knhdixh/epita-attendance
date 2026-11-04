// Commitments and working hours for the Timeline. Checked by public/timeline.js, stored as given.
import { HttpError } from './http.js';
import { cleanCommitment, cleanAvailability, MAX_COMMITMENTS } from '../public/timeline.js';

export const COMMITMENT_COLUMNS = 'id, title, kind, hours, start_date, end_date, days, color, project_id, course_id, note, created_at, updated_at';
export const commitmentRow = (r) => r && ({ ...r, days: r.days ? JSON.parse(r.days) : null });

// A linked project or course must be the user's own.
async function checkLinks(env, user, c) {
  if (c.project_id != null && !(await env.DB.prepare('SELECT 1 FROM projects WHERE id = ? AND user_id = ?').bind(c.project_id, user.id).first())) {
    throw new HttpError(400, 'Unknown project.');
  }
  if (c.course_id != null && !(await env.DB.prepare('SELECT 1 FROM courses WHERE id = ? AND user_id = ?').bind(c.course_id, user.id).first())) {
    throw new HttpError(400, 'Unknown course.');
  }
}

const stored = (v) => ('days' in v ? { ...v, days: v.days ? JSON.stringify(v.days) : null } : v);

export async function createCommitment(env, user, body, now = Date.now()) {
  const r = cleanCommitment(body);
  if (!r.ok) throw new HttpError(400, r.error);
  await checkLinks(env, user, r.value);
  const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM commitments WHERE user_id = ?').bind(user.id).first();
  if (n >= MAX_COMMITMENTS) throw new HttpError(400, `You can have up to ${MAX_COMMITMENTS} commitments. Delete an old one first.`);
  const v = stored(r.value);
  const keys = Object.keys(v);
  return commitmentRow(await env.DB.prepare(`INSERT INTO commitments (user_id, ${keys.join(', ')}, created_at, updated_at)
    VALUES (?, ${keys.map(() => '?').join(', ')}, ?, ?) RETURNING ${COMMITMENT_COLUMNS}`)
    .bind(user.id, ...keys.map((k) => v[k]), now, now).first());
}

export async function updateCommitment(env, user, id, body, now = Date.now()) {
  const current = await env.DB.prepare(`SELECT ${COMMITMENT_COLUMNS} FROM commitments WHERE id = ? AND user_id = ?`).bind(id, user.id).first();
  if (!current) throw new HttpError(404, 'Commitment not found.');
  const r = cleanCommitment(body, { partial: true, current });
  if (!r.ok) throw new HttpError(400, r.error);
  // Turning time away into work needs hours.
  if (r.value.kind === 'work' && current.kind === 'away' && !('hours' in r.value)) throw new HttpError(400, 'Say how many hours a week.');
  await checkLinks(env, user, r.value);
  const v = stored(r.value);
  const fields = Object.entries(v);
  if (!fields.length) return commitmentRow(current);
  return commitmentRow(await env.DB.prepare(`UPDATE commitments SET ${fields.map(([k]) => `${k} = ?, `).join('')}updated_at = ?
    WHERE id = ? AND user_id = ? RETURNING ${COMMITMENT_COLUMNS}`).bind(...fields.map(([, x]) => x), now, id, user.id).first());
}

export async function deleteCommitment(env, user, id) {
  const r = await env.DB.prepare('DELETE FROM commitments WHERE id = ? AND user_id = ?').bind(id, user.id).run();
  if (!r.meta.changes) throw new HttpError(404, 'Commitment not found.');
  return { ok: true };
}

// PUT /api/availability { days } (null = back to the default).
export async function saveAvailability(env, user, body) {
  let value = null;
  if (body?.days != null) {
    const r = cleanAvailability(body.days);
    if (!r.ok) throw new HttpError(400, r.error);
    value = JSON.stringify(r.value);
  }
  await env.DB.prepare('UPDATE users SET availability = ? WHERE id = ?').bind(value, user.id).run();
  return { availability: value ? JSON.parse(value) : null };
}
