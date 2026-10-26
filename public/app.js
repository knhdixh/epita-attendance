import { TAGS, splitGroup, effectiveTag, statsByCourse, computeStats, countsAttendance } from './stats.js';
import * as hw from './homework-ui.js';
import * as ai from './assistant-ui.js';
import * as brk from './breaks-ui.js';
import * as byok from './byok-ui.js';
import * as cal from './calendars-ui.js';
import * as today from './today-ui.js';
import * as tokens from './tokens-ui.js';
import * as fx from './focus-ui.js';
import { passkeysSupported, registerPasskey, signInWithPasskey } from './passkey.js';
import * as palette from './palette.js';

const $app = document.getElementById('app');
const $scroller = document.getElementById('scroller');
const $sbMain = document.getElementById('sb-main');
const $sbCourses = document.getElementById('sb-courses');
const $accountBtn = document.getElementById('account-btn');
const $accountMenu = document.getElementById('account-menu');
const $topTitle = document.getElementById('topbar-title');
const $scope = document.getElementById('scope');
const $toast = document.getElementById('toast');

const state = {
  data: null,           // { user, courses, events }
  tab: 'today',
  filter: { course: 'all', when: 'upcoming', showHidden: false, q: '' },
};

// ---------------------------------------------------------------- helpers

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const pct = (x) => `${Math.round(x * 1000) / 10}%`;
const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
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
  if (!res.ok) throw Object.assign(new Error(data.error || `Request failed (${res.status})`), { status: res.status });
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
  if (fromHash in TITLES) state.tab = fromHash;
  try { if (localStorage.getItem(SIDEBAR_KEY) === 'closed') document.body.classList.add('sidebar-closed'); } catch { /* storage unavailable */ }
  render();
  fx.init();
  hw.handleRedirectParams(params);
}

async function reload() {
  const data = await api('/api/data');
  data.allEvents = data.events;
  fx.adopt(data);
  state.data = data;
  applyScope();
}

// ---------------------------------------------------------------- groups (calendars)
// A session can be in several calendars, e.g. a lecture shared by groups SE1, SE2 and SE3 (e.cals lists them).
// The scope picks which calendars count: every view and all the attendance maths only see sessions in at least
// one of them (state.data.events), while state.data.allEvents keeps everything. An empty scope means all.

const SCOPE_KEY = 'a80-scope';

function applyScope() {
  const d = state.data;
  const valid = new Set(d.calendars.map((c) => c.id));
  let saved = state.scope;
  if (!saved) { try { saved = JSON.parse(localStorage.getItem(SCOPE_KEY) || '[]'); } catch { saved = []; } }
  const scope = Array.isArray(saved) ? saved.filter((id) => valid.has(id)) : [];
  state.scope = scope.length < valid.size ? scope : [];
  const on = new Set(state.scope);
  d.events = on.size ? d.allEvents.filter((e) => !e.cals.length || e.cals.some((c) => on.has(c))) : d.allEvents;
  // The calendars each course has sessions in (from all sessions, so labels don't change with the scope).
  d.courseCals = new Map();
  for (const e of d.allEvents) {
    if (!d.courseCals.has(e.course_id)) d.courseCals.set(e.course_id, new Set());
    for (const c of e.cals) d.courseCals.get(e.course_id).add(c);
  }
}

function setScope(ids) {
  state.scope = ids;
  try { localStorage.setItem(SCOPE_KEY, JSON.stringify(ids)); } catch { /* storage unavailable */ }
  applyScope();
  render();
}

// "SE1 · SE3" for the calendars a session or course is in, "All groups" when it's in every one, and nothing
// when the user has a single calendar.
function groupLabel(ids) {
  const cals = state.data.calendars;
  const set = new Set(ids || []);
  const names = cals.filter((c) => set.has(c.id)).map((c) => c.name); // in the order of the calendar list
  if (cals.length < 2 || !names.length) return '';
  return names.length === cals.length ? 'All groups' : names.join(' · ');
}

// The label as a chip. `all: false` leaves out "All groups" where space is tight (sidebar).
function groupChip(ids, { all = true } = {}) {
  const label = groupLabel(ids);
  if (!label || (!all && label === 'All groups')) return '';
  return `<span class="chip group${label === 'All groups' ? ' all' : ''}" title="Calendars: ${esc(label)}">${esc(label)}</span>`;
}
const courseGroups = (courseId) => state.data.courseCals.get(courseId);

// "SE1 + SE3" when only some calendars count, for page subtitles.
function scopeNote() {
  if (!state.scope.length) return '';
  const names = new Map(state.data.calendars.map((c) => [c.id, c.name]));
  return `Counting ${state.scope.map((id) => names.get(id)).join(' + ')} only`;
}

// The toggle in the top bar: All, or any combination of calendars.
function renderScope(view) {
  const cals = state.data.calendars;
  $scope.hidden = cals.length < 2 || view === 'empty';
  if ($scope.hidden) return;
  const on = new Set(state.scope);
  const chip = (id, label, pressed) => `<button class="scope-chip" data-scope="${id}" aria-pressed="${pressed}">${esc(label)}</button>`;
  $scope.innerHTML = `<span class="scope-label">Count</span>${chip('all', 'All', !on.size)}${cals.map((c) => chip(c.id, c.name, on.has(c.id))).join('')}`;
}

// From "all", a calendar alone; otherwise toggle it. None or every calendar means all again.
function toggleScope(value) {
  if (value === 'all') return setScope([]);
  const id = Number(value);
  const on = new Set(state.scope);
  if (!on.size) on.add(id); else if (on.has(id)) on.delete(id); else on.add(id);
  setScope(on.size >= state.data.calendars.length ? [] : [...on]);
}

async function renderLogin() {
  document.body.classList.add('signed-out');
  const cfg = await api('/auth/config');
  const pk = cfg.passkeys && passkeysSupported();
  $app.innerHTML = `
    <section class="signin-card">
      <div class="app-icon" aria-hidden="true">80</div>
      <h1 aria-label="Attendance80"><span class="word" aria-hidden="true">${maskedWord('Attendance80')}</span></h1>
      <p class="lede">Know how many classes you can still miss, in every course.</p>
      <div class="signin">
        ${pk ? '<button class="btn btn-primary btn-large" data-action="pk-login">Sign in with a passkey</button>' : ''}
        ${cfg.google ? '<a class="btn btn-large" href="/auth/google">Continue with Google</a>' : ''}
        ${pk && cfg.signup ? `
          <form id="pk-signup" class="signup-row">
            <input class="field" name="name" required maxlength="60" placeholder="Your name" autocomplete="name" aria-label="Your name">
            <button class="btn btn-default" type="submit">Create account</button>
          </form>
          <p class="hint">Face ID, fingerprint or PIN. No password.</p>` : ''}
        ${!pk && cfg.passkeys ? '<p class="hint">This browser has no passkeys. Use Google.</p>' : ''}
        ${cfg.dev ? '<a class="btn btn-borderless" href="/auth/dev">Dev login</a>' : ''}
      </div>
      <p class="links"><a href="/ics-guide">Get your .ics</a> · <a href="/privacy">Privacy</a> · <a href="/terms">Terms</a></p>
    </section>`;
  playEnter(true);
  // Water cursor trail (knhdixh cookbook §5), only behind the sign-in card. Sign out reloads the page, so it loads once.
  if (!reduced && matchMedia('(pointer: fine)').matches && !document.getElementById('fluid')) {
    const canvas = document.createElement('canvas');
    canvas.id = 'fluid';
    canvas.setAttribute('aria-hidden', 'true');
    document.body.prepend(canvas);
    const script = document.createElement('script');
    script.src = '/fluid.js';
    document.body.append(script);
  }
}

// Sign-in buttons and methods are text only (knhdixh cookbook: no icons).
const keyIcon = () => '';
const googleIcon = () => '';

// ---------------------------------------------------------------- render

function render() {
  document.body.classList.remove('signed-out');
  document.getElementById('fluid')?.remove();

  // Entrance animation only when the view changes, never on the re-render after a click.
  const view = state.data.events.length || state.tab === 'settings' || state.tab === 'focus' ? state.tab : 'empty';
  const changed = view !== lastView;
  lastView = view;

  renderSidebar(view);
  renderScope(view);
  if (view === 'empty') renderEmpty();
  else {
    ({ today: today.renderToday, overview: renderOverview, upcoming: renderUpcoming, sessions: renderSessions, homework: hw.renderHomework, breaks: brk.renderBreaks, focus: fx.renderFocus, assistant: ai.renderAssistant, courses: renderCourses, settings: renderSettings })[state.tab]();
    $app.insertAdjacentHTML('afterbegin', pageHead(TITLES[state.tab], pageSub()));
  }
  $topTitle.textContent = view === 'empty' ? 'Add your timetable' : TITLES[state.tab];
  document.title = `${$topTitle.textContent} · Attendance80`;
  $app.classList.toggle('wide', view === 'breaks');
  // The entrance plays once, on first load; switching pages just swaps the content.
  if (changed) {
    $scroller.scrollTop = 0;
    if (first) playEnter(true);
  }
  first = false;
  fx.paint();
  fx.afterRender();
}

let lastView = null;
let first = true;
const TITLES = { today: 'Today', overview: 'Attendance', upcoming: 'Upcoming', sessions: 'Sessions', homework: 'Homework', focus: 'Focus', breaks: 'Breaks', assistant: 'Assistant', courses: 'Courses', settings: 'Settings' };
const pageHead = (label, sub = '') => `<header class="page-head"><h1>${esc(label)}</h1>${sub ? `<span class="sub">${sub}</span>` : ''}</header>`;

// The small grey text on the right of the page title.
function pageSub() {
  const scope = ['overview', 'upcoming', 'sessions', 'courses', 'breaks'].includes(state.tab) ? scopeNote() : '';
  const parts = [];
  if (state.tab === 'today' || state.tab === 'overview') parts.push(new Date().toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' }));
  if (state.tab === 'homework') {
    const open = state.data.homework.filter((h) => !h.done).length;
    if (open) parts.push(`${open} open`);
  }
  if (state.tab === 'focus') parts.push(fx.subtitle());
  if (state.tab === 'upcoming' && state.weekCount != null) parts.push(`${state.weekCount} session${state.weekCount === 1 ? '' : 's'} this week`);
  if (state.tab === 'sessions' && state.shownIds) parts.push(`${state.shownIds.length} session${state.shownIds.length === 1 ? '' : 's'}`);
  return [esc(parts.join(' · ')), scope && `<span class="scope-note">${esc(scope)}</span>`].filter(Boolean).join(' · ');
}

const unmarkedEvents = () => {
  const byId = courseById();
  return trackedEvents().filter((e) => e.start_at <= Date.now() && !e.status && countsAttendance(byId[e.course_id]));
};

// Account, counts and the course list. Sessions filtered to one course highlight that course instead of Sessions.
function renderSidebar(view) {
  const { user, homework, courses, events } = state.data;
  const name = user.name || user.email || 'Account';
  $accountBtn.querySelector('.avatar').textContent = name.trim()[0] || '?';
  $accountBtn.querySelector('.account-name').textContent = name;
  document.getElementById('account-email').textContent = user.email || '';
  $sbMain.hidden = view === 'empty';
  if (view === 'empty') return;

  const now = Date.now();
  const unmarked = unmarkedEvents().length;
  const open = homework.filter((h) => !h.done);
  const overdue = open.some((h) => h.due_at < now);
  setCount('sessions', unmarked, 'warn', `${unmarked} past session${unmarked === 1 ? '' : 's'} to mark`);
  setCount('homework', open.length, overdue ? 'bad' : '', overdue ? 'Some homework is overdue' : `${open.length} open`);
  const endOfDay = new Date(now).setHours(23, 59, 59, 999);
  const dueToday = open.filter((h) => h.due_at <= endOfDay).length;
  setCount('today', dueToday, overdue ? 'bad' : 'warn', `${dueToday} due today or overdue`);

  const { perCourse } = statsByCourse(courses, events, opts());
  const tracked = perCourse.filter((p) => countsAttendance(p.course) && p.stats.sessions)
    .sort((a, b) => a.course.name.localeCompare(b.course.name));
  const courseFocus = state.tab === 'sessions' && state.filter.course !== 'all' ? state.filter.course : null;
  // Courses that don't count attendance follow, without a count.
  const others = perCourse.filter((p) => p.course.tracked && !countsAttendance(p.course) && p.stats.sessions)
    .sort((a, b) => a.course.name.localeCompare(b.course.name));
  $sbCourses.innerHTML = [...tracked, ...others].map(({ course, stats }) => {
    const counts = countsAttendance(course);
    return `
    <button class="sb-row ${counts ? stats.level : ''}" data-course-open="${course.id}" title="${esc(course.name)}${counts ? `: ${esc(skipsLeft(stats))}` : ''}" ${String(course.id) === courseFocus ? 'aria-current="page"' : ''}>
      <svg class="ic" aria-hidden="true"><use href="#i-hash"/></svg><span class="label">${esc(course.name)}</span>${groupChip(courseGroups(course.id), { all: false })}
      <span class="count">${!counts ? '' : stats.remaining < -1e-9 ? '!' : fmtNum(stats.remainingDisplay)}</span>
    </button>`;
  }).join('') || '<p class="empty">No tracked courses.</p>';

  for (const b of document.querySelectorAll('.sidebar [data-tab]')) {
    const on = b.dataset.tab === state.tab && !(courseFocus && b.dataset.tab === 'sessions');
    if (on) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current');
  }
}

function setCount(tab, n, tone, title) {
  const el = document.querySelector(`[data-count="${tab}"]`);
  el.textContent = n || '';
  el.className = `count ${n ? tone : ''}`;
  el.title = n ? title : '';
}

// ---------------------------------------------------------------- motion
// Only transform and opacity animate. On first load, blocks and their first rows rise in a short stagger;
// the sign-in title keeps the knhdixh.com character reveal.

const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const GLYPHS = 'abcdefghijklmnopqrstuvwxyz0123456789';
const randGlyph = () => GLYPHS[(Math.random() * GLYPHS.length) | 0];
const ROWS = '.row, .hw-item, .card, .method, .family, .brk-window, .msg, .byok-option, .stat, table.courses tbody tr';

function maskedWord(word) {
  return [...word].map((c, i) => `<span class="mask"><span class="ch" style="--c:${i}" data-c="${esc(c)}">${esc(c)}</span></span>`).join('');
}

// Each character shows random glyphs from the moment it starts rising until it settles.
function scrambleIn(word, base, step) {
  for (const [i, ch] of [...word.querySelectorAll('.ch')].entries()) {
    const final = ch.dataset.c;
    if (final === ' ') continue;
    const start = base + i * step, settle = start + 360 + i * 22, t0 = performance.now();
    let lastSwap = 0;
    (function frame(now) {
      const t = now - t0;
      if (t >= settle) { ch.textContent = final; return; }
      if (t >= start && now - lastSwap > 45) { ch.textContent = randGlyph(); lastSwap = now; }
      requestAnimationFrame(frame);
    })(t0);
  }
}

let enterTimer;
function playEnter(full) {
  clearTimeout(enterTimer);
  $app.classList.remove('enter', 'full');
  // Stagger only the first few blocks and rows; anything further down is off screen and just appears.
  [...$app.children].forEach((el, i) => el.style.setProperty('--i', Math.min(i, 6)));
  $app.querySelectorAll('.rise').forEach((el) => el.classList.remove('rise'));
  [...$app.querySelectorAll(ROWS)].slice(0, 14).forEach((el, i) => { el.classList.add('rise'); el.style.setProperty('--r', i); });
  if (reduced) return;
  void $app.offsetWidth;
  $app.classList.add('enter');
  $app.classList.toggle('full', full);
  const word = $app.querySelector('.signin-card .word');
  if (word) scrambleIn(word, 120, 40);
  // Drop the class once it has played, so re-renders after a click don't replay the entrance.
  enterTimer = setTimeout(() => $app.classList.remove('enter', 'full'), full ? 1400 : 900);
}

function renderEmpty() {
  $app.innerHTML = `
    ${pageHead('Add your timetable')}
    <p class="lead-in">Add your Zeus link to keep it up to date automatically, or upload an <code>.ics</code> file.
      You can add more calendars later in Settings.</p>
    ${cal.addBox({ cancel: false })}
    ${state.data.account.google || state.data.account.passkeys.length > 1 ? '' : `
    <p class="guide-link"><a href="#settings" data-goto="settings">Add a second passkey</a> so you can always sign in.</p>`}`;
}

function groupBanner() {
  // Shown until the user has picked at least one group.
  const families = groupFamilies();
  if (!families.length || families.some(([, list]) => list.some((c) => c.tracked))) return '';
  return `<div class="banner">
    <span>Pick your groups for ${families.map(([f]) => esc(f)).join(', ')}.</span>
    <button class="btn btn-default btn-small" data-goto="courses">Choose groups</button>
  </div>`;
}

function renderOverview() {
  const { user, courses, events } = state.data;
  const o = opts();
  const { perCourse, overall } = statsByCourse(courses, events, o);
  const now = Date.now();
  const tracked = perCourse.filter((p) => countsAttendance(p.course) && p.stats.sessions)
    .sort((a, b) => ({ fail: 0, risk: 1, ok: 2 })[a.stats.level] - ({ fail: 0, risk: 1, ok: 2 })[b.stats.level]
      || a.stats.remaining - b.stats.remaining);

  const today = trackedEvents().filter((e) => dayKey(e.start_at) === dayKey(now));
  const next = trackedEvents().find((e) => e.start_at > now);
  const unmarked = unmarkedEvents();

  const [whole, decimals] = pct(overall.projectedRate).replace('%', '').split('.');
  $app.innerHTML = `
    ${groupBanner()}
    <section class="ov">
      <div class="ov-stats">
        <div class="stat"><span class="n">${whole}<small>${decimals ? `.${decimals}` : ''}%</small></span><span class="l">projected</span></div>
        <div class="stat"><span class="n">${fmtNum(overall.absent)}<small>/${fmtNum(overall.total)}</small></span><span class="l">absences</span></div>
        <div class="stat"><span class="n">${unmarked.length}</span><span class="l">to mark</span></div>
      </div>
      <div class="ov-foot">
        <p class="ov-line ${overall.level}">${overviewLine(overall, tracked[0], user.threshold)}</p>
        ${unmarked.length ? `<button class="btn btn-small" data-action="mark-all-present">Mark ${unmarked.length} as attended</button>` : ''}
      </div>
    </section>

    ${today.length || next ? `
    <section class="panel">
      <h3><span>${today.length ? 'Today' : 'Next'}</span><a href="#upcoming" data-goto="upcoming">upcoming →</a></h3>
      <div class="list">${(today.length ? today : [next]).map((e) => sessionRow(e, { showDate: !today.length })).join('')}</div>
    </section>` : ''}

    ${hw.homeworkPanel()}

    <section class="panel">
      <h3>Courses</h3>
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
      <header>
        <h4>${esc(course.name)}</h4>
        ${groupChip(courseGroups(course.id))}
        ${course.tag !== 'normal' ? `<span class="chip tag-${course.tag}">${TAGS[course.tag].label.toLowerCase()}</span>` : ''}
      </header>
      <div class="headline">${skipsLeft(s)}<span class="chev" aria-hidden="true">›</span></div>
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
  const counts = countsAttendance(course);
  const catchup = state.data.homework.find((h) => h.kind === 'catchup' && h.event_id === e.id);
  const catchupBtn = past ? `<button data-action="catchup" class="${catchup ? 'on' : ''}" title="${catchup ? 'Catch-up added: open it' : 'Missed it? Add a catch-up'}">↺</button>` : '';
  return `
    <div class="row ${past ? 'past' : 'future'} ${!counts ? 'no-att' : st ? 'st-' + st : past ? 'st-unmarked' : ''} ${course.tracked ? '' : 'untracked'}" data-event="${e.id}">
      <div class="when">
        ${showDate ? `<div class="date">${fmtShort(e.start_at)}</div>` : ''}
        <div>${fmtTime(e.start_at)}</div><div>${fmtTime(e.end_at)}</div>
      </div>
      <div class="what">
        <div class="title">${esc(e.summary)} ${groupChip(e.cals)}</div>
        <div class="loc muted small">${esc(e.location)}${e.note ? ` · <em>${esc(e.note)}</em>` : ''}</div>
        ${hw.homeworkForSession(e).map((h) => `<a class="hw-badge" href="#homework" data-hw-open="${h.id}" title="Homework due at this session"><svg class="ic sm" aria-hidden="true"><use href="#i-homework"/></svg>${esc(h.title)}</a>`).join('')}
      </div>
      ${counts ? `<div class="select"><select class="tag-select tag-${tag}" data-field="tag" title="Session tag (default: course tag)">
        <option value="" ${!e.tag ? 'selected' : ''}>${TAGS[course.tag].label}</option>
        ${Object.entries(TAGS).map(([k, t]) => `<option value="${k}" ${e.tag === k ? 'selected' : ''}>${t.label}</option>`).join('')}
      </select></div>
      <div class="status" role="group" aria-label="Attendance">
        <button data-status="present" class="${st === 'present' ? 'on' : ''}" title="${past ? 'Attended' : 'Will attend'}">✓</button>
        <button data-status="absent" class="${st === 'absent' ? 'on' : ''} ${mustAttend && !past ? 'danger' : ''}" title="${past ? 'Absent' : 'Plan to skip'}">×</button>
        <button data-status="excused" class="${st === 'excused' ? 'on' : ''}" title="Excused (doesn't count)">E</button>
        ${catchupBtn}
        <button data-action="note" title="Add a note">+</button>
      </div>` : `<span></span>
      <div class="status" role="group" aria-label="Session">
        ${catchupBtn}
        <button data-action="note" title="Add a note">+</button>
      </div>`}
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
      <div class="select"><select id="f-course">
        <option value="all">All ${f.showHidden ? '' : 'tracked '}courses</option>
        ${shownCourses.map((c) => `<option value="${c.id}" ${String(c.id) === f.course ? 'selected' : ''}>${esc(c.name)}</option>`).join('')}
      </select></div>
      <div class="seg" id="f-when" role="group" aria-label="Show">
        ${[['upcoming', 'Upcoming'], ['unmarked', 'To mark'], ['past', 'Past'], ['skips', 'Absences'], ['mustattend', 'Exams'], ['all', 'All']]
          .map(([k, l]) => `<label class="segment"><input type="radio" name="f-when" value="${k}" ${f.when === k ? 'checked' : ''}><span>${l}</span></label>`).join('')}
      </div>
      <input class="field" id="f-q" type="search" placeholder="Search" value="${esc(f.q)}">
      <label class="checkbox check"><input type="checkbox" id="f-hidden" ${f.showHidden ? 'checked' : ''}><span></span>Show untracked</label>
    </div>
    ${courseSummary}
    ${f.when === 'unmarked' && list.length ? `<p><button class="btn btn-default btn-small" data-action="mark-shown-present">Mark these ${list.length} as attended</button></p>` : ''}
    <div class="days">
      ${days.map((d) => `
        <section class="day ${dayKey(now) === d.key ? 'today' : ''}">
          <h4><span>${esc(new Date(d.ms).toLocaleDateString([], { day: 'numeric', month: 'short', year: new Date(d.ms).getFullYear() === new Date(now).getFullYear() ? undefined : 'numeric' }))}</span>
            <span class="dow">· ${esc(new Date(d.ms).toLocaleDateString([], { weekday: 'long' }))}</span>${dayKey(now) === d.key ? '<span class="tag-today">· Today</span>' : ''}</h4>
          <div class="list">${d.items.map((e) => sessionRow(e)).join('')}</div>
        </section>`).join('') || '<p class="muted empty">No sessions match.</p>'}
    </div>`;
  state.shownIds = list.map((e) => e.id);
}

// ---------------------------------------------------------------- upcoming
// A week at a time, like a planner: a strip of seven days on top, then each day's sessions and the homework
// due that day. The current week starts at today; earlier weeks aren't offered (Sessions › Past has those).

function weekStart(offset) {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - (d.getDay() + 6) % 7 + 7 * offset); // weeks start on Monday
  return d;
}

function renderUpcoming() {
  const offset = state.upWeek ?? 0;
  const now = Date.now();
  const today = dayKey(now);
  const start = weekStart(offset);
  const midnight = new Date(now); midnight.setHours(0, 0, 0, 0);
  const tomorrow = new Date(midnight); tomorrow.setDate(midnight.getDate() + 1);
  const days = Array.from({ length: 8 }, (_, i) => {
    const d = new Date(start); d.setDate(start.getDate() + i);
    return { ms: d.getTime(), key: dayKey(d.getTime()), past: d < midnight };
  });
  const end = days.pop().ms; // the next Monday
  const byDay = new Map(days.map((d) => [d.key, { sessions: [], homework: [] }]));
  for (const e of trackedEvents()) if (e.start_at >= start.getTime() && e.start_at < end) byDay.get(dayKey(e.start_at))?.sessions.push(e);
  for (const h of state.data.homework) if (!h.done && h.due_at >= start.getTime() && h.due_at < end) byDay.get(dayKey(h.due_at))?.homework.push(h);
  state.weekCount = days.filter((d) => !d.past).reduce((n, d) => n + byDay.get(d.key).sessions.length, 0);

  const month = (ms, o) => new Date(ms).toLocaleDateString([], o);
  const range = month(days[0].ms, { month: 'long' }) === month(days[6].ms, { month: 'long' })
    ? month(days[0].ms, { month: 'long', year: 'numeric' })
    : `${month(days[0].ms, { month: 'short' })} – ${month(days[6].ms, { month: 'short', year: 'numeric' })}`;
  const relDay = (d) => d.key === today ? 'Today' : d.key === dayKey(tomorrow.getTime()) ? 'Tomorrow' : '';

  $app.innerHTML = `
    ${groupBanner()}
    <div class="up-head">
      <h3 class="up-range">${esc(range)}</h3>
      <div class="up-ctrl">
        <button class="icon-btn up-prev" data-up-week="${offset - 1}" title="Previous week" aria-label="Previous week" ${offset ? '' : 'disabled'}><svg class="ic" aria-hidden="true"><use href="#i-chevron"/></svg></button>
        <button class="btn btn-small" data-up-week="0" ${offset ? '' : 'disabled'}>Today</button>
        <button class="icon-btn up-next" data-up-week="${offset + 1}" title="Next week" aria-label="Next week"><svg class="ic" aria-hidden="true"><use href="#i-chevron"/></svg></button>
      </div>
    </div>
    <div class="up-strip">
      ${days.map((d) => {
        const n = byDay.get(d.key).sessions.length;
        const dt = new Date(d.ms);
        return `<button class="up-day${d.key === today ? ' today' : ''}${d.past ? ' past' : ''}" data-up-day="${d.key}" ${d.past ? 'disabled' : ''}
          aria-label="${esc(dt.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' }))}: ${n} session${n === 1 ? '' : 's'}">
          <span class="dow">${esc(dt.toLocaleDateString([], { weekday: 'short' }))}</span>
          <span class="d">${dt.getDate()}</span>
          <span class="dots" aria-hidden="true">${'<i></i>'.repeat(Math.min(n, 4))}</span>
        </button>`;
      }).join('')}
    </div>
    <div class="days up-days">
      ${days.filter((d) => !d.past).map((d) => {
        const { sessions, homework } = byDay.get(d.key);
        const dt = new Date(d.ms);
        const rel = relDay(d);
        return `
        <section class="day${d.key === today ? ' today' : ''}" id="up-${d.key}">
          <h4><span>${esc(dt.toLocaleDateString([], { day: 'numeric', month: 'short' }))}</span>
            <span class="dow">· ${esc(dt.toLocaleDateString([], { weekday: 'long' }))}</span>${rel ? `<span class="tag-today">· ${rel}</span>` : ''}</h4>
          ${sessions.length || homework.length ? `
          <div class="list">${sessions.map((e) => sessionRow(e)).join('')}${homework.map((h) => hw.hwItem(h, now)).join('')}</div>`
          : '<p class="up-free">Nothing scheduled</p>'}
        </section>`;
      }).join('')}
    </div>
    <p class="up-more"><button class="btn btn-default btn-small" data-up-week="${offset + 1}">Next week<svg class="ic sm" aria-hidden="true"><use href="#i-arrow"/></svg></button></p>`;
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
            <div class="select"><select data-family="${esc(family)}">
              <option value="">Not in this course</option>
              ${list.map((c) => `<option value="${c.id}" ${c.tracked ? 'selected' : ''}>${esc(c.group)}</option>`).join('')}
              ${list.filter((c) => c.tracked).length > 1 ? '<option value="multi" selected>Several groups</option>' : ''}
            </select></div>
          </label>`).join('')}
      </div>
    </section>` : ''}
    <section class="panel">
      <h3>All courses</h3>
      <p class="muted small">Untracked courses don't count toward attendance. The tag applies to every session of the course unless a session overrides it.
        <br><b>Exam</b>/<b>Mandatory</b>: counted, and flagged if you skip them. <b>Optional</b>: never counted.</p>
      <table class="courses">
        <thead><tr><th>Track</th><th>Course</th><th>Default tag</th><th class="num">Sessions</th><th class="num">Hours</th><th class="num">Projected</th><th>Recordings</th></tr></thead>
        <tbody>
          ${perCourse.map(({ course: c, stats: s }) => `
            <tr class="${c.tracked ? '' : 'off'}" data-course="${c.id}">
              <td><label class="checkbox"><input type="checkbox" data-field="tracked" ${c.tracked ? 'checked' : ''} aria-label="Track ${esc(c.name)}"><span></span></label></td>
              <td><a href="#" data-course-open="${c.id}">${esc(c.name)}</a> ${groupChip(courseGroups(c.id))}${groupedIds.has(c.id) ? ' <span class="chip">group split</span>' : ''}${c.attendance === 0 ? ' <span class="chip">no attendance</span>' : ''}</td>
              <td><div class="select"><select data-field="tag" class="tag-${c.tag}">${Object.entries(TAGS).map(([k, t]) => `<option value="${k}" ${c.tag === k ? 'selected' : ''}>${t.label}</option>`).join('')}</select></div></td>
              <td class="num">${count(c.id)}</td>
              <td class="num">${hours(c.id)}</td>
              <td class="num ${countsAttendance(c) ? s.level : ''}">${countsAttendance(c) ? pct(s.projectedRate) : '—'}</td>
              <td>${c.recording_url ? `<a href="${esc(c.recording_url)}" target="_blank" rel="noopener">open ↗</a> ` : ''}<button class="btn btn-borderless btn-small" data-action="recording" title="Where this course's lecture recordings are; catch-ups link to it">${c.recording_url ? 'edit' : 'add link'}</button></td>
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
          <span class="input-suffix"><input class="field" type="number" name="threshold" min="1" max="100" step="1" value="${Math.round(user.threshold * 100)}"> %</span>
        </label>
        <label>Count attendance by
          <div class="select"><select name="unit">
            <option value="sessions" ${user.unit === 'sessions' ? 'selected' : ''}>Number of sessions</option>
            <option value="hours" ${user.unit === 'hours' ? 'selected' : ''}>Hours (longer sessions weigh more)</option>
          </select></div>
        </label>
        <button class="btn btn-primary" type="submit">Save</button>
      </form>
      <p class="muted small">Unmarked future sessions are assumed attended. Mark a future session × to plan a skip and see its effect.
        Excused (E) sessions are removed from the total, as are sessions tagged Optional.</p>
    </section>
    ${cal.settingsPanel()}
    ${signInMethodsPanel()}
    ${tokens.settingsPanel()}
    ${byok.settingsPanel()}
    ${hw.integrationsPanel()}
    <section class="panel narrow danger-zone">
      <h3>Delete data</h3>
      <p>Remove all imported sessions, courses and attendance marks from your account. Homework is kept.</p>
      <button class="btn btn-destructive" data-action="wipe">Delete all my data</button>
      <h3 class="danger-title">Delete my account</h3>
      <p>Permanently delete your account and everything in it: calendar, attendance, homework, breaks, passkeys,
        AI key and Google Calendar connection (access is revoked; the "Homework (Attendance80)" calendar stays in your
        Google account for you to delete).</p>
      <button class="btn btn-destructive" data-action="delete-account">Delete my account</button>
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
            <button class="btn btn-borderless btn-small" data-remove-passkey="${esc(p.id)}">Remove</button>
          </div>`).join('')}
      </div>
      ${passkeysSupported() ? `<button class="btn btn-default" data-action="pk-add">${keyIcon()} Add a passkey${account.passkeys.length ? ' on another device' : ''}</button>` : ''}
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

// A catch-up for a missed session: due before the course meets again, with the course's recording link.
async function addCatchup(ev, { quiet = false } = {}) {
  try {
    const item = await api('/api/catchups', { method: 'POST', body: { event_id: ev.id, tz: Intl.DateTimeFormat().resolvedOptions().timeZone } });
    if (!state.data.homework.some((h) => h.id === item.id)) state.data.homework.push(item);
    state.data.homework.sort((a, b) => a.due_at - b.due_at);
    render();
    toast(quiet ? `Added a catch-up for ${courseById()[ev.course_id]?.name}.` : 'Catch-up added to your list.');
  } catch (err) { toast(err.message, true); }
}

async function removeCatchup(item) {
  try {
    await api(`/api/homework/${item.id}`, { method: 'DELETE' });
    state.data.homework = state.data.homework.filter((h) => h.id !== item.id);
    render();
  } catch (err) { toast(err.message, true); }
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

// ---------------------------------------------------------------- navigation

// [tab, label, icon, key after G]
const NAV = [
  ['today', 'Today', 'i-today', 'T'], ['overview', 'Attendance', 'i-home', 'O'], ['upcoming', 'Upcoming', 'i-calendar', 'U'], ['sessions', 'Sessions', 'i-sessions', 'S'], ['homework', 'Homework', 'i-homework', 'H'],
  ['focus', 'Focus', 'i-focus', 'F'], ['breaks', 'Breaks', 'i-breaks', 'B'], ['assistant', 'Assistant', 'i-assistant', 'A'], ['courses', 'Courses', 'i-courses', 'C'],
  ['settings', 'Settings', 'i-settings', ','],
];
const SIDEBAR_KEY = 'a80-sidebar';
const isPhone = () => matchMedia('(max-width: 760px)').matches;

function go(tab) {
  closeDrawer();
  if (tab === 'sessions' && state.tab !== 'sessions') state.filter.course = 'all';
  if (tab === 'upcoming' && state.tab !== 'upcoming') state.upWeek = 0;
  state.tab = tab;
  history.replaceState(null, '', '#' + tab);
  render();
}

function openCourse(id) {
  closeDrawer();
  Object.assign(state.filter, { course: String(id), when: 'all', q: '' });
  state.tab = 'sessions';
  history.replaceState(null, '', '#sessions');
  render();
  document.querySelector('.day.today, .row.future')?.scrollIntoView({ block: 'center' });
}

// Jumps to one session in its course's list and flashes it.
function showSession(id) {
  const ev = findEvent(id);
  openCourse(ev.course_id);
  const el = $app.querySelector(`[data-event="${id}"]`);
  el?.scrollIntoView({ block: 'center' });
  el?.classList.add('flash');
}

function toggleSidebar() {
  if (isPhone()) return document.body.classList.toggle('drawer-open');
  const closed = document.body.classList.toggle('sidebar-closed');
  try { localStorage.setItem(SIDEBAR_KEY, closed ? 'closed' : 'open'); } catch { /* storage unavailable */ }
}
const closeDrawer = () => document.body.classList.remove('drawer-open');

function toggleMenu(show = $accountMenu.hidden) {
  $accountMenu.hidden = !show;
  $accountBtn.setAttribute('aria-expanded', String(show));
  if (show) $accountMenu.querySelector('button').focus();
}

// ---------------------------------------------------------------- command palette

palette.setSource({ items: paletteItems, fallback: paletteFallback, help: shortcutsHelp });

function paletteItems() {
  if (!state.data) return [];
  const { courses, events, homework, user } = state.data;
  const hasData = events.length > 0;
  const now = Date.now();
  const items = NAV.filter(([tab]) => hasData || tab === 'settings' || tab === 'focus').map(([tab, label, icon, key]) => ({
    id: `nav:${tab}`, section: 'Navigation', label: `Go to ${label}`, icon, keys: ['G', 'then', key], home: true, run: () => go(tab),
  }));

  const act = (id, label, icon, run, extra = {}) => items.push({ id: `act:${id}`, section: 'Actions', label, icon, run, home: true, ...extra });
  act('add-homework', 'Add homework', 'i-plus', hw.openForm, { tone: 'accent', keys: ['Q'] });
  if (state.data.assistant && hasData) act('ask', 'Ask the assistant', 'i-assistant', () => go('assistant'), { keywords: 'ai chat question' });
  const unmarked = unmarkedEvents();
  if (unmarked.length) act('mark-all', `Mark ${unmarked.length} past session${unmarked.length === 1 ? '' : 's'} as attended`, 'i-check', () => bulkStatus(unmarked.map((x) => x.id), 'present'), { keywords: 'unmarked present attendance' });
  if (hasData) act('break', 'Plan a break', 'i-breaks', () => { go('breaks'); document.querySelector('#break-form [name=from]')?.focus(); }, { keywords: 'holiday trip time off skip' });
  items.push(...cal.paletteItems(go), ...fx.paletteItems());
  if (state.data.integrations?.feedUrl) act('copy-feed', 'Copy timetable calendar link', 'i-link', copyTimetableFeed, { home: false, keywords: 'feed ics subscribe google apple outlook' });
  act('sidebar', 'Toggle sidebar', 'i-panel', toggleSidebar, { keys: ['M'], home: false });
  act('shortcuts', 'Keyboard shortcuts', 'i-keyboard', () => palette.open('?'), { keys: ['?'], home: false, keywords: 'keys help' });
  act('logout', 'Sign out', 'i-logout', () => document.querySelector('#account-menu [data-action="logout"]').click(), { home: false, keywords: 'log out' });

  if (hasData) {
    const { perCourse } = statsByCourse(courses, events, opts());
    for (const { course, stats } of perCourse) {
      items.push({
        id: `course:${course.id}`, section: 'Courses', label: course.name, icon: 'i-hash', tone: countsAttendance(course) ? stats.level : '',
        sub: [!course.tracked ? 'not tracked' : countsAttendance(course) ? skipsLeft(stats) : 'attendance not counted', groupLabel(courseGroups(course.id))].filter(Boolean).join(' · '),
        keywords: 'course', run: () => openCourse(course.id),
      });
    }
    const byId = courseById();
    for (const h of homework) {
      items.push({
        id: `hw:${h.id}`, section: 'Homework', label: h.title, icon: 'i-homework', tone: !h.done && h.due_at < now ? 'fail' : '',
        sub: `${h.done ? 'done · ' : ''}${hw.dueLabel(h)}${byId[h.course_id] ? ` · ${byId[h.course_id].name}` : ''}`, run: () => hw.editHomework(h.id),
      });
    }
    // The next session of each tracked course (or its latest one, when it has no more); the course opens the rest.
    const next = new Map();
    for (const ev of trackedEvents()) if (!next.has(ev.summary) || next.get(ev.summary).end_at <= now) next.set(ev.summary, ev);
    for (const ev of next.values()) {
      items.push({
        id: `ev:${ev.id}`, section: 'Sessions', label: ev.summary, icon: 'i-sessions',
        sub: [`${fmtShort(ev.start_at)} ${fmtTime(ev.start_at)}`, ev.location, groupLabel(ev.cals)].filter(Boolean).join(' · '),
        run: () => showSession(ev.id),
      });
    }
  }
  return items;
}

// Always offered below the matches: send the text to the assistant, or search the session list for it.
function paletteFallback(q) {
  if (!state.data?.events.length) return [];
  const out = [];
  if (state.data.assistant) {
    out.push({ id: 'ask:q', recentId: 'act:ask', section: 'Assistant', label: `Ask: “${q}”`, icon: 'i-assistant', tone: 'accent',
      run: () => { go('assistant'); ai.ask(q); } });
  }
  out.push({ id: 'find:q', recentId: '', section: 'Assistant', label: `Search all sessions for “${q}”`, icon: 'i-search',
    run: () => { Object.assign(state.filter, { course: 'all', when: 'all', q }); go('sessions'); } });
  return out;
}

function shortcutsHelp() {
  const rows = [
    [['/'], 'Search and commands'], [['⌘', 'K'], 'Search and commands, even while typing'], [['Q'], 'Add homework'],
    [['M'], 'Show or hide the sidebar'], [['?'], 'This list'], ...fx.SHORTCUTS,
    ...NAV.map(([, label, , key]) => [['G', 'then', key], `Go to ${label}`]),
    [['Esc'], 'Close, or cancel the homework form'], [['⌘', '↵'], 'Save homework from the notes'],
  ];
  return `<div class="pal-section">Keyboard shortcuts</div>
    <div class="keys" style="padding:0 10px 6px">${rows.map(([keys, what]) => `<span>${esc(what)}</span>
      <span class="k">${keys.map((k) => (k === 'then' ? 'then' : `<kbd>${esc(k)}</kbd>`)).join('')}</span>`).join('')}</div>`;
}

async function copyTimetableFeed() {
  const url = hw.timetableFeedUrl();
  try { await navigator.clipboard.writeText(url); toast('Timetable link copied.'); }
  catch { go('settings'); toast('Copy the link from Settings.', true); }
}

// ---------------------------------------------------------------- events

document.addEventListener('click', async (e) => {
  const t = e.target;
  if (!t.closest('#account-menu, #account-btn')) toggleMenu(false);
  if (t.closest('#account-btn')) return toggleMenu();
  if (t.closest('#account-menu button')) toggleMenu(false);
  const tab = t.closest('[data-tab]');
  if (tab) return go(tab.dataset.tab);
  const scope = t.closest('[data-scope]');
  if (scope) return toggleScope(scope.dataset.scope);
  const week = t.closest('[data-up-week]');
  if (week) { state.upWeek = Math.max(0, Number(week.dataset.upWeek)); render(); $scroller.scrollTop = 0; return; }
  const upDay = t.closest('[data-up-day]');
  if (upDay) return document.getElementById(`up-${upDay.dataset.upDay}`)?.scrollIntoView({ block: 'start', behavior: reduced ? 'auto' : 'smooth' });

  if (byok.onClick(e) || fx.onClick(e) || await hw.onClick(e) || ai.onClick(e) || brk.onClick(e) || cal.onClick(e) || tokens.onClick(e)) return;
  const action = t.closest('[data-action]')?.dataset.action;
  const row = t.closest('[data-event]');

  if (action === 'palette') { closeDrawer(); return palette.open(); }
  if (action === 'shortcuts') return palette.open('?');
  if (action === 'toggle-sidebar') return toggleSidebar();
  if (action === 'add-homework') { closeDrawer(); return hw.openForm(); }
  if (action === 'upload') return cal.pickFile();
  if (action === 'add-calendar') { closeDrawer(); return cal.openAdd(); }
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
  if (action === 'mark-all-present') return bulkStatus(unmarkedEvents().map((x) => x.id), 'present');
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
    if (!confirm(`Delete all your sessions, attendance marks and breaks?${state.data.calendars.length ? ` Your ${state.data.calendars.length === 1 ? 'calendar is' : `${state.data.calendars.length} calendars are`} removed too.` : ''} This cannot be undone.`)) return;
    await api('/api/data', { method: 'DELETE' });
    await reload(); state.tab = 'today'; render();
    return toast('All data deleted.');
  }
  if (action === 'note' && row) {
    const ev = findEvent(Number(row.dataset.event));
    const note = prompt('Note for this session (e.g. reason for absence):', ev.note);
    if (note !== null) setEventField(ev.id, { note: note.slice(0, 2000) });
    return;
  }

  if (action === 'recording') {
    const course = courseById()[Number(t.closest('[data-course]').dataset.course)];
    const url = prompt(`Link to the lecture recordings of ${course.name} (Panopto, Moodle, YouTube…). New catch-ups link to it. Leave empty to remove.`, course.recording_url || '');
    if (url === null) return;
    try {
      await api(`/api/courses/${course.id}`, { method: 'PATCH', body: { recording_url: url.trim() } });
      course.recording_url = url.trim() || null;
      render();
    } catch (err) { toast(err.message, true); }
    return;
  }
  if (action === 'catchup' && row) {
    const ev = findEvent(Number(row.dataset.event));
    const existing = state.data.homework.find((h) => h.kind === 'catchup' && h.event_id === ev.id);
    return existing ? hw.editHomework(existing.id) : addCatchup(ev);
  }

  const statusBtn = t.closest('button[data-status]');
  if (statusBtn && row) {
    const ev = findEvent(Number(row.dataset.event));
    const status = ev.status === statusBtn.dataset.status ? null : statusBtn.dataset.status;
    await setEventField(ev.id, { status });
    // Missing a class puts a catch-up on your list; marking it attended again takes an untouched one off.
    if (ev.start_at <= Date.now()) {
      const existing = state.data.homework.find((h) => h.kind === 'catchup' && h.event_id === ev.id);
      if (status === 'absent' && !existing) await addCatchup(ev, { quiet: true });
      if (status !== 'absent' && existing && !existing.done && existing.created_at === existing.updated_at) await removeCatchup(existing);
    }
    return;
  }

  const goto = t.closest('[data-goto]');
  if (goto) { e.preventDefault(); return go(goto.dataset.goto); }

  const hwOpen = t.closest('[data-hw-open]');
  if (hwOpen) { e.preventDefault(); return hw.editHomework(Number(hwOpen.dataset.hwOpen)); }

  const open = t.closest('[data-course-open]');
  if (open) { e.preventDefault(); return openCourse(open.dataset.courseOpen); }

  const when = t.closest('[data-when]');
  if (when) { state.filter.when = when.dataset.when; return render(); }
});

// Keyboard: "/" and Cmd/Ctrl+K open the palette, Q adds homework, F starts or pauses the focus timer, M toggles
// the sidebar, "?" lists the shortcuts, and G then a letter goes to a page (G then H = Homework). On the Focus page,
// Space, S and X control the timer. Single keys are ignored while typing.
const isTyping = (el) => el?.closest?.('input, textarea, select, [contenteditable="true"]');
let gAt = 0;

document.addEventListener('keydown', (e) => {
  const signedIn = state.data && !document.body.classList.contains('signed-out');
  if (signedIn && (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    return palette.isOpen() ? palette.close() : palette.open();
  }
  if (palette.isOpen()) return;
  if (hw.onKeydown(e) || cal.onKeydown(e) || ai.onKeydown(e) || fx.onKeydown(e)) return;
  if (e.key === 'Escape') { toggleMenu(false); closeDrawer(); return; }
  if (e.key === 'Enter' && e.target.matches('[data-course-open]')) return e.target.click();
  if (!signedIn || e.metaKey || e.ctrlKey || e.altKey || isTyping(e.target)) return;

  const k = e.key.toLowerCase();
  if (Date.now() - gAt < 1500) {
    gAt = 0;
    const page = NAV.find((n) => n[3].toLowerCase() === k);
    if (page) { e.preventDefault(); go(page[0]); }
    return;
  }
  if (state.tab === 'focus' && fx.onPageKey(e)) return;
  if (k === '/') { e.preventDefault(); palette.open(); }
  else if (e.key === '?') { e.preventDefault(); palette.open('?'); }
  else if (k === 'g') gAt = Date.now();
  else if (k === 'q') { e.preventDefault(); hw.openForm(); }
  else if (k === 'f') { e.preventDefault(); if (!e.repeat) fx.toggle(); }
  else if (k === 'm') toggleSidebar();
});

window.addEventListener('hashchange', () => {
  const tab = location.hash.slice(1);
  if (state.data && tab in TITLES && tab !== state.tab) go(tab);
});

$scroller.addEventListener('scroll', () => $scroller.classList.toggle('scrolled', $scroller.scrollTop > 56), { passive: true });

document.addEventListener('change', (e) => {
  if (byok.onChange(e) || fx.onChange(e) || hw.onChange(e) || brk.onChange(e)) return;
  const t = e.target;
  if (t.id === 'f-course') { state.filter.course = t.value; return render(); }
  if (t.id === 'f-hidden') { state.filter.showHidden = t.checked; return render(); }
  if (t.name === 'f-when') { state.filter.when = t.value; return render(); }

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
  if (ai.onInput(e) || hw.onInput(e) || brk.onInput(e)) return;
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
  if (byok.onSubmit(e) || hw.onSubmit(e) || ai.onSubmit(e) || brk.onSubmit(e) || cal.onSubmit(e) || tokens.onSubmit(e) || fx.onSubmit(e)) return;
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

// Drag & drop anywhere on the page.
document.addEventListener('dragover', (e) => { e.preventDefault(); document.body.classList.add('dragging'); });
document.addEventListener('dragleave', (e) => { if (!e.relatedTarget) document.body.classList.remove('dragging'); });
document.addEventListener('drop', (e) => {
  e.preventDefault();
  document.body.classList.remove('dragging');
  if (state.data) cal.uploadFile(e.dataTransfer.files[0]);
});

boot().catch((err) => {
  $app.innerHTML = `<p class="error">Failed to load: ${esc(err.message)}</p>`;
});

export { state, esc, api, toast, render, reload, courseById, fmtTime, groupChip, groupLabel, courseGroups, sessionRow };
