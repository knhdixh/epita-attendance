// Focus: a Pomodoro timer tied to homework and courses, with stats. The timer state lives in localStorage (see
// public/focus.js), so it survives reloads and every tab shows the same timer; each finished focus is queued
// in localStorage and sent to POST /api/focus, so nothing is lost offline. A mini timer shows in the top bar and
// the sidebar on every page; the tab title counts down too.
import { state, esc, api, toast, render, courseById, fmtTime } from './app.js';
import * as F from './focus.js';

const $app = document.getElementById('app');
const TIMER_KEY = 'a80-focus';
const QUEUE_KEY = 'a80-focus-queue';
const LOG_KEY = 'a80-focus-log';     // tells other tabs about a new session
const MIN = 60000;
const RANGES = { week: [7, 'Week'], month: [30, '30 days'], year: [365, 'Year'] };
const HEAT_WEEKS = 53;

// ---------------------------------------------------------------- formatting

const pad = (n) => String(n).padStart(2, '0');
export function clock(ms) {
  const s = Math.ceil(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return h ? `${h}:${pad(m)}:${pad(s % 60)}` : `${pad(m)}:${pad(s % 60)}`;
}
// "40 min", "1 h 05 min"
const dur = (min) => {
  const m = Math.round(min);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h${m % 60 ? ` ${pad(m % 60)} min` : ''}`;
};
// "6.5 h" for totals, minutes under an hour
export const hrs = (min) => (min < 60 ? `${Math.round(min)} min` : `${Math.round(min / 6) / 10} h`);
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
const dayLabel = (ms) => {
  const diff = Math.round((F.dayStart(ms) - F.dayStart(Date.now())) / 86400000);
  return diff === 0 ? 'Today' : diff === -1 ? 'Yesterday' : new Date(ms).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
};

// ---------------------------------------------------------------- state

const settings = () => state.data?.focus?.settings ?? F.DEFAULT_SETTINGS;
const sessions = () => state.data?.focus?.sessions ?? [];
const read = (key, fallback) => { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } };
const write = (key, value) => { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage unavailable */ } };

function loadTimer() {
  const t = read(TIMER_KEY, null);
  const ok = t && (t.phase === 'idle' ? t.next in F.PHASES : t.phase in F.PHASES && Number.isFinite(t.endsAt) && Number.isFinite(t.startedAt));
  return ok ? t : F.idle();
}
let timer = loadTimer();
const save = (t) => { timer = t; write(TIMER_KEY, t); };
const newRun = () => (crypto.randomUUID ? crypto.randomUUID().replace(/-/g, '') : `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`);

export const running = () => timer.phase !== 'idle';
export const focusingOn = () => (timer.phase === 'focus' ? timer.target?.homework_id ?? null : null);

let byHw = { src: null, len: -1, map: new Map() };
export function focusedMinutes(hwId) {
  const list = sessions();
  if (byHw.src !== list || byHw.len !== list.length) byHw = { src: list, len: list.length, map: F.minutesByHomework(list) };
  return byHw.map.get(hwId) || 0;
}

// ---------------------------------------------------------------- recording (offline-safe)

function upsertSession(row) {
  const f = state.data?.focus;
  if (!f || !row) return;
  const i = f.sessions.findIndex((s) => s.client_id === row.client_id);
  if (i >= 0) f.sessions[i] = { ...f.sessions[i], ...row };
  else { f.sessions.push(row); f.sessions.sort((a, b) => a.started_at - b.started_at); }
}

function record(entry) {
  upsertSession({ ...entry, id: null });
  write(QUEUE_KEY, [...read(QUEUE_KEY, []).filter((x) => x.client_id !== entry.client_id), entry]);
  write(LOG_KEY, entry);
  flush();
}

let flushing = false;
async function flush() {
  if (flushing || !state.data) return;
  flushing = true;
  try {
    for (let q = read(QUEUE_KEY, []); q.length; q = read(QUEUE_KEY, [])) {
      const item = q[0];
      try {
        const row = await api('/api/focus', { method: 'POST', body: item });
        upsertSession(row);
        write(LOG_KEY, row);
      } catch (err) {
        // Offline, server trouble or the daily cap: keep it for later. A rejected session is dropped.
        if (!err.status || err.status >= 500 || err.status === 429) break;
        state.data.focus.sessions = sessions().filter((s) => s.client_id !== item.client_id);
        toast(`A focus session couldn't be saved: ${err.message}`, true);
      }
      write(QUEUE_KEY, read(QUEUE_KEY, []).filter((x) => x.client_id !== item.client_id));
    }
  } finally { flushing = false; }
  if (state.tab === 'focus' && safeToRender()) render();
}

// After /api/data is (re)loaded: sessions still waiting to be sent stay visible.
export function adopt(data) {
  for (const entry of read(QUEUE_KEY, [])) if (!data.focus.sessions.some((s) => s.client_id === entry.client_id)) data.focus.sessions.push({ ...entry, id: null });
  data.focus.sessions.sort((a, b) => a.started_at - b.started_at);
}

// The Focus page shows a year; /api/data brings eight weeks.
function ensureHistory() {
  const f = state.data.focus;
  const want = F.addDays(F.weekStart(Date.now()), -(HEAT_WEEKS - 1) * 7);
  if (f.since <= want || f.loading || f.failed) return;
  f.loading = true;
  api(`/api/focus?since=${want}`).then(({ sessions: list, since }) => {
    const known = new Set(list.map((s) => s.client_id));
    f.sessions = [...list, ...f.sessions.filter((s) => !known.has(s.client_id) && s.started_at >= since)].sort((a, b) => a.started_at - b.started_at);
    f.since = since;
  }).catch((err) => { f.failed = true; toast(err.message, true); })
    .finally(() => { f.loading = false; if (state.tab === 'focus' && safeToRender()) render(); });
}

// ---------------------------------------------------------------- sound and notifications

let audio = null;
function unlockAudio() {
  try {
    audio ??= new (window.AudioContext || window.webkitAudioContext)();
    if (audio.state === 'suspended') audio.resume();
  } catch { audio = null; }
}

// Two soft notes: rising when a break starts, falling when it's time to focus.
function chime(rising) {
  if (!settings().sound || !audio || audio.state !== 'running') return;
  const t0 = audio.currentTime + 0.02;
  (rising ? [660, 880] : [880, 660]).forEach((freq, i) => {
    const o = audio.createOscillator(), g = audio.createGain(), t = t0 + i * 0.2;
    o.type = 'sine';
    o.frequency.value = freq;
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(0.18, t + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.6);
    o.connect(g).connect(audio.destination);
    o.start(t); o.stop(t + 0.65);
  });
}

const canNotify = () => 'Notification' in window;
function askNotify() {
  if (settings().notify && canNotify() && Notification.permission === 'default') Notification.requestPermission().catch(() => {});
}

// A system notification while the page is hidden, a toast otherwise. Through the service worker when there is
// one (Android only shows notifications that way); clicking it opens the Focus page (see public/sw.js).
async function announce(title, body) {
  if (document.hidden && settings().notify && canNotify() && Notification.permission === 'granted') {
    const opts = { body, tag: 'a80-focus', renotify: true, icon: '/img/icon-192.png', badge: '/img/icon-192.png' };
    try {
      const reg = await navigator.serviceWorker?.getRegistration();
      if (reg) return await reg.showNotification(title, opts);
    } catch { /* fall back to a page notification */ }
    try {
      const n = new Notification(title, opts);
      n.onclick = () => { window.focus(); n.close(); };
      return;
    } catch { /* no notifications here: fall back to the toast */ }
  }
  toast(`${title} ${body}`);
}

// ---------------------------------------------------------------- actions

// F, Space and the main button: start, pause or resume.
export function toggle() {
  const now = Date.now();
  if (timer.phase === 'idle') return begin();
  unlockAudio();
  save(timer.pausedAt ? F.resume(timer, now) : F.pause(timer, now));
  changed();
}

function begin(target) {
  unlockAudio();
  askNotify();
  const t = target === undefined ? timer : { ...timer, target };
  save(F.start(t, settings(), Date.now(), newRun()));
  changed();
}

function commit({ log, next }) {
  save(next);
  if (log) record(log);
  changed();
}

// Skip: a focus ends now (recorded as stopped early) and its break follows; a break, running or next, is skipped.
export function skip() {
  if (timer.phase === 'idle') {
    if (timer.next === 'focus') return;
    save(F.idle(timer.next === 'long' ? 0 : timer.done, 'focus', timer.target));
    return changed();
  }
  unlockAudio();
  commit(F.finish(timer, settings(), Date.now(), newRun(), { early: true }));
}

export function stop() {
  if (timer.phase === 'idle') return;
  const r = F.stop(timer, Date.now());
  commit(r);
  if (r.log) toast(`Stopped. ${dur(r.log.focus_seconds / 60)} recorded.`);
}

const targetOf = (h) => ({ homework_id: h.id, course_id: h.course_id ?? null, label: h.title });

// "Focus on this" from a homework row, Today or the palette. A focus already running on something else is
// stopped (and recorded) first.
export function focusOn(hwId) {
  const h = state.data.homework.find((x) => x.id === hwId);
  if (!h) return;
  if (timer.phase === 'focus' && timer.target?.homework_id === hwId) {
    if (timer.pausedAt) toggle();
    return toast(`Already focusing on “${h.title}”.`);
  }
  if (timer.phase === 'focus') commit(F.stop(timer, Date.now()));
  save({ ...F.idle(timer.done, 'focus'), target: targetOf(h) });
  begin();
  toast(`Focusing on “${h.title}” for ${settings().focus} min.`);
}

function setTarget(value) {
  let target = null;
  const [kind, id] = value.split(':');
  if (kind === 'hw') { const h = state.data.homework.find((x) => x.id === Number(id)); if (h) target = targetOf(h); }
  if (kind === 'course') { const c = courseById()[Number(id)]; if (c) target = { homework_id: null, course_id: c.id, label: null }; }
  save({ ...timer, target });
  changed();
}

// ---------------------------------------------------------------- ticking

// Re-rendering a page would drop what's being typed, so a timer change only redraws the page when nothing is
// being edited; the mini timer and the clock are painted directly either way.
function safeToRender() {
  if (!state.data || document.body.classList.contains('signed-out')) return false;
  if (['assistant', 'settings', 'breaks'].includes(state.tab) || state.hwFormOpen || state.hwEditing) return false;
  return !document.activeElement?.closest('input, textarea, select, [contenteditable="true"]');
}

function changed() {
  syncTicker();
  if (safeToRender()) render();
  else if (state.tab === 'focus') document.getElementById('fx-timer')?.replaceWith(htmlNode(timerCard(Date.now())));
  paint();
}

const htmlNode = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstChild; };

// Two clocks: a 250 ms painter while the page is visible, and a one-shot alarm at the end of the phase. Chrome
// slows chained timers in background tabs to once a minute, which would make the alarm late, so the alarm is
// always armed from a fresh task (a MessageChannel message), never from inside another timer.
let ticker = null;
let alarm = null;
const armer = new MessageChannel();
armer.port1.onmessage = () => {
  clearTimeout(alarm);
  alarm = running() && !timer.pausedAt ? setTimeout(tick, Math.max(0, timer.endsAt - Date.now()) + 50) : null;
};
function syncTicker() {
  const paint = running() && !document.hidden;
  if (paint && !ticker) ticker = setInterval(tick, 250);
  if (!paint && ticker) { clearInterval(ticker); ticker = null; }
  armer.port2.postMessage(0);
}

function tick() {
  const now = Date.now();
  if (F.isDue(timer, now)) return phaseEnded(now);
  paint(now);
}

function phaseEnded(now) {
  // Another tab may already have moved on.
  const stored = loadTimer();
  if (stored.run !== timer.run || stored.phase !== timer.phase) { timer = stored; return changed(); }
  const ended = timer.phase, target = timer.target;
  const r = F.finish(timer, settings(), now, newRun());
  commit(r);
  const next = r.next, s = settings();
  const nextLen = next.phase === 'idle' ? s[next.next] : s[next.phase];
  if (ended === 'focus') {
    if (r.log && target?.homework_id) state.fxLast = { homework_id: target.homework_id, at: now };
    chime(true);
    const brk = (next.phase === 'idle' ? next.next : next.phase) === 'long' ? 'long' : 'short';
    announce(`Focus done${target?.label ? `: ${target.label}` : ''}.`,
      next.phase === 'idle' ? `Time for a ${nextLen}-minute ${brk} break.` : `${nextLen}-minute ${brk} break started.`);
  } else {
    chime(false);
    announce('Break over.', next.phase === 'idle' ? 'Press F when you’re ready to focus.' : `Focusing for ${nextLen} minutes.`);
  }
}

let lastPaint = '';
export function paint(now = Date.now()) {
  const on = running();
  const left = on ? clock(F.remaining(timer, now)) : '';
  const label = on ? F.PHASES[timer.phase] : '';
  const key = `${left}|${timer.phase}|${timer.pausedAt}|${state.tab}`;
  if (key === lastPaint) return;
  lastPaint = key;

  const pill = document.getElementById('focus-pill');
  if (pill) {
    pill.hidden = !on || state.tab === 'focus' || !state.data || document.body.classList.contains('signed-out');
    if (!pill.hidden) {
      pill.className = `focus-pill ${timer.phase}${timer.pausedAt ? ' paused' : ''}`;
      pill.innerHTML = `<span class="dot" aria-hidden="true"></span><span class="t">${left}</span>`;
      pill.title = `${label}${timer.pausedAt ? ' (paused)' : ''}: ${left} left. Open Focus (G then F); F pauses.`;
      pill.setAttribute('aria-label', `${label} timer, ${left} left${timer.pausedAt ? ', paused' : ''}`);
    }
  }
  const count = document.querySelector('[data-count="focus"]');
  if (count) {
    count.textContent = left;
    count.className = `count${on ? ` live ${timer.phase}` : ''}${timer.pausedAt ? ' paused' : ''}`;
  }
  const base = document.getElementById('topbar-title')?.textContent || 'Attendance80';
  if (state.data) document.title = on ? `${timer.pausedAt ? '❚❚' : '▶'} ${left} ${label} · Attendance80` : `${base} · Attendance80`;
  const c = document.getElementById('fx-clock');
  if (c && on) c.textContent = left;
  const bar = document.getElementById('fx-bar');
  if (bar) bar.style.transform = `scaleX(${F.progress(timer, now)})`;
  for (const el of document.querySelectorAll('[data-fx-left]')) el.textContent = left;
}

// Boot: picks up a running timer, sends sessions left from last time.
export function init() {
  navigator.serviceWorker?.register('/sw.js').catch(() => {});
  syncTicker();
  if (F.isDue(timer, Date.now())) phaseEnded(Date.now());
  paint();
  flush();
}

window.addEventListener('storage', (e) => {
  if (!state.data) return;
  if (e.key === TIMER_KEY) { timer = loadTimer(); changed(); }
  if (e.key === LOG_KEY && e.newValue) {
    try { upsertSession(JSON.parse(e.newValue)); } catch { /* ignore */ }
    if (state.tab === 'focus' && safeToRender()) render();
  }
});
document.addEventListener('visibilitychange', () => {
  if (!state.data) return;
  syncTicker();
  if (!document.hidden) { lastPaint = ''; tick(); }
});
window.addEventListener('online', () => flush());
// The browser only allows sound after a gesture: after a reload, the first click or key unlocks it.
for (const ev of ['pointerdown', 'keydown']) document.addEventListener(ev, () => { if (running()) unlockAudio(); }, { capture: true, passive: true });

// ---------------------------------------------------------------- views

const trackedIds = () => new Set(state.data.courses.filter((c) => c.tracked).map((c) => c.id));

// The next class you'd go to, within 3 hours.
function nextClass(now) {
  const tracked = trackedIds();
  const e = state.data.events.find((x) => x.start_at > now && tracked.has(x.course_id) && x.status !== 'absent');
  return e && e.start_at - now < 3 * 3600000 ? e : null;
}

function targetOptions() {
  const t = timer.target;
  const open = state.data.homework.filter((h) => !h.done).slice(0, 40);
  if (t?.homework_id && !open.some((h) => h.id === t.homework_id)) {
    const h = state.data.homework.find((x) => x.id === t.homework_id);
    if (h) open.unshift(h);
  }
  const byId = courseById();
  const courses = state.data.courses.filter((c) => c.tracked).sort((a, b) => a.name.localeCompare(b.name));
  const sel = t?.homework_id ? `hw:${t.homework_id}` : t?.course_id ? `course:${t.course_id}` : '';
  const opt = (v, label) => `<option value="${v}" ${v === sel ? 'selected' : ''}>${esc(label)}</option>`;
  return `${opt('', 'Nothing in particular')}
    ${open.length ? `<optgroup label="Homework and catch-ups">${open.map((h) => opt(`hw:${h.id}`, `${h.title}${byId[h.course_id] ? ` · ${byId[h.course_id].name}` : ''}`)).join('')}</optgroup>` : ''}
    ${courses.length ? `<optgroup label="Courses">${courses.map((c) => opt(`course:${c.id}`, c.name)).join('')}</optgroup>` : ''}`;
}

function timerCard(now) {
  const s = settings();
  const on = running();
  const phase = on ? timer.phase : timer.next;
  const paused = Boolean(timer.pausedAt);
  const total = on ? F.remaining(timer, now) : s[phase] * MIN;
  const title = on ? `${F.PHASES[phase]}${paused ? ' · paused' : ''}`
    : phase === 'focus' ? 'Ready to focus' : `Time for a ${phase === 'long' ? 'long' : 'short'} break`;
  const doneInSet = Math.min(timer.done, s.longEvery);
  const main = !on ? (phase === 'focus' ? 'Start focus' : 'Start break') : paused ? 'Resume' : 'Pause';
  const icon = on && !paused ? 'i-pause' : 'i-play';

  const cls = nextClass(now);
  const ends = on ? timer.endsAt : now + total;
  let note = '';
  if (cls) {
    const name = esc(courseById()[cls.course_id]?.name || cls.summary);
    const before = Math.round((cls.start_at - now) / MIN);
    note = ends > cls.start_at && phase === 'focus'
      ? `<p class="fx-note warn">${name} starts at ${fmtTime(cls.start_at)}, before this focus ends.</p>`
      : `<p class="fx-note">Next class: ${name} at ${fmtTime(cls.start_at)}, in ${dur(before)}.</p>`;
  }

  const last = state.fxLast;
  const lastHw = last && now - last.at < 30 * MIN && state.data.homework.find((h) => h.id === last.homework_id && !h.done);
  return `
    <section class="fx-timer ${phase}${on ? ' on' : ''}${paused ? ' paused' : ''}" id="fx-timer" aria-label="Focus timer">
      <div class="fx-phase"><span class="dot" aria-hidden="true"></span>${title}</div>
      <div class="fx-clock" id="fx-clock" role="timer" aria-live="off">${clock(total)}</div>
      <div class="fx-track" aria-hidden="true"><div class="fx-bar" id="fx-bar" style="transform:scaleX(${on ? F.progress(timer, now) : 0})"></div></div>
      <div class="fx-cycle" title="${doneInSet} of ${s.longEvery} focus sessions before a long break" aria-label="${doneInSet} of ${s.longEvery} before a long break">
        ${Array.from({ length: s.longEvery }, (_, i) => `<i class="${i < doneInSet ? 'on' : ''}"></i>`).join('')}
      </div>
      <label class="fx-target"><span>Working on</span>
        <div class="select"><select id="fx-target" aria-label="What this focus is for">${targetOptions()}</select></div>
      </label>
      <div class="fx-actions">
        <button class="btn btn-primary btn-large" data-fx="toggle"><svg class="ic sm" aria-hidden="true"><use href="#${icon}"/></svg>${main}<kbd>F</kbd></button>
        ${on || phase !== 'focus' ? `<button class="btn btn-large" data-fx="skip" title="${phase === 'focus' ? 'End this focus now and take the break' : 'Skip the break'} (S)">${phase === 'focus' ? 'Take a break' : 'Skip break'}<kbd>S</kbd></button>` : ''}
        ${on ? '<button class="btn btn-borderless btn-large" data-fx="stop" title="Stop the timer (X). Focus so far is recorded.">Stop<kbd>X</kbd></button>' : ''}
      </div>
      ${note}
      ${lastHw ? `<p class="fx-done">Finished “${esc(lastHw.title)}”? <button class="btn btn-small" data-fx-done="${lastHw.id}">Mark done</button></p>` : ''}
    </section>`;
}

const tile = (n, l, extra = '') => `<div class="stat"><span class="n">${n}</span><span class="l">${l}</span>${extra}</div>`;

function weekChart(st) {
  const s = settings();
  const goalMin = s.goal * s.focus;
  const top = Math.max(goalMin, ...st.week.map((d) => d.minutes), 1);
  const today = F.dayStart(Date.now());
  return `
    <div class="fx-week" role="img" aria-label="Focus per day this week: ${st.week.map((d) => `${new Date(d.ms).toLocaleDateString([], { weekday: 'short' })} ${dur(d.minutes)}`).join(', ')}">
      <div class="fx-goal" style="--g:${goalMin / top}"><span>goal ${hrs(goalMin)}</span></div>
      ${st.week.map((d) => {
        const h = (d.minutes / top) * 100;
        const name = new Date(d.ms).toLocaleDateString([], { weekday: 'short' });
        return `<div class="fx-col${d.ms === today ? ' today' : ''}${d.future ? ' future' : ''}"
          data-tip="${esc(`${new Date(d.ms).toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'short' })}: ${d.minutes ? `${dur(d.minutes)} · ${plural(d.count, 'pomodoro')}` : 'no focus'}`)}">
          <div class="fx-plot">${d.minutes ? `<div class="fx-bar-v" style="height:${Math.max(h, 1.5)}%"></div>` : ''}</div>
          <span class="fx-day">${esc(name)}</span>
        </div>`;
      }).join('')}
    </div>`;
}

// A year of days, GitHub-style: one column per week, darker for more focus (relative to the daily goal).
function heatmap(st) {
  const f = state.data.focus;
  const s = settings();
  const goalMin = s.goal * s.focus;
  const first = F.addDays(F.weekStart(Date.now()), -(HEAT_WEEKS - 1) * 7);
  const today = F.dayStart(Date.now());
  if (f.since > first) return `<p class="empty">${f.failed ? 'Couldn’t load the year.' : 'Loading the year…'}</p>`;
  const level = (m) => (!m ? 0 : m < goalMin / 4 ? 1 : m < goalMin / 2 ? 2 : m < goalMin ? 3 : 4);
  const cells = [], months = [];
  let lastMonth = -1;
  for (let w = 0; w < HEAT_WEEKS; w++) {
    const monday = F.addDays(first, w * 7);
    const month = new Date(F.addDays(monday, 6)).getMonth();
    if (month !== lastMonth) { months.push(`<span style="grid-column:${w + 1}">${esc(new Date(F.addDays(monday, 6)).toLocaleDateString([], { month: 'short' }))}</span>`); lastMonth = month; }
    for (let d = 0; d < 7; d++) {
      const ms = F.addDays(monday, d);
      if (ms > today) { cells.push('<i class="future"></i>'); continue; }
      const v = st.day(ms);
      cells.push(`<i class="l${level(v.minutes)}" data-tip="${esc(`${new Date(ms).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}: ${v.minutes ? `${dur(v.minutes)} · ${plural(v.count, 'pomodoro')}` : 'no focus'}`)}"></i>`);
    }
  }
  return `
    <div class="fx-heat-scroll">
      <div class="fx-heat" role="img" aria-label="Focus per day over the last year">
        <div class="fx-months">${months.join('')}</div>
        <div class="fx-dows"><span></span><span>Mon</span><span></span><span>Wed</span><span></span><span>Fri</span><span></span></div>
        <div class="fx-cells">${cells.join('')}</div>
      </div>
    </div>
    <div class="fx-legend"><span>Less</span>${[0, 1, 2, 3, 4].map((l) => `<i class="l${l}"></i>`).join('')}<span>More</span>
      <span class="muted">· darkest is your daily goal (${hrs(goalMin)})</span></div>`;
}

function breakdown(st) {
  const byId = courseById();
  const r = st.range;
  const top = Math.max(1, ...r.courses.map((c) => c.minutes));
  const open = new Set(state.data.homework.filter((h) => !h.done).map((h) => h.id));
  const bars = (rows) => rows.map((row) => `
    <div class="fx-row">
      <span class="fx-name">${row.name}</span>
      <span class="fx-meter" aria-hidden="true"><i style="transform:scaleX(${row.minutes / row.top})"></i></span>
      <span class="fx-val">${dur(row.minutes)}</span>
      ${row.action || ''}
    </div>`).join('');
  return `
    <p class="fx-summary">${r.minutes ? `${hrs(r.minutes)} over ${plural(r.activeDays, 'day')}, about ${dur(r.perActiveDay)} on a day you focus${r.peakHour != null ? `, mostly around ${fmtTime(new Date().setHours(r.peakHour, 0, 0, 0))}` : ''}.` : 'No focus in this period yet.'}</p>
    ${r.courses.length ? `<h4 class="fx-sub">By course</h4>
      <div class="fx-rows">${bars(r.courses.slice(0, 8).map((c) => ({
        name: c.course_id != null && byId[c.course_id] ? esc(byId[c.course_id].name) : '<span class="muted">No course</span>', minutes: c.minutes, top,
      })))}</div>` : ''}
    ${r.homework.length ? `<h4 class="fx-sub">By homework</h4>
      <div class="fx-rows">${bars(r.homework.slice(0, 6).map((h) => ({
        name: `${esc(h.label || 'Untitled')}${h.count ? ` <span class="muted small">${plural(h.count, 'pomodoro')}</span>` : ''}`,
        minutes: h.minutes, top: r.homework[0].minutes,
        action: h.homework_id && open.has(h.homework_id) ? `<button class="btn btn-borderless btn-small fx-again" data-hw-focus-id="${h.homework_id}" title="Focus on it again" aria-label="Focus on it again"><svg class="ic sm" aria-hidden="true"><use href="#i-play"/></svg></button>` : '<span></span>',
      })))}</div>` : ''}`;
}

function recent() {
  const list = sessions().slice(-8).reverse();
  if (!list.length) return '<p class="empty">Your focus sessions will show here. Press <kbd>F</kbd> to start one.</p>';
  const byId = courseById();
  return `<div class="list">${list.map((x) => `
    <div class="fx-log${x.completed ? '' : ' partial'}">
      <span class="when">${esc(dayLabel(x.started_at))}<span>${fmtTime(x.started_at)}–${fmtTime(x.ended_at)}</span></span>
      <span class="what">${esc(x.label || (x.course_id != null && byId[x.course_id]?.name) || 'Focus')}
        ${x.label && byId[x.course_id] ? `<span class="muted small"> · ${esc(byId[x.course_id].name)}</span>` : ''}</span>
      <span class="fx-val">${dur(x.focus_seconds / 60)}${x.completed ? '' : '<span class="chip">stopped early</span>'}${x.id ? '' : '<span class="chip">saving…</span>'}</span>
      ${x.id ? `<button class="btn btn-borderless btn-small" data-fx-delete="${x.id}" title="Delete this session" aria-label="Delete this session"><svg class="ic sm" aria-hidden="true"><use href="#i-trash"/></svg></button>` : '<span></span>'}
    </div>`).join('')}</div>`;
}

function settingsForm() {
  const s = settings();
  const num = (name, label, [lo, hi], suffix) => `<label>${label}<span class="input-suffix"><input class="field" type="number" name="${name}" min="${lo}" max="${hi}" step="1" value="${s[name]}" required> ${suffix}</span></label>`;
  const chk = (name, label) => `<label class="checkbox check"><input type="checkbox" name="${name}" ${s[name] ? 'checked' : ''}><span></span>${label}</label>`;
  const perm = canNotify() ? Notification.permission : 'unsupported';
  return `
    <details class="panel fx-settings" id="fx-settings" ${state.fxSettingsOpen ? 'open' : ''}>
      <summary><h3>Timer settings</h3></summary>
      <form id="fx-settings-form" class="form">
        <div class="form-row">
          ${num('focus', 'Focus', [1, 180], 'min')}${num('short', 'Short break', [1, 60], 'min')}${num('long', 'Long break', [1, 90], 'min')}
        </div>
        <div class="form-row">
          ${num('longEvery', 'Long break after', [2, 12], 'focus sessions')}${num('goal', 'Daily goal', [1, 24], 'pomodoros')}
        </div>
        <div class="fx-checks">
          ${chk('autoBreaks', 'Start breaks automatically')}
          ${chk('autoFocus', 'Start the next focus after a break automatically')}
          ${chk('sound', 'Play a sound when a phase ends')}
          ${chk('notify', 'Notify me when the page is in the background')}
        </div>
        ${perm === 'denied' ? '<p class="hint">Notifications are blocked for this site in your browser settings.</p>'
          : perm === 'default' && s.notify ? '<p class="hint"><button type="button" class="link" data-fx-notify>Allow notifications</button> so a phase ending reaches you in another tab.</p>' : ''}
        <button class="btn btn-primary" type="submit">Save</button>
      </form>
      <p class="hint">Changes apply from the next phase. Settings follow your account on every device.</p>
    </details>`;
}

export function subtitle() {
  const st = F.focusStats(sessions(), { now: Date.now() });
  return `${st.today.count} of ${settings().goal} pomodoros today`;
}

export function renderFocus() {
  ensureHistory();
  const now = Date.now();
  const s = settings();
  const rangeKey = state.fxRange in RANGES ? state.fxRange : 'week';
  const st = F.focusStats(sessions(), { now, range: RANGES[rangeKey][0] });
  const delta = st.thisWeek - st.lastWeek;
  $app.innerHTML = `
    ${timerCard(now)}
    <section class="panel">
      <h3>Today</h3>
      <div class="ov-stats fx-stats">
        ${tile(st.today.minutes ? dur(st.today.minutes) : '0 min', 'focused today')}
        ${tile(`${st.today.count}<small>/${s.goal}</small>`, 'pomodoros', `<span class="fx-goalbar" aria-hidden="true"><i style="transform:scaleX(${Math.min(1, st.today.count / s.goal)})"></i></span>`)}
        ${tile(`${st.streak}<small> ${st.streak === 1 ? 'day' : 'days'}</small>`, 'streak')}
        ${tile(hrs(st.thisWeek), 'this week', st.lastWeek || st.thisWeek ? `<span class="fx-delta ${delta >= 0 ? 'up' : 'down'}">${delta >= 0 ? '+' : '−'}${hrs(Math.abs(delta))} vs last week</span>` : '')}
      </div>
    </section>
    <section class="panel">
      <h3>This week</h3>
      ${weekChart(st)}
    </section>
    <section class="panel">
      <h3><span>Where it went</span>
        <span class="scope" role="group" aria-label="Period">${Object.entries(RANGES).map(([k, [, l]]) => `<button class="scope-chip" data-fx-range="${k}" aria-pressed="${k === rangeKey}">${l}</button>`).join('')}</span>
      </h3>
      ${breakdown(st)}
    </section>
    <section class="panel">
      <h3><span>Last 12 months</span>${st.best ? `<span class="muted small fx-best">best day ${dur(st.best.minutes)}</span>` : ''}</h3>
      ${heatmap(st)}
    </section>
    <section class="panel">
      <h3>Recent sessions</h3>
      ${recent()}
    </section>
    ${settingsForm()}`;
  const scroll = $app.querySelector('.fx-heat-scroll');
  if (scroll) scroll.scrollLeft = scroll.scrollWidth;
}

// The Focus block on Today.
export function todayPanel() {
  const st = F.focusStats(sessions(), { now: Date.now() });
  const s = settings();
  const on = running();
  const t = timer.target;
  const line = on
    ? `${F.PHASES[timer.phase]}${t?.label && timer.phase === 'focus' ? ` on <b>${esc(t.label)}</b>` : ''}: <b data-fx-left>${clock(F.remaining(timer, Date.now()))}</b> left${timer.pausedAt ? ' (paused)' : ''}.`
    : `${st.today.minutes ? `${dur(st.today.minutes)} focused today` : 'No focus yet today'} · ${st.today.count} of ${s.goal} pomodoros${st.streak > 1 ? ` · ${st.streak}-day streak` : ''}.`;
  const btn = !on ? (timer.next === 'focus' ? 'Start focus' : 'Start break') : timer.pausedAt ? 'Resume' : 'Pause';
  return `
    <section class="panel">
      <h3><span>Focus</span><a href="#focus" data-goto="focus">timer and stats →</a></h3>
      <div class="fx-today">
        <p>${line}</p>
        <button class="btn btn-small" data-fx="toggle"><svg class="ic sm" aria-hidden="true"><use href="#${on && !timer.pausedAt ? 'i-pause' : 'i-play'}"/></svg>${btn}<kbd>F</kbd></button>
      </div>
    </section>`;
}

// ---------------------------------------------------------------- palette and shortcuts

export function paletteItems() {
  const items = [];
  const kw = 'pomodoro focus timer';
  const add = (id, label, icon, run, extra = {}) => items.push({ id: `fx:${id}`, section: 'Focus', label, icon, run, keywords: kw, ...extra });
  const on = running();
  if (!on) add('start', timer.next === 'focus' ? 'Start focus' : `Start ${timer.next === 'long' ? 'long' : 'short'} break`, 'i-play', toggle, { keys: ['F'], home: true, tone: 'accent' });
  else add('toggle', timer.pausedAt ? 'Resume timer' : 'Pause timer', timer.pausedAt ? 'i-play' : 'i-pause', toggle, { keys: ['F'], home: true });
  if (on || timer.next !== 'focus') add('skip', timer.phase === 'focus' ? 'Take a break now' : 'Skip break', 'i-skip', skip);
  if (on) add('stop', 'Stop timer', 'i-stop', stop);
  for (const h of state.data.homework.filter((x) => !x.done).slice(0, 12)) {
    add(`hw:${h.id}`, `Focus on: ${h.title}`, 'i-focus', () => focusOn(h.id), { recentId: '', sub: courseById()[h.course_id]?.name });
  }
  return items;
}

export const SHORTCUTS = [[['F'], 'Start, pause or resume the focus timer'], [['S'], 'Focus page: take a break, or skip it'], [['X'], 'Focus page: stop the timer'],
  [['Space'], 'Focus page: start or pause']];

// Keys on the Focus page (single keys, never while typing). Returns true when handled.
export function onPageKey(e) {
  const k = e.key.toLowerCase();
  if (k === ' ' && !e.target.closest('button, a, summary, label')) { e.preventDefault(); if (!e.repeat) toggle(); return true; }
  if (k === 's') { e.preventDefault(); skip(); return true; }
  if (k === 'x') { e.preventDefault(); stop(); return true; }
  return false;
}

// ---------------------------------------------------------------- events

export function onClick(e) {
  const t = e.target;
  const fx = t.closest('[data-fx]');
  if (fx) { ({ toggle, skip, stop })[fx.dataset.fx]?.(); return true; }
  const hwFocus = t.closest('[data-hw-focus]');
  if (hwFocus) { focusOn(Number(hwFocus.closest('[data-hw]').dataset.hw)); return true; }
  const again = t.closest('[data-hw-focus-id]');
  if (again) { focusOn(Number(again.dataset.hwFocusId)); return true; }
  const range = t.closest('[data-fx-range]');
  if (range) { state.fxRange = range.dataset.fxRange; render(); return true; }
  if (t.closest('[data-fx-notify]')) {
    Notification.requestPermission().then(() => render(), () => {});
    return true;
  }
  const del = t.closest('[data-fx-delete]');
  if (del) {
    const id = Number(del.dataset.fxDelete);
    if (!confirm('Delete this focus session from your stats?')) return true;
    api(`/api/focus/${id}`, { method: 'DELETE' }).then(() => {
      state.data.focus.sessions = sessions().filter((x) => x.id !== id);
      render();
    }).catch((err) => toast(err.message, true));
    return true;
  }
  const done = t.closest('[data-fx-done]');
  if (done) {
    const id = Number(done.dataset.fxDone);
    api(`/api/homework/${id}`, { method: 'PATCH', body: { done: true } }).then((row) => {
      const i = state.data.homework.findIndex((h) => h.id === id);
      if (i >= 0) state.data.homework[i] = row;
      state.fxLast = null;
      if (timer.target?.homework_id === id) save({ ...timer, target: null });
      render();
      toast(`Marked “${row.title}” done.`);
    }).catch((err) => toast(err.message, true));
    return true;
  }
  if (t.closest('#fx-settings > summary')) { state.fxSettingsOpen = !document.getElementById('fx-settings').open; return false; }
  return false;
}

export function onChange(e) {
  if (e.target.id !== 'fx-target') return false;
  setTarget(e.target.value);
  return true;
}

export function onSubmit(e) {
  if (e.target.id !== 'fx-settings-form') return false;
  e.preventDefault();
  const fd = new FormData(e.target);
  const body = Object.fromEntries(['focus', 'short', 'long', 'longEvery', 'goal'].map((k) => [k, Number(fd.get(k))]));
  for (const k of ['autoBreaks', 'autoFocus', 'sound', 'notify']) body[k] = fd.get(k) === 'on';
  const btn = e.target.querySelector('[type=submit]');
  btn.disabled = true;
  api('/api/focus/settings', { method: 'PUT', body }).then(({ settings: saved }) => {
    state.data.focus.settings = saved;
    if (saved.notify) askNotify();
    document.activeElement?.blur();
    render();
    toast('Timer settings saved.');
  }).catch((err) => { btn.disabled = false; toast(err.message, true); });
  return true;
}

// Hover tooltips for the charts: one floating label, placed above the hovered mark.
const $tip = document.createElement('div');
$tip.className = 'fx-tip';
$tip.hidden = true;
$tip.setAttribute('role', 'tooltip');
document.body.append($tip);
document.addEventListener('pointerover', (e) => {
  const el = e.target.closest?.('#app [data-tip]');
  if (!el) { $tip.hidden = true; return; }
  $tip.textContent = el.dataset.tip;
  $tip.hidden = false;
  const r = el.getBoundingClientRect();
  const w = $tip.offsetWidth;
  $tip.style.left = `${Math.min(Math.max(8, r.left + r.width / 2 - w / 2), innerWidth - w - 8)}px`;
  $tip.style.top = `${Math.max(8, r.top - $tip.offsetHeight - 6)}px`;
});
document.addEventListener('scroll', () => { $tip.hidden = true; }, { capture: true, passive: true });
