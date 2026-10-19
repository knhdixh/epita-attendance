// "Bring your own key" for the assistant: the Settings panel, the first-visit intro on the Assistant tab,
// and the call-to-action shown when the shared allowance runs out.
import { state, esc, api, toast, render } from './app.js';
import { PROVIDERS, PROVIDER_ORDER } from './ai-providers.js';

const INTRO_KEY = () => `a80-ai-intro-${state.data?.user?.email || state.data?.user?.name || ''}`;
const form = () => (state.byok ??= { provider: PROVIDER_ORDER[0], model: '', saving: false });

export function introDismissed() {
  try { return localStorage.getItem(INTRO_KEY()) === '1'; } catch { return true; }
}
function dismissIntro() {
  try { localStorage.setItem(INTRO_KEY(), '1'); } catch { /* storage unavailable: show again next time */ }
}

const freeLine = (p) => `${esc(p.free)}${p.noCard ? ' · no card' : ''}`;

// Shown on the Assistant tab until the user picks an option.
export function introCard() {
  const info = state.data.assistant;
  if (info?.byok || introDismissed()) return '';
  return `
    <section class="panel byok-intro">
      <h3>Two ways to use the assistant</h3>
      <div class="byok-options">
        <div class="byok-option">
          <b>Shared allowance</b>
          <p class="muted small">Works right away. ${info.limit} messages a day, and it can run out when many students use it.</p>
          <button class="btn btn-default" data-byok="use-shared">Use the shared allowance</button>
        </div>
        <div class="byok-option recommended">
          <b>Your own free key <span class="chip">recommended</span></b>
          <p class="muted small">About 3 minutes to set up, free on every option below, with no app limits.
            It's your own quota, so other students can't use it up.</p>
          <div class="byok-actions">
            <a class="btn btn-primary" href="#settings" data-byok="setup">Set up my free key</a>
            <a class="small" href="/byok-guide" target="_blank" rel="noopener">Step-by-step guide</a>
          </div>
        </div>
      </div>
      <p class="muted small">Free options: ${PROVIDER_ORDER.map((id) => esc(PROVIDERS[id].label)).join(', ')}.</p>
    </section>`;
}

// Button under an error message when a shared limit was hit.
export const limitCta = () => `<a class="btn btn-default btn-small" href="#settings" data-byok="setup">Add a free key →</a>`;

export function settingsPanel() {
  const info = state.data.assistant;
  if (!info) return '';
  const f = form();
  const p = PROVIDERS[f.provider];
  const model = f.model && p.models.some((m) => m.id === f.model) ? f.model : p.defaultModel;
  const k = info.byok;
  return `
    <section class="panel narrow" id="assistant-settings">
      <h3>Assistant</h3>
      ${k ? `
        <div class="method">
          <div><b>Using your ${esc(k.label)} key</b> <span class="muted small">…${esc(k.keyHint)}</span>
            <div class="muted small">Model ${esc(PROVIDERS[k.provider]?.models.find((m) => m.id === k.model)?.label || k.model)}
              ${k.lastUsedAt ? ` · last used ${new Date(k.lastUsedAt).toLocaleDateString()}` : ''}</div>
            ${k.lastError ? `<div class="note bad">${esc(k.lastError)}</div>` : ''}</div>
          <button class="btn btn-borderless btn-small" data-byok="remove">Remove</button>
        </div>
        <p class="muted small">Messages use your own ${esc(k.label)} quota. Remove the key to go back to the shared allowance.</p>`
      : `<p class="muted small">Using the <b>shared allowance</b>: ${info.limit} messages a day, which can run out when many students use it.
          Add your own <b>free</b> key to use your own quota instead. <a href="/byok-guide" target="_blank" rel="noopener">Guide</a></p>`}
      <form id="byok-form" class="form byok-form" autocomplete="off">
        <label>${k ? 'Switch to another key' : 'Provider'}
          <div class="select"><select name="provider">${PROVIDER_ORDER.map((id) => `<option value="${id}" ${id === f.provider ? 'selected' : ''}>${esc(PROVIDERS[id].label)}${PROVIDERS[id].recommended ? ' (recommended)' : ''}</option>`).join('')}</select></div>
        </label>
        <p class="muted small byok-meta">${freeLine(p)} · ${esc(p.privacy)}<br>
          <a href="${esc(p.keyUrl)}" target="_blank" rel="noopener">Get a key ↗</a> ·
          <a href="/byok-guide#${esc(f.provider)}" target="_blank" rel="noopener">How to</a></p>
        ${p.needsAccountId ? `<label>Account ID <input class="field" name="accountId" required pattern="[0-9a-fA-F]{32}" placeholder="32 characters, e.g. 7d5d36…" spellcheck="false"></label>` : ''}
        <label>API key <input class="field" name="apiKey" type="password" required placeholder="${esc(p.keyPlaceholder || 'Paste your key')}" spellcheck="false" autocomplete="off"></label>
        <label>Model
          <div class="select"><select name="model">${p.models.map((m) => `<option value="${esc(m.id)}" ${m.id === model ? 'selected' : ''}>${esc(m.label)}</option>`).join('')}</select></div>
        </label>
        <button class="btn btn-primary" type="submit" ${f.saving ? 'disabled' : ''}>${f.saving ? 'Testing your key…' : 'Test & save'}</button>
      </form>
      <p class="muted small">Your key is tested first, then stored encrypted. It's never shown again or sent back to your browser.
        You can revoke it any time on the provider's website.</p>
    </section>`;
}

// ---------------------------------------------------------------- handlers (return true if handled)

export function onClick(e) {
  const b = e.target.closest('[data-byok]');
  if (!b) return false;
  const action = b.dataset.byok;
  if (action === 'use-shared') { dismissIntro(); render(); return true; }
  if (action === 'setup') {
    e.preventDefault();
    dismissIntro();
    state.tab = 'settings';
    history.replaceState(null, '', '#settings');
    render();
    document.getElementById('assistant-settings')?.scrollIntoView({ block: 'start', behavior: 'smooth' });
    return true;
  }
  if (action === 'remove') { remove(); return true; }
  return false;
}

export function onChange(e) {
  const f = e.target.closest('#byok-form');
  if (!f) return false;
  if (e.target.name === 'provider') {
    form().provider = e.target.value;
    form().model = '';
    render();
    document.getElementById('assistant-settings')?.scrollIntoView({ block: 'start' });
  } else if (e.target.name === 'model') {
    form().model = e.target.value;
  }
  return true;
}

export function onSubmit(e) {
  if (e.target.id !== 'byok-form') return false;
  e.preventDefault();
  save(new FormData(e.target));
  return true;
}

async function save(fd) {
  const f = form();
  f.saving = true;
  render();
  try {
    const r = await api('/api/ai-key', { method: 'PUT', body: {
      provider: fd.get('provider'), apiKey: fd.get('apiKey'), accountId: fd.get('accountId') || undefined, model: fd.get('model'),
    } });
    state.data.assistant = r.usage;
    dismissIntro();
    toast(`Key works. The assistant now uses your ${r.key.label} account.`);
  } catch (err) {
    toast(err.message, true);
  } finally {
    f.saving = false;
    render();
    document.getElementById('assistant-settings')?.scrollIntoView({ block: 'start' });
  }
}

async function remove() {
  if (!confirm('Remove your key? The assistant will go back to the shared allowance.')) return;
  try {
    const r = await api('/api/ai-key', { method: 'DELETE' });
    state.data.assistant = r.usage;
    toast('Key removed.');
    render();
  } catch (err) { toast(err.message, true); }
}
