import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { d1 } from './d1-shim.js';
import { callModel, saveAiKey, getAiKey, keyConfig, deleteAiKey } from '../src/llm.js';
import { chat, usage } from '../src/assistant.js';
import { PROVIDERS, PROVIDER_ORDER } from '../public/ai-providers.js';

let env, user, calls, reply;
const ACCT = '0123456789abcdef0123456789abcdef';
beforeEach(() => {
  env = { DB: d1(), TOKEN_KEY: btoa(String.fromCharCode(...new Uint8Array(32).fill(3))), AI_SHARED_DAILY_NEURONS: '1' };
  env.DB.raw.exec(`INSERT INTO users (id, google_sub, email, threshold, unit, created_at) VALUES (1, 'g', 'a@b.c', 0.8, 'sessions', 0)`);
  user = env.DB.raw.prepare('SELECT * FROM users WHERE id = 1').get();
  calls = [];
  reply = (url) => ({ status: 200, body: url.includes('/ai/run/')
    ? { success: true, result: { choices: [{ message: { content: 'OK' } }] } }
    : { choices: [{ message: { content: 'OK' } }] } });
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init, body: JSON.parse(init.body) });
    const r = reply(url, init);
    return new Response(JSON.stringify(r.body), { status: r.status });
  };
});

test('every provider has a working default model and a key URL', () => {
  for (const id of PROVIDER_ORDER) {
    const p = PROVIDERS[id];
    assert.ok(p.models.some((m) => m.id === p.defaultModel), id);
    assert.match(p.keyUrl, /^https:\/\//, id);
    assert.ok(['cloudflare', 'openai'].includes(p.kind), id);
    if (p.kind === 'openai') assert.match(p.baseUrl, /^https:\/\/[^ ]+[^/]$/, id);
  }
});

test('cloudflare BYOK: account URL, bearer token, tools, result unwrapped', async () => {
  const cfg = { provider: 'cloudflare', apiKey: 'tok_abcdefghijkl', accountId: ACCT, model: PROVIDERS.cloudflare.defaultModel };
  const r = await callModel(env, cfg, [{ role: 'user', content: 'hi' }, { role: 'tool', tool_call_id: 'x', tool_name: 'f', content: '{}' }], { tools: [{ type: 'function' }] });
  assert.equal(r.message.content, 'OK');
  assert.equal(calls[0].url, `https://api.cloudflare.com/client/v4/accounts/${ACCT}/ai/run/@cf/google/gemma-4-26b-a4b-it`);
  assert.equal(calls[0].init.headers.Authorization, 'Bearer tok_abcdefghijkl');
  assert.deepEqual(calls[0].body.chat_template_kwargs, { enable_thinking: false });
  assert.equal(calls[0].body.tools.length, 1);
  assert.equal(calls[0].body.messages[1].tool_name, undefined);   // internal field stripped
});

test('provider errors become clear messages', async () => {
  const cfg = { provider: 'cloudflare', apiKey: 'tok_abcdefghijkl', accountId: ACCT, model: PROVIDERS.cloudflare.defaultModel };
  reply = () => ({ status: 401, body: { errors: [{ message: 'Authentication error' }] } });
  await assert.rejects(callModel(env, cfg, []), /rejected your API key: Authentication error/);
  reply = () => ({ status: 429, body: { errors: [{ message: 'daily free allocation exceeded' }] } });
  await assert.rejects(callModel(env, cfg, []), /rate limit or daily quota/);
});

test('saveAiKey validates, tests the key, stores it encrypted; delete removes it', async () => {
  await assert.rejects(saveAiKey(env, user, { provider: 'nope', apiKey: 'x'.repeat(20) }), /Unknown provider/);
  await assert.rejects(saveAiKey(env, user, { provider: 'cloudflare', apiKey: 'x'.repeat(20), accountId: 'bad' }), /32-character/);
  await assert.rejects(saveAiKey(env, user, { provider: 'cloudflare', apiKey: 'has space here', accountId: ACCT }), /look like an API key/);
  reply = () => ({ status: 401, body: { errors: [{ message: 'Invalid API Token' }] } });
  await assert.rejects(saveAiKey(env, user, { provider: 'cloudflare', apiKey: 'tok_wrongwrongwrong', accountId: ACCT }), /rejected/);
  assert.equal(await getAiKey(env, 1), null);                    // nothing saved after a failed test

  reply = () => ({ status: 200, body: { result: { choices: [{ message: { content: 'OK' } }] } } });
  const k = await saveAiKey(env, user, { provider: 'cloudflare', apiKey: 'tok_secret_1234', accountId: ACCT });
  assert.equal(k.keyHint, '1234');
  const row = await getAiKey(env, 1);
  assert.ok(!row.api_key.includes('tok_secret'));                  // encrypted at rest
  assert.equal((await keyConfig(env, row)).apiKey, 'tok_secret_1234');
  await deleteAiKey(env, user);
  assert.equal(await getAiKey(env, 1), null);
});

test('chat with BYOK skips the shared budget and counts separately', async () => {
  env.AI = { run: async () => { throw new Error('shared allowance must not be used'); } };
  env.DB.raw.exec(`INSERT INTO ai_usage (user_id, day, neurons) VALUES (1, '${new Date().toISOString().slice(0, 10)}', 99999)`);
  await assert.rejects(chat(env, user, { messages: [{ role: 'user', content: 'hi' }] }, 'https://app'), /shared limit|unavailable/);
  reply = () => ({ status: 200, body: { result: { choices: [{ message: { content: 'From your key' } }] } } });
  await saveAiKey(env, user, { provider: 'cloudflare', apiKey: 'tok_secret_1234', accountId: ACCT });
  const r = await chat(env, user, { messages: [{ role: 'user', content: 'hi' }] }, 'https://app');
  assert.equal(r.reply, 'From your key');
  assert.equal(r.usage.byok.provider, 'cloudflare');
  const u = await usage(env, user);
  assert.equal(u.used, 1);
  assert.equal(u.limit, 300);
  const row = env.DB.raw.prepare('SELECT messages, byok_messages FROM ai_usage WHERE user_id = 1').get();
  assert.deepEqual({ ...row }, { messages: 0, byok_messages: 1 });
});

test('OpenAI-compatible providers: base URL, model, tools, no `name` on tool messages for Groq', async () => {
  const cfg = { provider: 'groq', apiKey: 'gsk_testtesttest', model: 'openai/gpt-oss-120b' };
  const r = await callModel(env, cfg, [{ role: 'user', content: 'hi' }, { role: 'tool', tool_call_id: 'c1', tool_name: 'f', content: '{}' }], { tools: [{ type: 'function' }] });
  assert.equal(r.message.content, 'OK');
  assert.equal(calls[0].url, 'https://api.groq.com/openai/v1/chat/completions');
  assert.equal(calls[0].body.model, 'openai/gpt-oss-120b');
  assert.equal(calls[0].body.tool_choice, 'auto');
  assert.equal(calls[0].body.messages[1].name, undefined);
  assert.equal(calls[0].body.messages[1].tool_name, undefined);
  await callModel(env, { provider: 'openrouter', apiKey: 'sk-or-testtest', model: PROVIDERS.openrouter.defaultModel }, []);
  assert.equal(calls[1].url, 'https://openrouter.ai/api/v1/chat/completions');
  assert.equal(calls[1].init.headers['X-OpenRouter-Title'], 'Attendance80');
});
