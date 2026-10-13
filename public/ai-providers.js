// Providers a user can bring their own (free) key for. Shared by the Settings UI, the /byok-guide page
// and the Worker (src/llm.js). Only models that handle the assistant's tool calling are listed.
// Facts checked 2026-10-13 against each provider's docs/terms; see /byok-guide for the user-facing summary.
//
// kind: 'cloudflare' = Workers AI REST API (needs an account id); 'openai' = OpenAI-compatible chat completions.
// toolMessageName: provider wants `name` on role:"tool" messages (Groq rejects it, so only set when required).

export const PROVIDERS = {
  cloudflare: {
    label: 'Cloudflare Workers AI',
    kind: 'cloudflare',
    recommended: true,
    needsAccountId: true,
    free: 'about 100 messages a day (10,000 free neurons)',
    noCard: true,
    privacy: 'not used for training',
    keyUrl: 'https://dash.cloudflare.com/?to=/:account/ai/workers-ai',
    keyPlaceholder: 'Workers AI API token',
    defaultModel: '@cf/google/gemma-4-26b-a4b-it',
    models: [
      // Same model as the shared allowance; thinking off keeps answers fast (~4 s).
      { id: '@cf/google/gemma-4-26b-a4b-it', label: 'Gemma 4 26B (recommended)', extra: { chat_template_kwargs: { enable_thinking: false } } },
    ],
  },
  groq: {
    label: 'Groq',
    kind: 'openai',
    baseUrl: 'https://api.groq.com/openai/v1',
    free: 'about 25 messages a day (200,000 free tokens/day), very fast',
    privacy: 'not used for training (contract)',
    age: 18,
    keyUrl: 'https://console.groq.com/keys',
    keyPlaceholder: 'gsk_…',
    defaultModel: 'openai/gpt-oss-120b',
    models: [
      { id: 'openai/gpt-oss-120b', label: 'GPT-OSS 120B (recommended)' },
      { id: 'llama-3.3-70b-versatile', label: 'Llama 3.3 70B' },
    ],
  },
  openrouter: {
    label: 'OpenRouter (free models)',
    kind: 'openai',
    baseUrl: 'https://openrouter.ai/api/v1',
    headers: { 'HTTP-Referer': 'https://epita.knhdixh.com', 'X-OpenRouter-Title': 'Attendance80' },
    free: 'about 20 messages a day (50 free requests/day)',
    noCard: true,
    privacy: 'free models may log or train on prompts',
    age: 18,
    keyUrl: 'https://openrouter.ai/settings/keys',
    keyPlaceholder: 'sk-or-…',
    defaultModel: 'nvidia/nemotron-3-super-120b-a12b:free',
    models: [
      { id: 'nvidia/nemotron-3-super-120b-a12b:free', label: 'Nemotron 3 Super 120B (free)' },
      { id: 'google/gemma-4-31b-it:free', label: 'Gemma 4 31B (free)' },
    ],
  },
};

export const PROVIDER_ORDER = ['cloudflare', 'groq', 'openrouter'];
