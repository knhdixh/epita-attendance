import { TAGS, splitGroup, effectiveTag, statsByCourse, computeStats } from './stats.js';
import * as hw from './homework-ui.js';
import * as ai from './assistant-ui.js';
import * as brk from './breaks-ui.js';
import * as byok from './byok-ui.js';
import { passkeysSupported, registerPasskey, signInWithPasskey } from './passkey.js';

const $app = document.getElementById('app');
const $tabs = document.getElementById('tabs');
const $account = document.getElementById('account');
const $file = document.getElementById('file-input');
const $toast = document.getElementById('toast');

const state = {
  data: null,           // { user, courses, events }
  tab: 'overview',
  filter: { course: 'all', when: 'upcoming', showHidden: false, q: '' },
};

// ---------------------------------------------------------------- helpers

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const pct = (x) => `${Math.round(x * 1000) / 10}%`;
const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const fmtDay = (ms) => new Date(ms).toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
const fmtShort = (ms) => new Date(ms).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
const dayKey = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`; };
const unitLabel = (n, unit) => unit === 'hours' ? `${n} h` : `${n} session${n === 1 ? '' : 's'}`;

async function api(path, { method = 'GET', body } = {}) {
  let res;
  try {
    res = await fetch(path, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new Error('Could not reach the server. Check your connection, or whether an ad/tracker blocker is blocking this site.');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

let toastTimer;
function toast(msg, isError = false) {
  $toast.textContent = msg;
  $toast.className = 'toast' + (isError ? ' error' : '');
  $toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($toast.hidden = true), isError ? 6000 : 3000);
}

const courseById = () => Object.fromEntries(state.data.courses.map((c) => [c.id, c]));
const opts = () => ({ threshold: state.data.user.threshold, unit: state.data.user.unit, now: Date.now() });
const trackedEvents = () => {
  const tracked = new Set(state.data.courses.filter((c) => c.tracked).map((c) => c.id));
  return state.data.events.filter((e) => tracked.has(e.course_id));
};

// Group-specific courses ("GR3 - French …") grouped by family.
function groupFamilies() {
  const fam = new Map();
  for (const c of state.data.courses) {
    const g = splitGroup(c.name);
    if (!g) continue;
    if (!fam.has(g.family)) fam.set(g.family, []);
    fam.get(g.family).push({ ...c, group: g.group });
  }
  for (const list of fam.values()) list.sort((a, b) => a.group.localeCompare(b.group, undefined, { numeric: true }));
  return [...fam.entries()].sort(([a], [b]) => a.localeCompare(b));
}

// ---------------------------------------------------------------- boot

async function boot() {
  const params = new URLSearchParams(location.search);
  if (params.get('auth_error')) {
    const msg = { domain: 'That Google account is not allowed to sign in here.', state: 'Sign-in expired, please try again.' }[params.get('auth_error')]
      || 'Google sign-in failed. Please try again.';
    history.replaceState(null, '', '/');
    setTimeout(() => toast(msg, true));
  }
  const { user } = await api('/api/me');
  if (!user) return renderLogin();
  await reload();
  const fromHash = location.hash.slice(1);
  if (['overview', 'sessions', 'homework', 'breaks', 'assistant', 'courses', 'settings'].includes(fromHash)) state.tab = fromHash;
  render();
  hw.handleRedirectParams(params);
}

async function reload() {
  state.data = await api('/api/data');
}

async function renderLogin() {
  document.body.classList.add('signed-out');
  $tabs.hidden = true;
  $account.innerHTML = '';
  const cfg = await api('/auth/config');
  const pk = cfg.passkeys && passkeysSupported();
  $app.innerHTML = `
    <h1 class="display" aria-label="attendance80"><span class="word" aria-hidden="true">${maskedWord('attendance80')}</span><span class="cursor" aria-hidden="true"></span></h1>
    <div class="signin">
      ${pk ? '<button class="btn primary" data-action="pk-login">Sign in with a passkey</button>' : ''}
      ${cfg.google ? '<a class="btn" href="/auth/google">Continue with Google</a>' : ''}
      ${pk && cfg.signup ? `
        <form id="pk-signup" class="signup-row">
          <input name="name" required maxlength="60" placeholder="Your name" autocomplete="name" aria-label="Your name">
          <button class="btn" type="submit">Create account</button>
        </form>
        <p>Face ID, fingerprint or PIN. No password.</p>` : ''}
      ${!pk && cfg.passkeys ? '<p>This browser has no passkeys. Use Google.</p>' : ''}
      ${cfg.dev ? '<a class="btn" href="/auth/dev">Dev login</a>' : ''}
    </div>
    <section class="panel landing-rows">
      <h3><span>Index</span><span>03</span></h3>
      <div class="cards">
        ${[['/ics-guide', 'calendar', 'export from zeus'], ['/privacy', 'privacy', 'policy'], ['/terms', 'terms', 'of service']]
          .map(([href, name, what], i) => `<a class="card" href="${href}"><span class="num">${pad2(i + 1)}</span>
            <header><h4 data-scramble>${name}</h4></header><div class="headline">${what}<span class="arrow">↗</span></div></a>`).join('')}
      </div>
    </section>
    ${footer()}`;
  reveal();
  const fluid = document.createElement('script');
  fluid.src = '/fluid.js';
  document.body.append(fluid);
}

// Sign-in buttons and methods are text only (knhdixh cookbook: no icons).
const keyIcon = () => '';
const googleIcon = () => '';

// ---------------------------------------------------------------- render

function render() {
  const { user } = state.data;
  $account.innerHTML = `
    <span class="import-group"><button class="btn small" data-action="upload" title="Upload or re-import an .ics file">import</button><a class="help-dot" href="/ics-guide" title="Where do I get my .ics file?" aria-label="How to get your .ics file">?</a></span>
    <span class="email">${esc(user.email || user.name || '')}</span>
    <button class="btn small ghost" data-action="logout">sign out</button>`;
  document.body.classList.remove('signed-out');
  document.getElementById('fluid')?.remove();

  // The reveal plays only when the view changes, never on the re-render after a click.
  const view = state.data.events.length || state.tab === 'settings' ? state.tab : 'empty';
  const changed = view !== lastView;
  const first = lastView === null;
  lastView = view;

  if (view === 'empty') {
    $tabs.hidden = true;
    renderEmpty();
  } else {
    $tabs.hidden = false;
    for (const b of $tabs.querySelectorAll('button')) b.classList.toggle('active', b.dataset.tab === state.tab);
    ({ overview: renderOverview, sessions: renderSessions, homework: hw.renderHomework, breaks: brk.renderBreaks, assistant: ai.renderAssistant, courses: renderCourses, settings: renderSettings })[state.tab]();
    $app.insertAdjacentHTML('afterbegin', pageHead(state.tab));
  }
  $app.insertAdjacentHTML('beforeend', footer());
  indexRows();
  if (changed) reveal({ quick: !first });
}

let lastView = null;
const pad2 = (n) => String(n).padStart(2, '0');
const footer = () => `<footer class="foot"><span>${esc(location.hostname)}</span><span>${new Date().getFullYear()}</span></footer>`;
const pageHead = (label) => `<header class="page-head"><h2 aria-label="${esc(label)}"><span class="word" aria-hidden="true">${maskedWord(label)}</span></h2></header>`;

// ---------------------------------------------------------------- motion (knhdixh cookbook §4)

const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const GLYPHS = 'abcdefghijklmnopqrstuvwxyz0123456789';
const randGlyph = () => GLYPHS[(Math.random() * GLYPHS.length) | 0];
const ROWS = '.row, .hw-item, .card, .method, .family, .window, .msg, .byok-option, .stat';
let settleTimer;

// A heading split into masked characters that rise and cycle glyphs (see scrambleIn).
function maskedWord(word) {
  return [...word].map((c, i) => `<span class="mask"><span class="ch" style="--i:${i}" data-c="${esc(c)}">${esc(c)}</span></span>`).join('');
}

// Stagger index for each row. Capped so long lists don't keep the page waiting.
function indexRows() {
  $app.querySelectorAll(ROWS).forEach((el, i) => el.style.setProperty('--i', Math.min(i, 14)));
  $app.querySelectorAll('table.courses tbody tr, .cal-day, .weeks > .week').forEach((el, i) => el.style.setProperty('--i', Math.min(i, 60)));
}

// Each character cycles through random glyphs while it rises, then settles. Returns when it is done.
function scrambleIn(word, base) {
  const chars = [...word.querySelectorAll('.ch')];
  chars.forEach((ch, i) => {
    const final = ch.dataset.c, start = base + i * 55, settle = start + 420 + i * 30, t0 = performance.now();
    (function frame(now) {
      const t = now - t0;
      if (t >= settle) { ch.textContent = final; return; }
      if (t >= start) ch.textContent = randGlyph();
      requestAnimationFrame(frame);
    })(t0);
  });
  return base + chars.length * 85 + 450;
}

// Decodes a name left to right (row hover).
function decode(el) {
  const target = el.dataset.text || (el.dataset.text = el.textContent);
  if (el._raf) cancelAnimationFrame(el._raf);
  const t0 = performance.now(), dur = 380;
  (function frame(now) {
    const p = Math.min(1, (now - t0) / dur), fixed = Math.floor(p * target.length);
    let out = target.slice(0, fixed);
    for (let k = fixed; k < target.length; k++) out += /[\s-]/.test(target[k]) ? target[k] : randGlyph();
    el.textContent = out;
    if (p < 1) el._raf = requestAnimationFrame(frame);
  })(t0);
}

// Title rises, hairlines draw in, rows rise, footer fades. Tab changes use compressed delays.
function reveal({ quick = false } = {}) {
  clearTimeout(settleTimer);
  $app.classList.remove('ready', 'settled');
  $app.classList.toggle('quick', quick);
  const cursor = $app.querySelector('.cursor');
  if (reduced) { $app.classList.add('ready', 'settled'); cursor?.classList.add('on'); return; }
  requestAnimationFrame(() => requestAnimationFrame(() => {
    $app.classList.add('ready');
    const word = $app.querySelector('.page-head .word, .display .word');
    const done = word ? scrambleIn(word, quick ? 0 : 200) : 0;
    if (cursor) setTimeout(() => cursor.classList.add('on'), done);
    settleTimer = setTimeout(() => $app.classList.add('settled'), quick ? 1800 : 3300);
  }));
}

for (const type of ['mouseover', 'focusin']) {
  document.addEventListener(type, (e) => {
    const row = e.target.closest?.('.card');
    if (!row || reduced || (e.relatedTarget && row.contains(e.relatedTarget))) return;
    const name = row.querySelector('[data-scramble]');
    if (name) decode(name);
  });
}

function renderEmpty() {
  $app.innerHTML = `
    ${pageHead('import')}
    <section class="dropzone" data-action="upload" id="dropzone">
      <p>Drop your <code>.ics</code> here, or choose it.</p>
      <button class="btn primary" type="button">Choose file</button>
    </section>
    <p class="guide-link"><a href="/ics-guide">Export it from Zeus</a></p>
    ${state.data.account.google || state.data.account.passkeys.length > 1 ? '' : `
    <p class="guide-link"><a href="#settings" data-goto="settings">Add a second passkey</a> so you can always sign in.</p>`}`;
}

function groupBanner() {
  // Shown until the user has picked at least one group.
  const families = groupFamilies();
  if (!families.length || families.some(([, list]) => list.some((c) => c.tracked))) return '';
  return `<div class="banner">
    <span>Pick your groups for ${families.map(([f]) => esc(f)).join(', ')}.</span>
    <button class="btn small" data-goto="courses">Choose groups</button>
  </div>`;
}

function renderOverview() {
  const { user, courses, events } = state.data;
  const o = opts();
  const { perCourse, overall } = statsByCourse(courses, events, o);
  const now = Date.now();
  const tracked = perCourse.filter((p) => p.course.tracked && p.stats.sessions)
    .sort((a, b) => ({ fail: 0, risk: 1, ok: 2 })[a.stats.level] - ({ fail: 0, risk: 1, ok: 2 })[b.stats.level]
      || a.stats.remaining - b.stats.remaining);

  const today = trackedEvents().filter((e) => dayKey(e.start_at) === dayKey(now));
  const next = trackedEvents().find((e) => e.start_at > now);
  const unmarked = trackedEvents().filter((e) => e.start_at <= now && !e.status);

  const [whole, decimals] = pct(overall.projectedRate).replace('%', '').split('.');
  $app.innerHTML = `
    ${groupBanner()}
    <section class="ov">
      <div class="ov-stats">
        <div class="stat"><span class="n">${whole}<small>${decimals ? `.${decimals}` : ''}%</small></span><span class="l">projected</span></div>
        <div class="stat"><span class="n">${fmtNum(overall.absent)}<small>/${fmtNum(overall.total)}</small></span><span class="l">absences</span></div>
        <div class="stat"><span class="n">${unmarked.length}</span><span class="l">to mark</span></div>
      </div>
      <p class="ov-line ${overall.level}">${overviewLine(overall, tracked[0], user.threshold)}</p>
      ${unmarked.length ? '<button class="btn primary small" data-action="mark-all-present">Mark all attended</button>' : ''}
    </section>

    ${today.length || next ? `
    <section class="panel">
      <h3><span>${today.length ? 'Today' : 'Next'}</span><span>${pad2(today.length || 1)}</span></h3>
      <div class="list">${(today.length ? today : [next]).map((e) => sessionRow(e, { showDate: !today.length })).join('')}</div>
    </section>` : ''}

    ${hw.homeworkPanel()}

    <section class="panel">
      <h3><span>Courses</span><span>${pad2(tracked.length)}</span></h3>
      <div class="cards">
        ${tracked.map(({ course, stats }, i) => courseCard(course, stats, i)).join('') || '<p class="empty">No tracked courses.</p>'}
      </div>
    </section>`;
}

// One line of words for the overall state (knhdixh cookbook: state with words, not badges).
function overviewLine(overall, closest, threshold) {
  const parts = [`${{ ok: 'On track for', risk: 'Close to', fail: 'Below' }[overall.level]} ${pct(threshold)}.`];
  if (closest && closest.stats.level !== 'ok') parts.push(`${esc(closest.course.name)}: ${skipsLeft(closest.stats)}.`);
  return parts.join(' ');
}

// "2 left", "none left", "over by 1" (in sessions or hours).
function skipsLeft(s) {
  const h = state.data.user.unit === 'hours' ? ' h' : '';
  if (s.remaining < -1e-9) return `over by ${fmtNum(Math.ceil(-s.remaining * 2) / 2)}${h}`;
  return s.remainingDisplay > 0 ? `${fmtNum(s.remainingDisplay)}${h} left` : 'none left';
}

const fmtNum = (n) => (Number.isInteger(n) ? n : Math.round(n * 10) / 10);

function courseCard(course, s, i) {
  const thr = state.data.user.threshold * 100;
  const attendedW = s.total ? ((s.total - s.absent) / s.total) * 100 : 100;
  return `
    <article class="card ${s.level}" data-course-open="${course.id}" tabindex="0">
      <span class="num">${pad2(i + 1)}</span>
      <header>
        <h4 data-scramble>${esc(course.name)}</h4>
        ${course.tag !== 'normal' ? `<span class="chip tag-${course.tag}">${TAGS[course.tag].label.toLowerCase()}</span>` : ''}
      </header>
      <div class="headline">${skipsLeft(s)}<span class="arrow">↗</span></div>
      <div class="bar" title="Projected ${pct(s.projectedRate)}">
        <div class="fill" style="width:${attendedW}%"></div>
        <div class="mark" style="left:${thr}%"></div>
      </div>
      <div class="meta">
        <span>${pct(s.projectedRate)} projected</span>
        <span>${fmtNum(s.absent)} missed${s.plannedAbsent ? `, ${fmtNum(s.plannedAbsent)} planned` : ''}</span>
        <span>${s.upcoming} upcoming</span>
      </div>
      ${s.unmarkedPast ? `<div class="note">${s.unmarkedPast} unmarked</div>` : ''}
      ${s.missedMustAttend.length ? `<div class="note bad">Missed ${s.missedMustAttend.length} exam or mandatory</div>` : ''}
    </article>`;
}

function sessionRow(e, { showDate = false } = {}) {
  const course = courseById()[e.course_id];
  const tag = effectiveTag(e, course);
  const past = e.start_at <= Date.now();
  const st = e.status;
  const mustAttend = TAGS[tag].mustAttend;
  return `
    <div class="row ${past ? 'past' : 'future'} ${st ? 'st-' + st : past ? 'st-unmarked' : ''} ${course.tracked ? '' : 'untracked'}" data-event="${e.id}">
      <div class="when">
        ${showDate ? `<div class="date">${fmtShort(e.start_at)}</div>` : ''}
        <div>${fmtTime(e.start_at)}</div><div>${fmtTime(e.end_at)}</div>
      </div>
      <div class="what">
        <div class="title">${esc(e.summary)}</div>
        <div class="loc muted small">${esc(e.location)}${e.note ? ` · <em>${esc(e.note)}</em>` : ''}</div>
        ${hw.homeworkForSession(e).map((h) => `<a class="hw-badge" href="#homework" data-goto="homework" title="Homework due at this session">Due: ${esc(h.title)}</a>`).join('')}
      </div>
      <select class="tag-select tag-${tag}" data-field="tag" title="Session tag (default: course tag)">
        <option value="" ${!e.tag ? 'selected' : ''}>${TAGS[course.tag].label}</option>
        ${Object.entries(TAGS).map(([k, t]) => `<option value="${k}" ${e.tag === k ? 'selected' : ''}>${t.label}</option>`).join('')}
      </select>
      <div class="status" role="group" aria-label="Attendance">
        <button data-status="present" class="${st === 'present' ? 'on' : ''}" title="${past ? 'Attended' : 'Will attend'}">✓</button>
        <button data-status="absent" class="${st === 'absent' ? 'on' : ''} ${mustAttend && !past ? 'danger' : ''}" title="${past ? 'Absent' : 'Plan to skip'}">×</button>
        <button data-status="excused" class="${st === 'excused' ? 'on' : ''}" title="Excused (doesn't count)">E</button>
        <button data-action="note" title="Add a note">+</button>
      </div>
    </div>`;
}

function renderSessions() {
  const { courses, events, user } = state.data;
  const f = state.filter;
  const now = Date.now();
  const byId = courseById();
  let list = f.showHidden ? events : trackedEvents();
  if (f.course !== 'all') list = events.filter((e) => e.course_id === Number(f.course));
  if (f.when === 'upcoming') list = list.filter((e) => e.end_at > now);
  if (f.when === 'past') list = list.filter((e) => e.start_at <= now).reverse();
  if (f.when === 'unmarked') list = list.filter((e) => e.start_at <= now && !e.status);
  if (f.when === 'skips') list = list.filter((e) => e.status === 'absent');
  if (f.when === 'mustattend') list = list.filter((e) => TAGS[effectiveTag(e, byId[e.course_id])].mustAttend);
  if (f.q) {
    const q = f.q.toLowerCase();
    list = list.filter((e) => (e.summary + ' ' + e.location + ' ' + e.note).toLowerCase().includes(q));
  }

  const selectedCourse = f.course !== 'all' ? byId[Number(f.course)] : null;
  let courseSummary = '';
  if (selectedCourse) {
    const s = computeStats(events.filter((e) => e.course_id === selectedCourse.id), byId, opts());
    courseSummary = `<div class="course-summary ${s.level}">
      <strong>${esc(selectedCourse.name)}</strong>: ${pct(s.projectedRate)} projected ·
      ${s.remaining < -1e-9 ? '<span class="bad">over the absence limit</span>' : `${unitLabel(fmtNum(s.remainingDisplay), user.unit)} left to skip`}
      · ${fmtNum(s.absent)} of max ${fmtNum(Math.floor(s.allowedAbsence * 10 + 1e-9) / 10)} absences used
    </div>`;
  }

  const days = [];
  for (const e of list) {
    const k = dayKey(e.start_at);
    if (!days.length || days.at(-1).key !== k) days.push({ key: k, ms: e.start_at, items: [] });
    days.at(-1).items.push(e);
  }

  const shownCourses = courses.filter((c) => c.tracked || f.showHidden || String(c.id) === f.course);
  $app.innerHTML = `
    ${groupBanner()}
    <div class="filters">
      <select id="f-course">
        <option value="all">All ${f.showHidden ? '' : 'tracked '}courses</option>
        ${shownCourses.map((c) => `<option value="${c.id}" ${String(c.id) === f.course ? 'selected' : ''}>${esc(c.name)}</option>`).join('')}
      </select>
      <div class="seg" id="f-when">
        ${[['upcoming', 'Upcoming'], ['unmarked', 'Needs marking'], ['past', 'Past'], ['skips', 'Absences'], ['mustattend', 'Exams & mandatory'], ['all', 'All']]
          .map(([k, l]) => `<button data-when="${k}" class="${f.when === k ? 'on' : ''}">${l}</button>`).join('')}
      </div>
      <input id="f-q" type="search" placeholder="Search" value="${esc(f.q)}">
      <label class="check"><input type="checkbox" id="f-hidden" ${f.showHidden ? 'checked' : ''}> show untracked</label>
    </div>
    ${courseSummary}
    ${f.when === 'unmarked' && list.length ? `<p><button class="btn small" data-action="mark-shown-present">Mark these ${list.length} as attended</button></p>` : ''}
    <div class="days">
      ${days.map((d) => `
        <section class="day ${dayKey(now) === d.key ? 'today' : ''}">
          <h4><span>${fmtDay(d.ms)}</span>${dayKey(now) === d.key ? '<span>today</span>' : ''}</h4>
          <div class="list">${d.items.map((e) => sessionRow(e)).join('')}</div>
        </section>`).join('') || '<p class="muted empty">No sessions match.</p>'}
    </div>`;
  state.shownIds = list.map((e) => e.id);
}

function renderCourses() {
  const { courses, events, user } = state.data;
  const { perCourse } = statsByCourse(courses, events, opts());
  const families = groupFamilies();
  const groupedIds = new Set(families.flatMap(([, l]) => l.map((c) => c.id)));
  const hours = (id) => fmtNum(events.filter((e) => e.course_id === id).reduce((h, e) => h + (e.end_at - e.start_at) / 3600000, 0));
  const count = (id) => events.filter((e) => e.course_id === id).length;

  $app.innerHTML = `
    ${families.length ? `
    <section class="panel">
      <h3>Your groups</h3>
      <p class="muted small">Pick the group you belong to for each group-split course. Other groups' sessions are ignored.</p>
      <div class="families">
        ${families.map(([family, list]) => `
          <label class="family">
            <span>${esc(family)}</span>
            <select data-family="${esc(family)}">
              <option value="">Not in this course</option>
              ${list.map((c) => `<option value="${c.id}" ${c.tracked ? 'selected' : ''}>${esc(c.group)}</option>`).join('')}
              ${list.filter((c) => c.tracked).length > 1 ? '<option value="multi" selected>Several groups</option>' : ''}
            </select>
          </label>`).join('')}
      </div>
    </section>` : ''}
    <section class="panel">
      <h3>All courses</h3>
      <p class="muted small">Untracked courses don't count toward attendance. The tag applies to every session of the course unless a session overrides it.
        <br><b>Exam</b>/<b>Mandatory</b>: counted, and flagged if you skip them. <b>Optional</b>: never counted.</p>
      <table class="courses">
        <thead><tr><th>Track</th><th>Course</th><th>Default tag</th><th class="num">Sessions</th><th class="num">Hours</th><th class="num">Projected</th></tr></thead>
        <tbody>
          ${perCourse.map(({ course: c, stats: s }) => `
            <tr class="${c.tracked ? '' : 'off'}" data-course="${c.id}">
              <td><input type="checkbox" data-field="tracked" ${c.tracked ? 'checked' : ''} aria-label="Track ${esc(c.name)}"></td>
              <td><a href="#" data-course-open="${c.id}">${esc(c.name)}</a>${groupedIds.has(c.id) ? ' <span class="chip">group</span>' : ''}</td>
              <td><select data-field="tag" class="tag-${c.tag}">${Object.entries(TAGS).map(([k, t]) => `<option value="${k}" ${c.tag === k ? 'selected' : ''}>${t.label}</option>`).join('')}</select></td>
              <td class="num">${count(c.id)}</td>
              <td class="num">${hours(c.id)}</td>
              <td class="num ${c.tracked ? s.level : ''}">${c.tracked ? pct(s.projectedRate) : '—'}</td>
            </tr>`).join('')}
        </tbody>
      </table>
    </section>`;
}

function renderSettings() {
  const { user } = state.data;
  $app.innerHTML = `
    <section class="panel narrow">
      <h3>Attendance rules</h3>
      <form id="settings-form" class="form">
        <label>Minimum attendance
          <span class="input-suffix"><input type="number" name="threshold" min="1" max="100" step="1" value="${Math.round(user.threshold * 100)}"> %</span>
        </label>
        <label>Count attendance by
          <select name="unit">
            <option value="sessions" ${user.unit === 'sessions' ? 'selected' : ''}>Number of sessions</option>
            <option value="hours" ${user.unit === 'hours' ? 'selected' : ''}>Hours (longer sessions weigh more)</option>
          </select>
        </label>
        <button class="btn primary" type="submit">Save</button>
      </form>
      <p class="muted small">Unmarked future sessions are assumed attended. Mark a future session × to plan a skip and see its effect.
        Excused (E) sessions are removed from the total, as are sessions tagged Optional.</p>
    </section>
    <section class="panel narrow">
      <h3>Calendar</h3>
      <p>Re-import your <code>.ics</code> whenever the school publishes changes. Sessions are matched by their ID, so your marks are kept.
        <a href="/ics-guide">How to export it from Zeus</a>.</p>
      <button class="btn" data-action="upload">Import .ics</button>
    </section>
    ${signInMethodsPanel()}
    ${byok.settingsPanel()}
    ${hw.integrationsPanel()}
    <section class="panel narrow danger-zone">
      <h3>Delete data</h3>
      <p>Remove all imported sessions, courses and attendance marks from your account. Homework is kept.</p>
      <button class="btn danger" data-action="wipe">Delete all my data</button>
      <h3 class="danger-title">Delete my account</h3>
      <p>Permanently delete your account and everything in it: calendar, attendance, homework, breaks, passkeys,
        AI key and Google Calendar connection (access is revoked; the "Homework (Attendance80)" calendar stays in your
        Google account for you to delete).</p>
      <button class="btn danger" data-action="delete-account">Delete my account</button>
    </section>
    <p class="muted small center legal-links"><a href="/privacy">Privacy policy</a> · <a href="/terms">Terms of service</a></p>`;
}

function signInMethodsPanel() {
  const { account, user } = state.data;
  const fmt = (ms) => new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
  return `
    <section class="panel narrow">
      <h3>Sign-in methods</h3>
      <div class="list">
        ${account.google ? `<div class="method"><span>${googleIcon()}</span><div><b>Google</b><div class="muted small">${esc(user.email || '')}</div></div></div>` : ''}
        ${account.passkeys.map((p) => `
          <div class="method">
            <span>${keyIcon()}</span>
            <div><b>${esc(p.name || 'Passkey')}</b>${p.backed_up ? ' <span class="chip">synced</span>' : ''}
              <div class="muted small">Added ${fmt(p.created_at)}${p.last_used_at ? ` · last used ${fmt(p.last_used_at)}` : ''}</div></div>
            <button class="btn small ghost" data-remove-passkey="${esc(p.id)}">Remove</button>
          </div>`).join('')}
      </div>
      ${passkeysSupported() ? `<button class="btn" data-action="pk-add">${keyIcon()} Add a passkey${account.passkeys.length ? ' on another device' : ''}</button>` : ''}
      <p class="muted small">${account.google
        ? 'Add a passkey to sign in with Face ID, fingerprint or PIN instead of Google.'
        : 'Add a passkey on a second device (or use a synced one) so you can always get back in.'}</p>
    </section>`;
}

async function passkeyAction(fn, okMsg) {
  try {
    await fn();
    if (okMsg) toast(okMsg);
    return true;
  } catch (err) {
    toast(err.message, true);
    return false;
  }
}

// ---------------------------------------------------------------- mutations

function findEvent(id) { return state.data.events.find((e) => e.id === id); }

async function setEventField(id, patch) {
  const ev = findEvent(id);
  const before = { ...ev };
  Object.assign(ev, patch);
  render();
  try {
    await api(`/api/attendance/${id}`, { method: 'PATCH', body: patch });
  } catch (err) {
    Object.assign(ev, before);
    render();
    toast(err.message, true);
  }
}

async function bulkStatus(ids, status) {
  if (!ids.length) return;
  const set = new Set(ids);
  for (const e of state.data.events) if (set.has(e.id)) e.status = status;
  render();
  try {
    await api('/api/attendance/bulk', { method: 'POST', body: { ids, status } });
    toast(`Marked ${ids.length} session${ids.length > 1 ? 's' : ''}.`);
  } catch (err) {
    toast(err.message, true);
    await reload(); render();
  }
}

async function setCourseField(id, patch) {
  const c = state.data.courses.find((x) => x.id === id);
  Object.assign(c, patch);
  render();
  try {
    await api(`/api/courses/${id}`, { method: 'PATCH', body: patch });
  } catch (err) {
    toast(err.message, true);
    await reload(); render();
  }
}

async function setTracked(updates) {
  for (const u of updates) state.data.courses.find((c) => c.id === u.id).tracked = u.tracked ? 1 : 0;
  render();
  try {
    await api('/api/courses/tracked', { method: 'POST', body: { updates } });
  } catch (err) {
    toast(err.message, true);
    await reload(); render();
  }
}

async function importFile(file) {
  if (!file) return;
  try {
    const ics = await file.text();
    if (!ics.includes('BEGIN:VCALENDAR')) throw new Error('That does not look like an .ics calendar file.');
    const r = await api('/api/import', { method: 'POST', body: { ics } });
    await reload();
    if (state.tab === 'settings') state.tab = 'overview';
    render();
    const parts = [`${r.added} new`, `${r.updated} updated`];
    if (r.removed) parts.push(`${r.removed} removed`);
    if (r.recurringIgnored) parts.push(`${r.recurringIgnored} recurring events only imported once`);
    toast(`Imported ${r.total} sessions (${parts.join(', ')}).`);
  } catch (err) {
    toast(err.message, true);
  }
}

// ---------------------------------------------------------------- events

$tabs.addEventListener('click', (e) => {
  const b = e.target.closest('button[data-tab]');
  if (!b) return;
  state.tab = b.dataset.tab;
  history.replaceState(null, '', '#' + state.tab);
  render();
});

document.addEventListener('click', async (e) => {
  if (byok.onClick(e) || await hw.onClick(e) || ai.onClick(e) || brk.onClick(e)) return;
  const t = e.target;
  const action = t.closest('[data-action]')?.dataset.action;
  const row = t.closest('[data-event]');

  if (action === 'upload') return $file.click();
  if (action === 'pk-login') {
    if (await passkeyAction(signInWithPasskey)) location.href = '/';
    return;
  }
  if (action === 'pk-add') {
    if (await passkeyAction(() => registerPasskey(), 'Passkey added.')) { await reload(); render(); }
    return;
  }
  const rmPk = t.closest('[data-remove-passkey]');
  if (rmPk) {
    if (!confirm('Remove this passkey? You won\'t be able to sign in with it any more.')) return;
    if (await passkeyAction(() => api(`/api/passkeys/${rmPk.dataset.removePasskey}`, { method: 'DELETE' }), 'Passkey removed.')) { await reload(); render(); }
    return;
  }
  if (action === 'logout') {
    await api('/auth/logout', { method: 'POST' });
    location.href = '/';
    return;
  }
  if (action === 'mark-all-present') {
    return bulkStatus(trackedEvents().filter((x) => x.start_at <= Date.now() && !x.status).map((x) => x.id), 'present');
  }
  if (action === 'mark-shown-present') return bulkStatus(state.shownIds, 'present');
  if (action === 'delete-account') {
    const typed = prompt('This permanently deletes your account and all its data. It cannot be undone.\n\nType DELETE to confirm.');
    if (typed === null) return;
    if (typed.trim() !== 'DELETE') return toast('Account not deleted: you need to type DELETE.', true);
    try {
      await api('/api/account', { method: 'DELETE', body: { confirm: 'DELETE' } });
      try { localStorage.clear(); } catch { /* storage unavailable */ }
      location.href = '/';
    } catch (err) { toast(err.message, true); }
    return;
  }
  if (action === 'wipe') {
    if (!confirm('Delete all your sessions and attendance marks? This cannot be undone.')) return;
    await api('/api/data', { method: 'DELETE' });
    await reload(); state.tab = 'overview'; render();
    return toast('All data deleted.');
  }
  if (action === 'note' && row) {
    const ev = findEvent(Number(row.dataset.event));
    const note = prompt('Note for this session (e.g. reason for absence):', ev.note);
    if (note !== null) setEventField(ev.id, { note: note.slice(0, 2000) });
    return;
  }

  const statusBtn = t.closest('button[data-status]');
  if (statusBtn && row) {
    const ev = findEvent(Number(row.dataset.event));
    const status = ev.status === statusBtn.dataset.status ? null : statusBtn.dataset.status;
    return setEventField(ev.id, { status });
  }

  const goto = t.closest('[data-goto]');
  if (goto) { state.tab = goto.dataset.goto; history.replaceState(null, '', '#' + state.tab); return render(); }

  const open = t.closest('[data-course-open]');
  if (open) {
    e.preventDefault();
    state.tab = 'sessions';
    state.filter.course = open.dataset.courseOpen;
    state.filter.when = 'all';
    history.replaceState(null, '', '#sessions');
    render();
    document.querySelector('.day.today, .row.future')?.scrollIntoView({ block: 'center' });
    return;
  }

  const when = t.closest('[data-when]');
  if (when) { state.filter.when = when.dataset.when; return render(); }
});

document.addEventListener('keydown', (e) => {
  if (ai.onKeydown(e)) return;
  if (e.key === 'Enter' && e.target.matches('[data-course-open]')) e.target.click();
});

document.addEventListener('change', (e) => {
  if (byok.onChange(e) || hw.onChange(e) || brk.onChange(e)) return;
  const t = e.target;
  if (t.id === 'f-course') { state.filter.course = t.value; return render(); }
  if (t.id === 'f-hidden') { state.filter.showHidden = t.checked; return render(); }

  const row = t.closest('[data-event]');
  if (row && t.dataset.field === 'tag') return setEventField(Number(row.dataset.event), { tag: t.value || null });

  const crow = t.closest('[data-course]');
  if (crow) {
    const id = Number(crow.dataset.course);
    if (t.dataset.field === 'tracked') return setCourseField(id, { tracked: t.checked ? 1 : 0 });
    if (t.dataset.field === 'tag') return setCourseField(id, { tag: t.value });
  }

  if (t.dataset.family !== undefined) {
    if (t.value === 'multi') return;
    const fam = groupFamilies().find(([f]) => f === t.dataset.family)[1];
    return setTracked(fam.map((c) => ({ id: c.id, tracked: String(c.id) === t.value })));
  }
});

let searchTimer;
document.addEventListener('input', (e) => {
  if (ai.onInput(e) || brk.onInput(e)) return;
  if (e.target.id !== 'f-q') return;
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    state.filter.q = e.target.value;
    render();
    const q = document.getElementById('f-q');
    q.focus(); q.setSelectionRange(q.value.length, q.value.length);
  }, 200);
});

document.addEventListener('submit', async (e) => {
  if (byok.onSubmit(e) || hw.onSubmit(e) || ai.onSubmit(e) || brk.onSubmit(e)) return;
  if (e.target.id === 'pk-signup') {
    e.preventDefault();
    const name = new FormData(e.target).get('name');
    if (await passkeyAction(() => registerPasskey(name))) location.href = '/';
    return;
  }
  if (e.target.id !== 'settings-form') return;
  e.preventDefault();
  const fd = new FormData(e.target);
  const body = { threshold: Number(fd.get('threshold')) / 100, unit: fd.get('unit') };
  try {
    await api('/api/settings', { method: 'PATCH', body });
    Object.assign(state.data.user, body);
    toast('Settings saved.');
    render();
  } catch (err) { toast(err.message, true); }
});

$file.addEventListener('change', () => { importFile($file.files[0]); $file.value = ''; });

// Drag & drop anywhere on the page.
document.addEventListener('dragover', (e) => { e.preventDefault(); document.body.classList.add('dragging'); });
document.addEventListener('dragleave', (e) => { if (!e.relatedTarget) document.body.classList.remove('dragging'); });
document.addEventListener('drop', (e) => {
  e.preventDefault();
  document.body.classList.remove('dragging');
  if (state.data) importFile(e.dataTransfer.files[0]);
});

boot().catch((err) => {
  $app.innerHTML = `<p class="error">Failed to load: ${esc(err.message)}</p>`;
});

export { state, esc, api, toast, render, reload, courseById, fmtTime };
