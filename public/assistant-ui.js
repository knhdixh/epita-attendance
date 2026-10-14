// AI assistant tab: chat with the Workers AI assistant, with Undo for the changes it makes.
import { state, esc, api, toast, render, reload } from './app.js';
import { introCard, limitCta } from './byok-ui.js';

const $app = document.getElementById('app');
const STORE_KEY = () => `a80-chat-${state.data?.user?.email || ''}`;
const KEEP = 40; // messages kept in browser storage

const SUGGESTIONS = [
  ['What\'s due this week?', 'What homework is due in the next 7 days?'],
  ['Can I skip a class?', 'Which classes this week could I skip safely, and which must I attend?'],
  ['Weekly plan', 'Make me a study plan for this week: which classes to prioritise, homework deadlines in order, and when to work on each around my schedule.'],
  ['Plan a break', 'When could I take a week off in the next two months without dropping below the minimum?'],
  ['Anything unmarked?', 'Which past sessions haven\'t I marked yet?'],
  ['I missed today', 'I missed all my classes today, mark them absent.'],
  ['Add homework…', null],
];

function chatState() {
  if (!state.chat) {
    let messages = [];
    try { messages = JSON.parse(localStorage.getItem(STORE_KEY()) || '[]'); } catch { /* storage unavailable */ }
    state.chat = { messages: Array.isArray(messages) ? messages : [], pending: false, draft: '' };
  }
  return state.chat;
}

function persist() {
  try { localStorage.setItem(STORE_KEY(), JSON.stringify(state.chat.messages.slice(-KEEP))); } catch { /* ignore */ }
}

// Small, safe Markdown subset: headings, lists, bold/italic, inline code, code blocks, paragraphs.
function markdown(src) {
  const inline = (s) => esc(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\s][^*]*)\*/g, '$1<em>$2</em>');
  const out = [];
  let list = null, para = [];
  const flushPara = () => { if (para.length) { out.push(`<p>${para.map(inline).join('<br>')}</p>`); para = []; } };
  const flushList = () => { if (list) { out.push(`<${list.tag}>${list.items.map((i) => `<li>${inline(i)}</li>`).join('')}</${list.tag}>`); list = null; } };
  const lines = src.replace(/\r/g, '').split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith('```')) {
      flushPara(); flushList();
      const code = [];
      while (++i < lines.length && !lines[i].startsWith('```')) code.push(lines[i]);
      out.push(`<pre><code>${esc(code.join('\n'))}</code></pre>`);
      continue;
    }
    let m;
    if ((m = /^#{1,6}\s+(.*)$/.exec(line))) { flushPara(); flushList(); out.push(`<h4>${inline(m[1])}</h4>`); continue; }
    if ((m = /^\s*(?:[-*•]|(\d+)[.)])\s+(.*)$/.exec(line))) {
      flushPara();
      const tag = m[1] ? 'ol' : 'ul';
      if (list && list.tag !== tag) flushList();
      list ??= { tag, items: [] };
      list.items.push(m[2]);
      continue;
    }
    if (!line.trim()) { flushPara(); flushList(); continue; }
    flushList();
    para.push(line);
  }
  flushPara(); flushList();
  return out.join('');
}

function actionChip(a, mi, ai) {
  const undo = a.undone ? '<span class="muted small">undone</span>'
    : `<button class="btn small ghost" data-undo="${mi}:${ai}">Undo</button>`;
  if (a.type === 'homework_added') {
    const h = a.homework;
    const due = new Date(h.due_at).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', ...(h.due_time ? { hour: '2-digit', minute: '2-digit' } : {}) });
    return `<div class="action">Added homework <b>${esc(h.title)}</b>, due ${esc(due)}${h.gcal_event_id ? ' · in Google Calendar' : ''} ${undo}</div>`;
  }
  if (a.type === 'homework_updated') {
    return `<div class="action">${a.homework.done ? 'Marked done' : 'Marked not done'}: <b>${esc(a.homework.title)}</b> ${undo}</div>`;
  }
  if (a.type === 'break_saved') {
    const b = a.break;
    return `<div class="action">Saved break <b>${esc(b.label || 'Break')}</b> ${esc(b.start_date)} – ${esc(b.end_date)}: ${a.sessions} session${a.sessions === 1 ? '' : 's'} marked as planned absences ${undo}</div>`;
  }
  if (a.type === 'attendance') {
    const label = { present: 'attended', absent: 'absent', excused: 'excused', null: 'unmarked' };
    return `<div class="action">Marked ${a.changes.length} session${a.changes.length > 1 ? 's' : ''} ${label[a.changes[0].to]}:
      ${a.changes.map((c) => esc(c.label)).join('; ')} ${undo}</div>`;
  }
  return '';
}

export function renderAssistant() {
  const chat = chatState();
  const info = state.data.assistant;
  if (!info) {
    $app.innerHTML = '<section class="panel narrow"><h3>Assistant</h3><p class="muted">The AI assistant is not configured on this server.</p></section>';
    return;
  }
  $app.innerHTML = `
    <section class="chat">
      <header class="chat-head">
        <h3>Assistant</h3>
        <span class="muted small">${info.byok
          ? `Your ${esc(info.byok.label)} key · ${info.used} today`
          : `Shared · ${info.used} / ${info.limit} today · <a href="#settings" data-byok="setup">use your own free key</a>`}</span>
        ${chat.messages.length ? '<button class="btn small ghost" data-action="chat-clear">Clear chat</button>' : ''}
      </header>
      ${introCard()}
      <div class="chat-log" id="chat-log">
        ${chat.messages.length ? '' : `
          <div class="chat-empty">
            <p>Ask about your attendance, sessions and homework. I can also mark attendance and add homework for you.</p>
            <div class="chips">${SUGGESTIONS.map(([label, prompt], i) => `<button class="btn small" data-suggest="${i}">${esc(label)}</button>`).join('')}</div>
          </div>`}
        ${chat.messages.map((m, mi) => `
          <div class="msg ${m.role}${m.error ? ' error' : ''}">
            <div class="bubble">${m.role === 'assistant' ? markdown(m.content) : esc(m.content).replace(/\n/g, '<br>')}</div>
            ${(m.actions || []).map((a, ai) => actionChip(a, mi, ai)).join('')}
            ${m.error && /own free key|Settings → Assistant/.test(m.content) && mi === chat.messages.length - 1 ? limitCta() : ''}
          </div>`).join('')}
        ${chat.pending ? '<div class="msg assistant"><div class="bubble typing"><span></span><span></span><span></span></div></div>' : ''}
      </div>
      ${chat.messages.length ? `<div class="chips compact">${SUGGESTIONS.slice(0, 4).map(([label], i) => `<button class="btn small ghost" data-suggest="${i}">${esc(label)}</button>`).join('')}</div>` : ''}
      <form id="chat-form" class="chat-input">
        <textarea name="message" rows="1" maxlength="4000" placeholder="e.g. I missed French today · Add TP4 due at the next Databases class" ${chat.pending ? 'disabled' : ''}>${esc(chat.draft)}</textarea>
        <button class="btn primary" type="submit" ${chat.pending ? 'disabled' : ''}>Send</button>
      </form>
      <p class="muted small chat-note">${info.byok
        ? `Runs on your ${esc(info.byok.label)} account. Your messages and schedule are sent to ${esc(info.byok.label)}.`
        : 'Runs on Cloudflare Workers AI. Your messages and schedule are sent to the model but not used for training.'}
        The AI can make mistakes; every change it makes shows above with Undo.</p>
    </section>`;
  const log = document.getElementById('chat-log');
  log.scrollTop = log.scrollHeight;
  const ta = document.querySelector('#chat-form textarea');
  autosize(ta);
  if (!chat.pending && matchMedia('(pointer: fine)').matches) ta.focus();
}

function autosize(ta) { ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 200) + 'px'; }

async function send(text) {
  const chat = chatState();
  text = text.trim();
  if (!text || chat.pending) return;
  chat.messages.push({ role: 'user', content: text });
  chat.draft = '';
  chat.pending = true;
  persist();
  render();
  try {
    const history = chat.messages.filter((m) => !m.error).map(({ role, content }) => ({ role, content }));
    const r = await api('/api/assistant/chat', {
      method: 'POST', body: { messages: history, tz: Intl.DateTimeFormat().resolvedOptions().timeZone },
    });
    chat.messages.push({ role: 'assistant', content: r.reply, actions: r.actions });
    state.data.assistant = r.usage;
    if (r.actions.length) {
      const keep = state.data.assistant;
      await reload();
      state.data.assistant = keep;
    }
  } catch (err) {
    chat.messages.push({ role: 'assistant', content: err.message, error: true });
  } finally {
    chat.pending = false;
    persist();
    if (state.tab === 'assistant') render();
  }
}

async function undo(mi, ai) {
  const action = state.chat.messages[mi]?.actions?.[ai];
  if (!action || action.undone) return;
  try {
    if (action.type === 'homework_added') {
      await api(`/api/homework/${action.homework.id}`, { method: 'DELETE' }).catch((e) => {
        if (!/not found/i.test(e.message)) throw e; // already deleted elsewhere
      });
    } else if (action.type === 'homework_updated') {
      await api(`/api/homework/${action.homework.id}`, { method: 'PATCH', body: { done: Boolean(action.from.done) } });
    } else if (action.type === 'break_saved') {
      await api(`/api/breaks/${action.break.id}`, { method: 'DELETE' }).catch((e) => {
        if (!/not found/i.test(e.message)) throw e;
      });
    } else if (action.type === 'attendance') {
      for (const c of action.changes) await api(`/api/attendance/${c.id}`, { method: 'PATCH', body: { status: c.from } });
    }
    action.undone = true;
    persist();
    const keep = state.data.assistant;
    await reload();
    state.data.assistant = keep;
    render();
    toast('Undone.');
  } catch (err) { toast(err.message, true); }
}

// ---------------------------------------------------------------- event handlers (return true if handled)

export function onClick(e) {
  const t = e.target;
  const sug = t.closest('[data-suggest]');
  if (sug) {
    const [label, prompt] = SUGGESTIONS[Number(sug.dataset.suggest)];
    if (prompt) send(prompt);
    else {
      const ta = document.querySelector('#chat-form textarea');
      ta.value = 'Add homework: ';
      chatState().draft = ta.value;
      ta.focus();
    }
    return true;
  }
  const u = t.closest('[data-undo]');
  if (u) { const [mi, ai] = u.dataset.undo.split(':').map(Number); undo(mi, ai); return true; }
  if (t.closest('[data-action="chat-clear"]')) {
    state.chat.messages = [];
    persist();
    render();
    return true;
  }
  return false;
}

export function onInput(e) {
  if (!e.target.matches('#chat-form textarea')) return false;
  chatState().draft = e.target.value;
  autosize(e.target);
  return true;
}

export function onKeydown(e) {
  if (!e.target.matches('#chat-form textarea')) return false;
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    send(e.target.value);
    return true;
  }
  return false;
}

export function onSubmit(e) {
  if (e.target.id !== 'chat-form') return false;
  e.preventDefault();
  send(e.target.elements.message.value);
  return true;
}

export const _internal = { markdown };
