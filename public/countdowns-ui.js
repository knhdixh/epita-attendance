// Countdowns: days until an exam, a trip or a birthday, or days since something began, each shown the way you
// choose (days, weeks, months, working days, hours or a live clock). One sentence adds one ("Algo exam 12 dec 9:00");
// a row opens in place to fine-tune it. Exams in the timetable are offered with one tap. Pinned ones show on
// Today and in the sidebar. The page ticks without re-rendering: live clocks every second, the rest every 30 s.
import { state, esc, api, toast, render, courseById, fmtTime } from './app.js';
import * as C from './countdowns.js';
import { effectiveTag } from './stats.js';
import { localKey } from './habits.js';

const $app = document.getElementById('app');
const DISMISS_KEY = 'a80-cd-dismissed';
const MIN = 60000, DAY = 86400000;

const list = () => state.data.countdowns;
const byId = (id) => list().find((c) => c.id === id);
const color = (c) => `--hc:var(--c-${C.COLORS.includes(c.color) ? c.color : 'blue'})`;

// ---------------------------------------------------------------- formatting

function when(c, at) {
  const d = new Date(at);
  const year = d.getFullYear() !== new Date().getFullYear() ? { year: 'numeric' } : {};
  return `${d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short', ...year })}${c.all_day ? '' : `, ${fmtTime(at)}`}`;
}
// "<b>6</b> w <b>3</b> d"
const figure = (v) => (v.state === 'today' ? '<b>Today</b>' : esc(v.parts).replace(/(\d+(?::\d+)*)/g, '<b>$1</b>'));
const direction = (v) => ({ future: 'to go', today: '', up: 'since', past: 'ago' })[v.state];

// "45d", "5h", "25m", "today": for the sidebar and chips.
export function short(c, now = Date.now()) {
  const v = C.view({ ...c, units: 'auto' }, now);
  if (v.state === 'today') return 'today';
  const ms = Math.abs(v.at - now);
  const s = ms >= 2 * DAY || c.all_day ? `${Math.abs(Math.round((C.dayStart(v.at) - C.dayStart(now)) / DAY))}d` : ms >= 3600000 ? `${Math.floor(ms / 3600000)}h` : `${Math.max(1, Math.floor(ms / MIN))}m`;
  return v.state === 'future' ? s : `${s} ago`;
}

// The countdown to show in the sidebar: the soonest pinned one coming up, else the soonest coming up.
export function nearest(now = Date.now()) {
  const upcoming = C.sortCountdowns(list(), now).filter((x) => x.v.state === 'future' || x.v.state === 'today');
  return (upcoming.find((x) => x.c.pinned) || upcoming[0])?.c || null;
}

// ---------------------------------------------------------------- saving

async function create(body, { quiet = false } = {}) {
  try {
    const row = await api('/api/countdowns', { method: 'POST', body });
    list().push(row);
    state.cdAdding = false;
    state.cdEditing = null;
    state.cdDraft = '';
    render();
    flash(row.id);
    if (!quiet) toast(`Added “${row.title}”: ${C.view(row).parts} ${direction(C.view(row))}.`);
    return row;
  } catch (err) { toast(err.message, true); return null; }
}

async function update(id, body, msg) {
  const i = list().findIndex((c) => c.id === id);
  const before = list()[i];
  list()[i] = { ...before, ...body };
  state.cdEditing = null;
  render();
  try {
    list()[list().findIndex((c) => c.id === id)] = await api(`/api/countdowns/${id}`, { method: 'PATCH', body });
    if (msg) toast(msg);
    render();
  } catch (err) {
    list()[list().findIndex((c) => c.id === id)] = before;
    render();
    toast(err.message, true);
  }
}

async function remove(id) {
  const c = byId(id);
  if (!c || !confirm(`Delete “${c.title}”?`)) return;
  try {
    await api(`/api/countdowns/${id}`, { method: 'DELETE' });
    state.data.countdowns = list().filter((x) => x.id !== id);
    state.cdEditing = null;
    render();
    toast(`Deleted “${c.title}”.`);
  } catch (err) { toast(err.message, true); }
}

const flash = (id) => { const el = $app.querySelector(`[data-cd="${id}"]`); el?.scrollIntoView({ block: 'nearest' }); el?.classList.add('flash'); };

// ---------------------------------------------------------------- suggestions from the timetable

const dismissed = () => { try { return new Set(JSON.parse(localStorage.getItem(DISMISS_KEY) || '[]')); } catch { return new Set(); } };

function suggestions(now = Date.now()) {
  const linked = new Set(list().map((c) => c.event_id).filter(Boolean));
  const skip = dismissed();
  const courses = courseById();
  const seen = new Set();
  return state.data.events.filter((e) => e.start_at > now && e.start_at < now + 180 * DAY && !linked.has(e.id) && !skip.has(e.id)
      && courses[e.course_id]?.tracked && effectiveTag(e, courses[e.course_id]) === 'exam')
    .filter((e) => { const k = `${e.course_id}|${new Date(e.start_at).toDateString()}`; if (seen.has(k)) return false; seen.add(k); return true; })
    .slice(0, 4)
    .map((e) => ({ e, title: `${courses[e.course_id]?.name || e.summary} exam` }));
}

const fromExam = (e, title) => create({ title, at: e.start_at, all_day: false, event_id: e.id, color: 'red', pinned: true, start_at: Date.now() }, { quiet: false });

// ---------------------------------------------------------------- rows

function row(c, v, now) {
  const ev = c.event_id && state.data.events.find((e) => e.id === c.event_id);
  const metaBits = [when(c, v.at)];
  if (c.repeat !== 'none') metaBits.push(C.REPEATS[c.repeat].toLowerCase());
  if (c.units !== 'auto') metaBits.push(C.UNITS[c.units].toLowerCase());
  if (ev?.location) metaBits.push(esc(ev.location));
  return `
    <div class="cd-row cd-${v.state}${c.pinned ? ' pinned' : ''}" data-cd="${c.id}" style="${color(c)}">
      <span class="cd-dot" aria-hidden="true"></span>
      <div class="cd-main" data-cd-edit tabindex="0" role="button" aria-label="${esc(c.title)}: edit">
        <div class="cd-title">${esc(c.title)}${c.pinned ? '<svg class="ic cd-pin-ic" aria-label="pinned"><use href="#i-pin"/></svg>' : ''}</div>
        <div class="cd-meta">${metaBits.map((b) => `<span>${b}</span>`).join('')}${c.note ? `<span class="cd-note">${esc(c.note)}</span>` : ''}</div>
        ${v.progress != null && v.state !== 'up' && v.state !== 'past' ? `<div class="cd-track" aria-hidden="true"><i data-cd-bar="${c.id}" style="transform:scaleX(${v.progress})"></i></div>` : ''}
      </div>
      <div class="cd-left" aria-live="off">
        <span class="cd-fig" data-cd-fig="${c.id}">${figure(v)}</span>
        <span class="cd-dir">${direction(v)}</span>
      </div>
      <div class="cd-actions">
        <button class="btn btn-borderless btn-small" data-cd-pin title="${c.pinned ? 'Unpin' : 'Pin to Today and the sidebar'}" aria-label="${c.pinned ? 'Unpin' : 'Pin'}" aria-pressed="${Boolean(c.pinned)}"><svg class="ic" aria-hidden="true"><use href="#i-pin"/></svg></button>
        <button class="btn btn-borderless btn-small" data-cd-delete title="Delete" aria-label="Delete"><svg class="ic" aria-hidden="true"><use href="#i-trash"/></svg></button>
      </div>
    </div>`;
}

// ---------------------------------------------------------------- the editor

const pad = (n) => String(n).padStart(2, '0');
const timeOf = (ms) => { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
function fromInputs(date, time) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date || '')) return null;
  const [y, m, d] = date.split('-').map(Number);
  const [h, mi] = time ? time.split(':').map(Number) : [0, 0];
  return new Date(y, m - 1, d, h, mi).getTime();
}

function editor(c) {
  const v = c.id ? c : { title: '', at: C.dayStart(Date.now() + 7 * DAY), all_day: 1, repeat: 'none', units: 'auto', after: 'up', start_at: null, color: 'blue', pinned: 0, note: '', ...c };
  const seg = (name, opts, cur) => `<div class="seg" role="radiogroup">${Object.entries(opts).map(([k, l]) => `<label class="segment"><input type="radio" name="${name}" value="${k}" ${cur === k ? 'checked' : ''}><span>${l}</span></label>`).join('')}</div>`;
  return `
    <form class="cd-edit" id="cd-form" data-id="${c.id || 'new'}" autocomplete="off" style="${color(v)}">
      <input class="field cd-edit-title" name="title" value="${esc(v.title)}" maxlength="80" required placeholder="What are you counting to? e.g. Algorithms exam" aria-label="Title">
      <div class="cd-grid">
        <label class="cd-f"><span>Date</span><input class="field" type="date" name="date" value="${localKey(v.at)}" required></label>
        <label class="cd-f"><span>Time <small>(empty = all day)</small></span><input class="field" type="time" name="time" value="${v.all_day ? '' : timeOf(v.at)}"></label>
        <label class="cd-f"><span>Repeats</span><div class="select"><select name="repeat">${Object.entries(C.REPEATS).map(([k, l]) => `<option value="${k}" ${v.repeat === k ? 'selected' : ''}>${l}</option>`).join('')}</select></div></label>
      </div>
      <div class="cd-f"><span>Show as</span>${seg('units', C.UNITS, v.units)}</div>
      <div class="cd-f"><span>Once it’s reached</span>${seg('after', C.AFTER, v.after)}</div>
      <div class="cd-grid">
        <label class="cd-f cd-progress"><span><label class="checkbox"><input type="checkbox" name="progress" ${v.start_at != null ? 'checked' : ''}><span></span>Progress bar from</label></span>
          <input class="field" type="date" name="start" value="${localKey(v.start_at ?? v.created_at ?? Date.now())}"></label>
        <div class="cd-f"><span>Colour</span>
          <div class="swatches">${C.COLORS.map((k) => `<label class="swatch" style="--hc:var(--c-${k})" title="${k}"><input type="radio" name="color" value="${k}" ${v.color === k ? 'checked' : ''} aria-label="${k}"><span></span></label>`).join('')}</div>
        </div>
      </div>
      <label class="checkbox cd-pinbox"><input type="checkbox" name="pinned" ${v.pinned ? 'checked' : ''}><span></span>Pin to Today and the sidebar</label>
      <textarea class="field" name="note" rows="2" maxlength="500" placeholder="Note (optional)">${esc(v.note || '')}</textarea>
      <div class="cd-edit-foot">
        <span class="cd-preview" id="cd-preview"></span>
        <span class="spacer"></span>
        ${c.id ? '<button class="btn btn-borderless btn-small bad" type="button" data-cd-delete>Delete</button>' : ''}
        <button class="btn btn-small" type="button" data-cd-cancel>Cancel</button>
        <button class="btn btn-primary btn-small" type="submit">${c.id ? 'Save' : 'Add countdown'}<kbd>⌘↵</kbd></button>
      </div>
    </form>`;
}

function readForm(f) {
  const fd = new FormData(f);
  const time = fd.get('time');
  const at = fromInputs(fd.get('date'), time);
  return {
    title: fd.get('title'), at, all_day: !time, repeat: fd.get('repeat'), units: fd.get('units'), after: fd.get('after'),
    start_at: fd.get('progress') ? fromInputs(fd.get('start'), '') : null, color: fd.get('color'), pinned: fd.get('pinned') === 'on', note: fd.get('note'),
  };
}

// What the editor's settings would show, as you change them.
function preview() {
  const f = document.getElementById('cd-form');
  const el = document.getElementById('cd-preview');
  if (!f || !el) return;
  const b = readForm(f);
  if (b.at == null) { el.textContent = ''; return; }
  const v = C.view({ ...b, all_day: b.all_day ? 1 : 0 });
  el.innerHTML = `<span class="cd-fig">${figure(v)}</span> ${direction(v)}`;
}

// ---------------------------------------------------------------- quick add

function quickAdd() {
  if (state.cdEditing === 'new') return editor(state.cdPreset || {});
  if (!state.cdAdding) return '<button class="add-row" data-cd-add><span class="plus" aria-hidden="true"><svg class="ic sm"><use href="#i-plus"/></svg></span>Add countdown<kbd>N</kbd></button>';
  return `
    <form class="quick-add" id="cd-quick" autocomplete="off">
      <input class="field" name="q" maxlength="160" placeholder="e.g. Algo exam 12 dec 9:00 · Mum’s birthday 3 march · No coffee since 1 sep" aria-label="New countdown" required>
      <div class="quick-foot">
        <span class="quick-chips" id="cd-quick-chips"><span class="muted">Type a name and a date</span></span>
        <span class="spacer"></span>
        <button class="btn btn-borderless btn-small" type="button" data-cd-more>More options</button>
        <button class="btn btn-small" type="button" data-cd-cancel>Cancel</button>
        <button class="btn btn-primary btn-small" type="submit">Add</button>
      </div>
    </form>`;
}

function quickChips(q) {
  const p = C.parseCountdown(q);
  if (!p) return `<span class="muted">${q.trim() ? 'Add a date: “12 dec”, “friday 9am”, “in 3 weeks”…' : 'Type a name and a date'}</span>`;
  const v = C.view(p);
  return [`<span class="chip on"><svg class="ic" aria-hidden="true"><use href="#i-calendar"/></svg>${esc(when(p, p.at))}</span>`,
    p.repeat !== 'none' ? `<span class="chip on">${esc(C.REPEATS[p.repeat].toLowerCase())}</span>` : '',
    `<span class="chip">${esc(v.parts)} ${direction(v)}</span>`].join('');
}

function refreshChips() {
  const q = document.querySelector('#cd-quick [name=q]');
  const el = document.getElementById('cd-quick-chips');
  if (q && el) el.innerHTML = quickChips(q.value);
}

function createFromText(q) {
  const p = C.parseCountdown(q);
  if (!p) { toast('Add a date, like “12 dec”, “friday 9am” or “in 3 weeks”.', true); return null; }
  if (!p.title) { toast('Give it a name too.', true); return null; }
  return create({ title: p.title, at: p.at, all_day: Boolean(p.all_day), repeat: p.repeat, after: p.after, color: C.COLORS[list().length % (C.COLORS.length - 1)] });
}

// ---------------------------------------------------------------- page

export function subtitle() {
  const c = nearest();
  return c ? `next: ${esc(c.title)}, ${short(c) === 'today' ? 'today' : `in ${short(c)}`}` : '';
}

export function renderCountdowns() {
  const now = Date.now();
  const sorted = C.sortCountdowns(list(), now);
  const by = (states) => sorted.filter((x) => states.includes(x.v.state));
  const coming = by(['today', 'future']), up = by(['up']), past = by(['past']);
  const item = ({ c, v }) => (state.cdEditing === c.id ? editor(c) : row(c, v, now));
  const sugg = suggestions(now);

  $app.innerHTML = `
    ${!list().length && !state.cdAdding && state.cdEditing !== 'new' ? `
      <div class="hb-empty">
        <p><b>Count down to what matters</b>, or count up from when something began.</p>
        <p class="muted small">Try “Algo exam 12 dec 9:00”, “Trip in 3 weeks”, “Mum’s birthday 3 march” or “No coffee since 1 sep”.</p>
      </div>` : ''}
    <section class="panel">
      ${list().length ? '<h3><span>Coming up</span></h3>' : ''}
      <div class="list">${coming.map(item).join('')}</div>
      ${list().length && !coming.length ? '<p class="empty">Nothing coming up.</p>' : ''}
      ${quickAdd()}
      ${sugg.length ? `
      <div class="cd-suggest">
        <span class="muted small">From your timetable</span>
        ${sugg.map(({ e, title }) => `<span class="cd-sugg"><button class="tag-chip" data-cd-exam="${e.id}" title="Count down to it">+ ${esc(title)} · ${esc(when({ all_day: 0 }, e.start_at))}</button><button class="cd-x" data-cd-dismiss="${e.id}" title="Don’t suggest this" aria-label="Don’t suggest ${esc(title)}">×</button></span>`).join('')}
      </div>` : ''}
    </section>
    ${up.length ? `<section class="panel"><h3><span>Counting up</span></h3><div class="list">${up.map(item).join('')}</div></section>` : ''}
    ${past.length ? `<details class="panel hb-archived" ${state.cdShowPast ? 'open' : ''}><summary><h3>Past <span class="muted small">${past.length}</span></h3></summary><div class="list">${past.map(item).join('')}</div></details>` : ''}`;

  if (state.cdAdding) { const q = document.querySelector('#cd-quick [name=q]'); if (q) { q.value = state.cdDraft || ''; q.focus(); refreshChips(); } }
  if (state.cdEditing) { document.querySelector('#cd-form [name=title]')?.focus(); preview(); }
  arm();
}

// Pinned countdowns, as a strip at the top of Today.
export function todayStrip() {
  const now = Date.now();
  const pinned = C.sortCountdowns(list().filter((c) => c.pinned), now).filter((x) => x.v.state !== 'past');
  if (!pinned.length) return '';
  setTimeout(arm);
  return `<div class="cd-strip">${pinned.map(({ c, v }) => `
    <button class="cd-chip" data-goto-cd="${c.id}" style="${color(c)}" title="${esc(`${c.title}: ${when(c, v.at)}`)}">
      <span class="cd-chip-fig" data-cd-fig="${c.id}">${figure(v)}</span>
      <span class="cd-chip-t">${esc(c.title)}<small>${direction(v)}</small></span>
    </button>`).join('')}</div>`;
}

// ---------------------------------------------------------------- ticking
// Paints the figures in place: live ones every second, the others every 30 seconds.

let ticker = null, ticks = 0;
function arm() {
  const any = document.querySelector('[data-cd-fig]');
  if (any && !ticker && !document.hidden) ticker = setInterval(tick, 1000);
  if ((!any || document.hidden) && ticker) { clearInterval(ticker); ticker = null; }
}
function tick() {
  const els = document.querySelectorAll('[data-cd-fig]');
  if (!els.length || document.hidden) return arm();
  const now = Date.now();
  const all = ++ticks % 30 === 0;
  for (const el of els) {
    const c = byId(Number(el.dataset.cdFig));
    if (!c) continue;
    const v = C.view(c, now);
    if (!v.live && !all) continue;
    const html = figure(v);
    if (el.innerHTML !== html) el.innerHTML = html;
    const bar = document.querySelector(`[data-cd-bar="${c.id}"]`);
    if (bar && v.progress != null) bar.style.transform = `scaleX(${v.progress})`;
  }
  if (document.getElementById('cd-preview') && ticks % 5 === 0) preview();
}
document.addEventListener('visibilitychange', () => { arm(); if (!document.hidden) tick(); });

// ---------------------------------------------------------------- palette and keys

export function paletteItems(go) {
  if (!state.data?.countdowns) return [];
  const now = Date.now();
  const items = [{ id: 'cd:new', section: 'Actions', label: 'New countdown', icon: 'i-plus', home: true, keywords: 'countdown count up timer days until since date exam birthday',
    run: () => { go('countdowns'); openAdd(); } }];
  for (const { c, v } of C.sortCountdowns(list(), now).slice(0, 30)) {
    items.push({ id: `cd:${c.id}`, section: 'Countdowns', label: c.title, icon: 'i-countdown', keywords: 'countdown',
      home: Boolean(c.pinned) && v.state !== 'past', sub: `${v.state === 'today' ? 'today' : `${v.parts} ${direction(v)}`} · ${when(c, v.at)}`,
      run: () => { state.cdEditing = c.id; go('countdowns'); } });
  }
  return items;
}

// "/" then a sentence with a date: add it as a countdown in one go.
export function fallback(q, go) {
  const p = C.parseCountdown(q);
  if (!p?.title) return [];
  const v = C.view(p);
  return [{ id: 'cd:add:q', recentId: '', section: 'Add', label: `Add countdown: ${p.title}`, icon: 'i-countdown',
    sub: `${when(p, p.at)} · ${v.parts} ${direction(v)}`, run: () => { go('countdowns'); createFromText(q); } }];
}

function openAdd(preset = null) {
  if (preset) { state.cdPreset = preset; state.cdEditing = 'new'; state.cdAdding = false; }
  else { state.cdAdding = true; state.cdEditing = null; }
  render();
}

export function onPageKey(e) {
  if (e.key.toLowerCase() === 'n') { e.preventDefault(); openAdd(); return true; }
  return false;
}

// Esc closes the add row or the editor; Cmd/Ctrl+Enter saves the editor.
export function onKeydown(e) {
  const inForm = e.target.closest?.('#cd-quick, #cd-form');
  if (!inForm) return false;
  if (e.key === 'Escape') { e.preventDefault(); state.cdAdding = false; state.cdEditing = null; state.cdDraft = ''; render(); return true; }
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && inForm.id === 'cd-form') { e.preventDefault(); inForm.requestSubmit(); return true; }
  return false;
}

// ---------------------------------------------------------------- events

export function onClick(e) {
  const t = e.target;
  const chip = t.closest('[data-goto-cd]');
  if (chip) { state.cdEditing = null; document.querySelector('[data-tab="countdowns"]')?.click(); flash(Number(chip.dataset.gotoCd)); return true; }
  if (t.closest('[data-cd-add]')) { openAdd(); return true; }
  if (t.closest('[data-cd-cancel]')) { state.cdAdding = false; state.cdEditing = null; state.cdDraft = ''; render(); return true; }
  if (t.closest('[data-cd-more]')) {
    const q = document.querySelector('#cd-quick [name=q]')?.value || '';
    const p = C.parseCountdown(q);
    openAdd(p ? { title: p.title, at: p.at, all_day: p.all_day, repeat: p.repeat, after: p.after } : { title: q.trim() });
    return true;
  }
  const exam = t.closest('[data-cd-exam]');
  if (exam) {
    const s = suggestions().find((x) => x.e.id === Number(exam.dataset.cdExam));
    if (s) fromExam(s.e, s.title);
    return true;
  }
  const dis = t.closest('[data-cd-dismiss]');
  if (dis) {
    const set = dismissed();
    set.add(Number(dis.dataset.cdDismiss));
    try { localStorage.setItem(DISMISS_KEY, JSON.stringify([...set].slice(-200))); } catch { /* storage unavailable */ }
    render();
    return true;
  }
  if (t.closest('.hb-archived > summary') && state.tab === 'countdowns') { state.cdShowPast = !t.closest('details').open; return false; }
  const el = t.closest('[data-cd]');
  const formEl = t.closest('#cd-form');
  const id = el ? Number(el.dataset.cd) : formEl && formEl.dataset.id !== 'new' ? Number(formEl.dataset.id) : null;
  if (t.closest('[data-cd-delete]') && id) { remove(id); return true; }
  if (!el) return false;
  if (t.closest('[data-cd-pin]')) { const c = byId(id); update(id, { pinned: !c.pinned }, c.pinned ? 'Unpinned.' : `Pinned “${c.title}” to Today and the sidebar.`); return true; }
  if (t.closest('[data-cd-edit]')) { state.cdEditing = id; state.cdAdding = false; render(); return true; }
  return false;
}

document.addEventListener('keydown', (e) => {
  if ((e.key === 'Enter' || e.key === ' ') && e.target.matches?.('[data-cd-edit]')) { e.preventDefault(); e.target.click(); }
});

export function onInput(e) {
  if (e.target.closest('#cd-quick')) { state.cdDraft = e.target.value; refreshChips(); return true; }
  if (e.target.closest('#cd-form')) { preview(); return true; }
  return false;
}

export function onChange(e) {
  const f = e.target.closest('#cd-form');
  if (!f) return false;
  if (e.target.name === 'color') f.style.cssText = `--hc:var(--c-${e.target.value})`;
  preview();
  return true;
}

export function onSubmit(e) {
  if (e.target.id === 'cd-quick') { e.preventDefault(); createFromText(new FormData(e.target).get('q')); return true; }
  if (e.target.id !== 'cd-form') return false;
  e.preventDefault();
  const body = readForm(e.target);
  if (body.at == null) { toast('Pick a date.', true); return true; }
  const id = e.target.dataset.id;
  if (id === 'new') create({ ...body, ...(state.cdPreset?.event_id ? { event_id: state.cdPreset.event_id } : {}) });
  else update(Number(id), body, 'Countdown saved.');
  return true;
}
