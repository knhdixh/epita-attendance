// Settings › Claude Code access: personal tokens for scripts (the "plate" repo). A new token is shown once.
import { state, esc, api, toast, render } from './app.js';

const when = (ms) => new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });

async function load() {
  try { state.tokens = (await api('/api/tokens')).tokens; } catch (err) { state.tokens = []; toast(err.message, true); }
  render();
}

export function settingsPanel() {
  // undefined: not loaded yet; null: loading.
  if (state.tokens === undefined) { state.tokens = null; load(); }
  const list = state.tokens || [];
  const fresh = state.newToken;
  return `
    <section class="panel narrow" id="tokens">
      <h3>Claude Code access</h3>
      <p class="muted small">A token lets a script read your plan (and, if you allow it, add homework and catch-ups or mark sessions).
        Put it in the <code>.env</code> of your <b>plate</b> repo, then ask Claude Code “what should I do today?”.
        It can't change calendars, tokens or your account.</p>
      ${fresh ? `<div class="banner"><span>Copy it now, it won't be shown again: <code class="token">${esc(fresh)}</code></span>
        <button class="btn btn-small" data-token-copy>Copy</button></div>` : ''}
      <div class="list">${list.map((t) => `
        <div class="cal-src" data-token="${t.id}">
          <span class="cal-src-icon"><svg class="ic" aria-hidden="true"><use href="#i-keyboard"/></svg></span>
          <div class="cal-src-main">
            <div class="cal-src-name">${esc(t.name)}</div>
            <div class="cal-src-meta">${t.scope === 'write' ? 'read and write' : 'read only'} · created ${when(t.created_at)} · ${t.last_used_at ? `used ${when(t.last_used_at)}` : 'never used'}</div>
          </div>
          <div class="cal-src-actions"><button class="btn btn-borderless btn-small" data-token-revoke title="Revoke" aria-label="Revoke"><svg class="ic" aria-hidden="true"><use href="#i-trash"/></svg></button></div>
        </div>`).join('')}</div>
      <form id="token-form" class="cal-add-row" autocomplete="off">
        <input class="field" name="name" maxlength="60" required placeholder="Name, e.g. MacBook Claude Code" aria-label="Token name">
        <div class="select"><select name="scope" aria-label="Access">
          <option value="write">Read and write</option><option value="read">Read only</option>
        </select></div>
        <button class="btn btn-primary" type="submit">Create token</button>
      </form>
    </section>`;
}

export function onClick(e) {
  const t = e.target;
  if (t.closest('[data-token-copy]')) {
    navigator.clipboard.writeText(state.newToken).then(() => toast('Token copied.'), () => toast('Copy failed: select it by hand.', true));
    return true;
  }
  const revoke = t.closest('[data-token-revoke]');
  if (revoke) {
    const id = Number(revoke.closest('[data-token]').dataset.token);
    const tok = state.tokens.find((x) => x.id === id);
    if (!confirm(`Revoke "${tok.name}"? Scripts using it stop working.`)) return true;
    api(`/api/tokens/${id}`, { method: 'DELETE' })
      .then(() => { state.tokens = state.tokens.filter((x) => x.id !== id); state.newToken = null; render(); toast('Token revoked.'); })
      .catch((err) => toast(err.message, true));
    return true;
  }
  return false;
}

export function onSubmit(e) {
  if (e.target.id !== 'token-form') return false;
  e.preventDefault();
  const fd = new FormData(e.target);
  api('/api/tokens', { method: 'POST', body: { name: fd.get('name'), scope: fd.get('scope') } })
    .then(({ token, ...row }) => { state.tokens = [...(state.tokens || []), row]; state.newToken = token; render(); })
    .catch((err) => toast(err.message, true));
  return true;
}
