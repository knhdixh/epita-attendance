// Calls the assistant's model: the app's own Workers AI binding (shared allowance), or the user's own key
// ("bring your own key") on Cloudflare Workers AI or an OpenAI-compatible provider. Every path returns the
// same normalized shape: { message: { content, tool_calls }, neurons }.
import { HttpError } from './http.js';
import { PROVIDERS } from '../public/ai-providers.js';
import { encrypt, decrypt } from './gcal.js';

const TIMEOUT_MS = 60000;

// ---------------------------------------------------------------- stored keys

export async function getAiKey(env, userId) {
  return env.DB.prepare('SELECT * FROM ai_keys WHERE user_id = ?').bind(userId).first();
}

// Public view of a user's key settings (never includes the key).
export function describeKey(row) {
  if (!row) return null;
  const p = PROVIDERS[row.provider];
  return { provider: row.provider, label: p?.label || row.provider, model: row.model, keyHint: row.key_hint,
    accountId: row.account_id, lastError: row.last_error, lastUsedAt: row.last_used_at };
}

export async function saveAiKey(env, user, { provider, apiKey, accountId, model }) {
  const p = PROVIDERS[provider];
  if (!p) throw new HttpError(400, 'Unknown provider.');
  apiKey = String(apiKey || '').trim();
  if (apiKey.length < 10 || apiKey.length > 400 || /\s/.test(apiKey)) throw new HttpError(400, 'That doesn\'t look like an API key.');
  if (p.needsAccountId) {
    accountId = String(accountId || '').trim();
    if (!/^[0-9a-f]{32}$/i.test(accountId)) throw new HttpError(400, 'The Cloudflare Account ID is a 32-character code (letters a–f and digits).');
  }
  model = String(model || p.defaultModel);
  if (!p.models.some((m) => m.id === model)) throw new HttpError(400, 'Pick one of the listed models.');
  const cfg = { provider, apiKey, accountId: p.needsAccountId ? accountId : null, model };

  // Test the key with a tiny request before saving, so mistakes show up now rather than mid-chat.
  await callModel(env, cfg, [{ role: 'user', content: 'Reply with the single word OK.' }], { maxTokens: 16 });

  await env.DB.prepare(`INSERT INTO ai_keys (user_id, provider, account_id, api_key, key_hint, model, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (user_id) DO UPDATE SET provider = excluded.provider, account_id = excluded.account_id,
      api_key = excluded.api_key, key_hint = excluded.key_hint, model = excluded.model, last_error = NULL`)
    .bind(user.id, provider, cfg.accountId, await encrypt(env, apiKey), apiKey.slice(-4), model, Date.now()).run();
  return describeKey(await getAiKey(env, user.id));
}

export async function deleteAiKey(env, user) {
  await env.DB.prepare('DELETE FROM ai_keys WHERE user_id = ?').bind(user.id).run();
}

// Decrypted config for a stored key, or null to use the shared allowance.
export async function keyConfig(env, row) {
  if (!row) return null;
  return { provider: row.provider, apiKey: await decrypt(env, row.api_key), accountId: row.account_id, model: row.model };
}

// ---------------------------------------------------------------- calling models

async function postJson(url, headers, body, providerLabel) {
  let res;
  try {
    res = await fetch(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (e) {
    throw new HttpError(502, `${providerLabel} didn't respond (${e.name === 'TimeoutError' ? 'timed out' : 'network error'}). Try again.`);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const detail = data.error?.message || data.errors?.[0]?.message || data.message || (typeof data.error === 'string' ? data.error : '');
    if (res.status === 401 || res.status === 403) {
      throw new HttpError(400, `${providerLabel} rejected your API key${detail ? `: ${detail}` : ''}. Check it in Settings → Assistant.`);
    }
    if (res.status === 429) {
      throw new HttpError(429, `${providerLabel}: you've hit your free plan's rate limit or daily quota${detail ? ` (${detail})` : ''}. Wait a bit, or switch provider in Settings → Assistant.`);
    }
    throw new HttpError(502, `${providerLabel} returned an error (${res.status})${detail ? `: ${detail}` : ''}.`);
  }
  return data;
}

/**
 * cfg: null for the shared allowance, else { provider, apiKey, accountId, model }.
 * messages: OpenAI-style chat messages (system/user/assistant with tool_calls/tool).
 */
export async function callModel(env, cfg, messages, { tools, maxTokens = 1200, sharedModel } = {}) {
  // `tool_name` is our bookkeeping on tool results; only providers that require `name` get it (as `name`).
  const keepName = Boolean(cfg && PROVIDERS[cfg.provider]?.toolMessageName);
  messages = messages.map(({ tool_name, ...m }) => (keepName && m.role === 'tool' && !m.name ? { ...m, name: tool_name } : m));
  // Shared allowance: the app's own Workers AI binding.
  if (!cfg) {
    // Reasoning made Gemma ~4x slower (15 s vs 4 s per call) without better answers for this app.
    const input = { messages, max_tokens: maxTokens, temperature: 0.3 };
    if (env.AI_THINKING !== 'true') input.chat_template_kwargs = { enable_thinking: false };
    if (tools) input.tools = tools;
    let res;
    try {
      res = await env.AI.run(sharedModel, input);
    } catch (e) {
      console.error('AI.run failed, retrying once', e);
      try { res = await env.AI.run(sharedModel, input); } catch (e2) {
        console.error('AI.run failed', e2);
        throw new HttpError(503, "The AI service is unavailable right now, or today's shared allowance is used up. Try again later, or use your own free key (Settings → Assistant).");
      }
    }
    return { message: res.choices?.[0]?.message || { content: res.response }, neurons: res.usage?.neurons || 0 };
  }

  const p = PROVIDERS[cfg.provider];
  if (!p) throw new HttpError(400, 'Unknown AI provider. Check Settings → Assistant.');
  const model = p.models.find((m) => m.id === cfg.model) || p.models[0];

  if (p.kind === 'cloudflare') {
    const input = { messages, max_tokens: maxTokens, temperature: 0.3, ...(model.extra || {}) };
    if (tools) input.tools = tools;
    const data = await postJson(`https://api.cloudflare.com/client/v4/accounts/${cfg.accountId}/ai/run/${model.id}`,
      { Authorization: `Bearer ${cfg.apiKey}` }, input, p.label);
    const r = data.result || {};
    return { message: r.choices?.[0]?.message || { content: r.response }, neurons: 0 };
  }

  // OpenAI-compatible chat completions.
  const body = {
    model: model.id, temperature: 0.3, max_tokens: maxTokens, ...(model.extra || {}),
    messages,
  };
  if (tools) { body.tools = tools; body.tool_choice = 'auto'; }
  const data = await postJson(`${p.baseUrl}/chat/completions`, { Authorization: `Bearer ${cfg.apiKey}`, ...(p.headers || {}) }, body, p.label);
  const message = data.choices?.[0]?.message;
  if (!message) throw new HttpError(502, `${p.label} returned an empty answer. Try again or pick another model.`);
  return { message, neurons: 0 };
}
