// Breaks tab. A month calendar is the date-range picker: each day shows what missing it costs, you click your first
// and last day off, and the side panel shows what that break does to every course and saves it. Suggestions of a
// chosen length fill the same range. Saving a break marks its sessions as planned absences.
import { state, esc, api, toast, render, reload } from './app.js';
import { breakModel, simulateBreak, findBreakWindows, startDayGrid, addDays, weekdayOf } from './breaks.js';

const $app = document.getElementById('app');
const tz = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
const pct = (x) => `${Math.round(x * 1000) / 10}%`;
const fmtN = (n) => (Number.isInteger(n) ? n : Math.round(n * 10) / 10);
const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;
const dayLabel = (d, opts = { weekday: 'short', day: 'numeric', month: 'short' }) =>
  new Date(d + 'T12:00:00Z').toLocaleDateString([], { ...opts, timeZone: 'UTC' });
// "Mon, Oct 19 – Wed, Oct 21", or one date for a one-day range.
const rangeLabel = (from, to) => (from === to ? dayLabel(from) : `${dayLabel(from)} – ${dayLabel(to)}`);
const monthOf = (date) => date.slice(0, 7);
const addMonths = (month, n) => {
  const d = new Date(month + '-01T12:00:00Z');
  d.setUTCMonth(d.getUTCMonth() + n);
  return d.toISOString().slice(0, 7);
};
const LENGTHS = [[1, '1 day'], [3, 'Long weekend'], [7, '1 week'], [9, '9 days'], [14, '2 weeks']];
// What a whole break does, and what missing a single day does (the calendar).
const VERDICT = { free: 'No classes to miss', ok: 'Safe', risk: 'Possible, but leaves no spare skips', fail: 'Not possible' };
const DAY_VERDICT = { free: 'no classes', ok: 'safe to miss', risk: 'tight: uses your last spare skip', fail: "can't miss" };

function model() {
  const { courses, events, user } = state.data;
  return breakModel({ courses, events, threshold: user.threshold, unit: user.unit, tz: tz() });
}

// from/to: the picked break. phase: which end the next calendar click sets. keepEnd: the user chose to move only
// the first day, so a later end stays put.
function ui() {
  state.breaks ??= { from: '', to: '', label: '', len: 7, phase: 'start', keepEnd: false, month: '' };
  return state.breaks;
}

function skipsLeft(s, unit) {
  if (s.remaining < -1e-9) return `over by ${fmtN(Math.ceil(-s.remaining * 2) / 2)}`;
  return `${fmtN(s.remainingDisplay)}${unit === 'hours' ? ' h' : ''} left`;
}

// ---------------------------------------------------------------- the month calendar

function monthView(m, s, days, saved) {
  const first = s.month + '-01';
  const lastOfMonth = addDays(addMonths(s.month, 1) + '-01', -1);
  const lead = (weekdayOf(first) + 6) % 7; // blank cells before the 1st (weeks start on Monday)
  const minMonth = monthOf(m.today), maxMonth = monthOf(m.lastDay);
  const savedOn = (d) => saved.find((b) => d >= b.start_date && d <= b.end_date);

  const cells = [];
  for (let i = 0; i < lead; i++) cells.push('<div class="cal-blank"></div>');
  for (let d = first; d <= lastOfMonth; d = addDays(d, 1)) {
    const c = days.get(d);
    const inBreak = s.from && d >= s.from && d <= s.to;
    const cls = [
      inBreak && 'in', d === s.from && 'first', d === s.to && 'last', d === m.today && 'today',
      c?.holiday && 'holiday', savedOn(d) && 'saved', weekdayOf(d) % 6 === 0 && 'weekend',
    ].filter(Boolean).join(' ');
    const num = `<span class="cal-num">${Number(d.slice(8))}</span>`;
    if (!c) {
      const why = d < m.today ? 'Past' : 'After the end of your calendar';
      cells.push(`<div class="cal-day off ${cls}" title="${why}">${num}</div>`);
      continue;
    }
    const b = savedOn(d);
    const title = [`${dayLabel(d)}: ${DAY_VERDICT[c.level]}`, c.sessions ? plural(c.sessions, 'session') : '', ...c.blockers,
      c.holiday ? 'School holiday' : '', b ? `Saved break: ${b.label || 'Break'}` : ''].filter(Boolean).join('\n');
    cells.push(`<button class="cal-day ${c.level} ${cls}" data-day="${d}" title="${esc(title)}" aria-label="${esc(title)}" aria-pressed="${inBreak ? 'true' : 'false'}">
      ${num}<span class="cal-dot" aria-hidden="true"></span></button>`);
  }

  return `
    <section class="cal-card">
      <header class="cal-head">
        <h3>${esc(new Date(first + 'T12:00:00Z').toLocaleDateString([], { month: 'long', year: 'numeric', timeZone: 'UTC' }))}</h3>
        <div class="cal-nav">
          <button class="icon-btn" data-month="-1" ${s.month <= minMonth ? 'disabled' : ''} aria-label="Previous month"><svg class="ic" aria-hidden="true"><use href="#i-chevron" transform="rotate(90 12 12)"/></svg></button>
          <button class="btn btn-small btn-borderless" data-month="0" ${s.month === minMonth ? 'disabled' : ''}>Today</button>
          <button class="icon-btn" data-month="1" ${s.month >= maxMonth ? 'disabled' : ''} aria-label="Next month"><svg class="ic" aria-hidden="true"><use href="#i-chevron" transform="rotate(-90 12 12)"/></svg></button>
        </div>
      </header>
      <div class="cal" role="group" aria-label="Pick your first and last day off">
        ${['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => `<div class="cal-dow">${d}</div>`).join('')}
        ${cells.join('')}
      </div>
      <footer class="brk-legend">
        <span><i class="ok"></i>Safe to miss</span><span><i class="risk"></i>Tight</span><span><i class="fail"></i>Can't miss</span>
        <span><i class="free"></i>No classes</span>${saved.length ? '<span><i class="saved"></i>Saved break</span>' : ''}
      </footer>
      ${m.noSkip.length ? `<p class="hint cal-note">No spare absences left in ${m.noSkip.map((c) => `<b>${esc(c.name)}</b>`).join(', ')}, so any day with one of their classes can't be missed.</p>` : ''}
    </section>`;
}

// ---------------------------------------------------------------- the picked break

function pill(which, s) {
  const value = which === 'start' ? s.from : s.from && (s.phase === 'end' && s.to === s.from ? '' : s.to);
  return `<button class="brk-pill${s.phase === which ? ' active' : ''}" data-pick="${which}">
    <span class="k">${which === 'start' ? 'First day off' : 'Last day off'}</span>
    <span class="v${value ? '' : ' empty'}">${value ? esc(dayLabel(value)) : 'Pick a day'}</span></button>`;
}

function panelView(sim, s, unit) {
  const head = `
    <div class="brk-pills">${pill('start', s)}<span class="brk-arrow" aria-hidden="true">→</span>${pill('end', s)}</div>
    <p class="brk-step">${!s.from ? 'Click your first day off on the calendar.'
      : s.phase === 'end' ? 'Now click your last day off, or keep it to one day.' : 'Click a day to start a new break.'}</p>`;
  if (!sim) {
    return `<div class="brk-card">${head}
      <p class="hint brk-ask">Or ask the <a href="#assistant" data-goto="assistant">Assistant</a>, e.g. “when could I go home for a week in November?”</p></div>`;
  }
  const level = sim.sessions.length || sim.conflicts.length ? sim.level : 'free';
  const facts = [plural(sim.days, 'day'), sim.sessions.length ? `skips ${plural(sim.sessions.length, 'session')}` : 'no classes missed'];
  if (sim.holidayDays) facts.push(plural(sim.holidayDays, 'holiday day'));
  return `
    <div class="brk-card">
      ${head}
      <div class="brk-result">
        <div class="brk-verdict-row">
          <p class="brk-verdict ${level}">${VERDICT[level]}</p>
          <button class="btn btn-small btn-borderless" data-action="clear-break">Clear</button>
        </div>
        <div class="brk-facts">${facts.join(' · ')}</div>
        ${sim.conflicts.length ? `<div class="brk-conflicts"><b>Can't miss:</b>
          <ul>${sim.conflicts.map((e) => `<li>${esc(e.summary)}, ${esc(new Date(e.start_at).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }))}</li>`).join('')}</ul></div>` : ''}
        ${sim.courses.length ? `<div class="brk-courses">${sim.courses.map((c) => `
          <div class="brk-course ${c.level}">
            <span class="name">${esc(c.course.name)}</span>
            <span class="skip">−${c.skipped}</span>
            <span class="left">${esc(skipsLeft(c.before, unit))} <span aria-hidden="true">→</span> <b>${esc(skipsLeft(c.after, unit))}</b></span>
            <span class="after">${pct(c.after.projectedRate)}</span>
          </div>`).join('')}</div>` : ''}
        ${sim.sessions.length ? `
          <form id="break-form" class="brk-form" autocomplete="off">
            <input class="field" name="label" maxlength="100" placeholder="Name, e.g. Trip home" value="${esc(s.label)}" aria-label="Name">
            <button class="btn ${level === 'fail' ? 'btn-destructive' : 'btn-primary'}" type="submit">Save break</button>
          </form>
          <p class="hint">Marks ${plural(sim.sessions.length, 'session')} as planned absences. You can remove it later.</p>` : ''}
      </div>
    </div>`;
}

function savedView(saved) {
  if (!saved.length) return '';
  return `
    <section class="brk-saved">
      <h3 class="section-title"><span>Saved breaks</span><span class="muted">${saved.length}</span></h3>
      ${saved.map((b) => `
        <div class="brk-saved-row">
          <button class="brk-saved-main" data-use="${b.start_date}|${b.end_date}" title="Show on the calendar">
            <span>${esc(b.label || 'Break')}</span>
            <span class="brk-facts">${esc(rangeLabel(b.start_date, b.end_date))} · ${plural(b.sessions, 'session')} marked absent</span>
          </button>
          <button class="btn btn-borderless btn-small" data-remove-break="${b.id}" title="Remove and restore those sessions" aria-label="Remove"><svg class="ic" aria-hidden="true"><use href="#i-trash"/></svg></button>
        </div>`).join('')}
    </section>`;
}

function suggestionsView(m, s) {
  const best = findBreakWindows(m, { days: s.len, limit: 4 });
  const lenWord = s.len === 1 ? 'day off' : `${s.len}-day break`;
  const picked = (w) => w.from === s.from && w.to === s.to;
  return `
    <section class="brk-suggest">
      <header class="brk-suggest-head">
        <h3>Suggestions</h3>
        <div class="seg" role="radiogroup" aria-label="Break length">
          ${LENGTHS.map(([d, l]) => `<label class="segment"><input type="radio" name="brk-len" value="${d}" ${d === s.len ? 'checked' : ''}><span>${l}</span></label>`).join('')}
        </div>
      </header>
      ${best.windows.length ? `<div class="list">${best.windows.map((w) => `
        <button class="brk-window ${w.level}${picked(w) ? ' picked' : ''}" data-use="${w.from}|${w.to}">
          <span class="cal-dot" aria-hidden="true"></span>
          <span class="brk-window-range">${esc(rangeLabel(w.from, w.to))}</span>
          <span class="brk-facts">${w.sessions ? `skips ${plural(w.sessions, 'session')}` : 'no classes missed'}${w.holidayDays ? ` · ${plural(w.holidayDays, 'holiday day')}` : ''}${w.level === 'risk' ? ' · no spare skips after' : ''}</span>
        </button>`).join('')}</div>`
      : `<p class="empty">No safe ${lenWord} left in your calendar.${best.longest
        ? ` The longest is <button class="link" data-use="${best.longest.from}|${best.longest.to}">${esc(rangeLabel(best.longest.from, best.longest.to))}</button> (${plural(best.longest.days, 'day')}).` : ''}</p>`}
    </section>`;
}

// ---------------------------------------------------------------- page

export function renderBreaks() {
  const s = ui();
  const m = model();
  const saved = state.data.breaks || [];
  const minMonth = monthOf(m.today), maxMonth = monthOf(m.lastDay < m.today ? m.today : m.lastDay);
  s.month = s.month || monthOf(s.from || m.today);
  if (s.month < minMonth) s.month = minMonth;
  if (s.month > maxMonth) s.month = maxMonth;
  // What missing each single day costs.
  const days = new Map(startDayGrid(m, 1).flat().filter((c) => !c.past && !c.beyond).map((c) => [c.date, c]));
  const sim = s.from && s.to >= s.from ? simulateBreak(m, s.from, s.to) : null;

  $app.innerHTML = `
    <div class="brk">
      <div class="brk-main">
        ${monthView(m, s, days, saved)}
        ${suggestionsView(m, s)}
      </div>
      <aside class="brk-side">
        ${panelView(sim, s, state.data.user.unit)}
        ${savedView(saved)}
      </aside>
    </div>`;
}

// ---------------------------------------------------------------- handlers

function setRange(from, to) {
  const s = ui();
  Object.assign(s, { from, to, phase: 'start', keepEnd: false, month: monthOf(from) });
  render();
  if (matchMedia('(max-width: 1100px)').matches) document.querySelector('.cal-card')?.scrollIntoView({ block: 'start', behavior: 'smooth' });
}

// A click on the calendar sets whichever end is being picked. Picking the first day starts a one-day break
// and moves on to the last day; a last day before the first one starts over from there.
function clickDay(d) {
  const s = ui();
  if (s.phase === 'start') {
    const keep = s.keepEnd && s.to && d <= s.to;
    Object.assign(s, { from: d, to: keep ? s.to : d, phase: keep ? 'start' : 'end', keepEnd: false });
  } else if (d < s.from) {
    Object.assign(s, { from: d, to: d });
  } else {
    Object.assign(s, { to: d, phase: 'start' });
  }
  render();
}

export function onClick(e) {
  const t = e.target;
  const s = ui();
  const day = t.closest('[data-day]');
  if (day) { clickDay(day.dataset.day); return true; }
  const nav = t.closest('[data-month]');
  if (nav) {
    const n = Number(nav.dataset.month);
    s.month = n ? addMonths(s.month, n) : '';
    render();
    return true;
  }
  const pick = t.closest('[data-pick]');
  if (pick) {
    s.phase = pick.dataset.pick;
    s.keepEnd = s.phase === 'start' && Boolean(s.from);
    if (s.from) s.month = monthOf(s.phase === 'start' ? s.from : s.to);
    render();
    return true;
  }
  const use = t.closest('[data-use]');
  if (use) { setRange(...use.dataset.use.split('|')); return true; }
  if (t.closest('[data-action="clear-break"]')) {
    Object.assign(s, { from: '', to: '', label: '', phase: 'start', keepEnd: false });
    render();
    return true;
  }
  const rm = t.closest('[data-remove-break]');
  if (rm) { remove(Number(rm.dataset.removeBreak)); return true; }
  return false;
}

export function onChange(e) {
  if (e.target.name !== 'brk-len') return false;
  ui().len = Number(e.target.value);
  render();
  return true;
}

export function onInput(e) {
  if (e.target.form?.id !== 'break-form' || e.target.name !== 'label') return false;
  ui().label = e.target.value;
  return true;
}

export function onSubmit(e) {
  if (e.target.id !== 'break-form') return false;
  e.preventDefault();
  save();
  return true;
}

// While the last day is being picked, hovering a day previews the range up to it.
document.addEventListener('pointerover', (e) => {
  const s = state.breaks;
  if (state.tab !== 'breaks' || s?.phase !== 'end' || !s.from) return;
  const over = e.target.closest?.('.cal-day[data-day]')?.dataset.day;
  for (const el of document.querySelectorAll('.cal-day[data-day]')) {
    const d = el.dataset.day;
    el.classList.toggle('preview', Boolean(over) && over > s.from && d > s.from && d <= over);
  }
});

async function save() {
  const s = ui();
  const sim = simulateBreak(model(), s.from, s.to);
  if (sim.level === 'fail' && !confirm('This break would drop a course below the minimum or miss an exam/mandatory session. Save it anyway?')) return;
  try {
    const r = await api('/api/breaks', { method: 'POST', body: { from: s.from, to: s.to, label: s.label, tz: tz() } });
    await reload();
    Object.assign(s, { from: '', to: '', label: '', phase: 'start', keepEnd: false });
    render();
    toast(`Break saved: ${plural(r.sessions, 'session')} marked as planned absences.`);
  } catch (err) { toast(err.message, true); }
}

async function remove(id) {
  const b = state.data.breaks.find((x) => x.id === id);
  if (!confirm(`Remove "${b?.label || 'this break'}"? Its sessions go back to how they were marked before.`)) return;
  try {
    const r = await api(`/api/breaks/${id}`, { method: 'DELETE' });
    await reload();
    render();
    toast(`Break removed, ${plural(r.restored, 'session')} restored.`);
  } catch (err) { toast(err.message, true); }
}
