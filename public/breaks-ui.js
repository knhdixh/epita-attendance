// Breaks tab: pick a length, see the best dates and a calendar of start days, and check any break in a side panel
// that updates as you click. Saving a break marks its sessions as planned absences.
import { state, esc, api, toast, render, reload } from './app.js';
import { breakModel, simulateBreak, findBreakWindows, startDayGrid, addDays } from './breaks.js';

const $app = document.getElementById('app');
const tz = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
const pct = (x) => `${Math.round(x * 1000) / 10}%`;
const fmtN = (n) => (Number.isInteger(n) ? n : Math.round(n * 10) / 10);
const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;
const dayLabel = (d, opts = { weekday: 'short', day: 'numeric', month: 'short' }) =>
  new Date(d + 'T12:00:00Z').toLocaleDateString([], { ...opts, timeZone: 'UTC' });
// "Mon, Oct 19 – Wed, Oct 21", or one date for a one-day range.
const rangeLabel = (from, to, sep = '–') => (from === to ? dayLabel(from) : `${dayLabel(from)} ${sep} ${dayLabel(to)}`);
const LENGTHS = [[1, '1 day'], [3, 'Long weekend'], [7, '1 week'], [9, '9 days'], [14, '2 weeks']];
const VERDICT = {
  free: 'No classes to miss', ok: 'Safe', risk: 'Possible, but leaves no spare skips', fail: 'Not possible',
};

function model() {
  const { courses, events, user } = state.data;
  return breakModel({ courses, events, threshold: user.threshold, unit: user.unit, tz: tz() });
}

function ui() {
  state.breaks ??= { from: '', to: '', label: '', len: 7 };
  return state.breaks;
}

function skipsLeft(s, unit) {
  if (s.remaining < -1e-9) return `over by ${fmtN(Math.ceil(-s.remaining * 2) / 2)}`;
  return `${fmtN(s.remainingDisplay)}${unit === 'hours' ? ' h' : ''} left`;
}

// ---------------------------------------------------------------- calendar

// One cell per possible start day. Safe days stand out; impossible ones recede. The picked break is a band.
function calCell(c, i, s) {
  const d = new Date(c.date + 'T12:00:00Z');
  const month = i === 0 || d.getUTCDate() === 1 ? dayLabel(c.date, { month: 'short' }) : '';
  const num = `${month ? `<span class="cal-month">${esc(month)}</span>` : ''}<span class="cal-num">${d.getUTCDate()}</span>`;
  const inBreak = s.from && c.date >= s.from && c.date <= s.to;
  const band = inBreak ? ` in${c.date === s.from ? ' first' : ''}${c.date === s.to ? ' last' : ''}` : '';
  if (c.past || c.beyond) {
    return `<div class="cal-day off${band}" title="${c.past ? 'Past' : 'After the end of your calendar'}">${num}</div>`;
  }
  const title = [`${s.len}-day break from ${dayLabel(c.date)}: ${VERDICT[c.level]}`, c.sessions ? `${plural(c.sessions, 'session')} missed` : '', ...c.blockers]
    .filter(Boolean).join('\n');
  return `<button class="cal-day ${c.level}${c.holiday ? ' holiday' : ''}${band}" data-start="${c.date}" title="${esc(title)}" aria-label="${esc(title)}">
    ${num}<span class="cal-dot" aria-hidden="true"></span></button>`;
}

// ---------------------------------------------------------------- the picked break

function detailView(sim, s, m, unit) {
  const level = sim.sessions.length || sim.conflicts.length ? sim.level : 'free';
  const facts = [plural(sim.days, 'day'), sim.sessions.length ? `skips ${plural(sim.sessions.length, 'session')}` : 'no classes missed'];
  if (sim.holidayDays) facts.push(plural(sim.holidayDays, 'holiday day'));
  return `
    <div class="brk-card">
      <div class="brk-card-head">
        <div>
          <div class="brk-range">${esc(rangeLabel(sim.from, sim.to, '→'))}</div>
          <div class="brk-facts">${facts.join(' · ')}</div>
        </div>
        <button class="icon-btn" data-action="clear-break" title="Clear" aria-label="Clear"><svg class="ic sm" aria-hidden="true"><use href="#i-plus" transform="rotate(45 12 12)"/></svg></button>
      </div>
      <p class="brk-verdict ${level}">${VERDICT[level]}</p>
      ${sim.conflicts.length ? `<div class="brk-conflicts"><b>Can't miss:</b>
        <ul>${sim.conflicts.map((e) => `<li>${esc(e.summary)}, ${esc(new Date(e.start_at).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }))}</li>`).join('')}</ul></div>` : ''}
      ${sim.courses.length ? `<div class="brk-courses">${sim.courses.map((c) => `
        <div class="brk-course ${c.level}">
          <span class="name">${esc(c.course.name)}</span>
          <span class="skip">−${c.skipped}</span>
          <span class="left">${esc(skipsLeft(c.before, unit))} <span aria-hidden="true">→</span> <b>${esc(skipsLeft(c.after, unit))}</b></span>
          <span class="after">${pct(c.after.projectedRate)}</span>
        </div>`).join('')}</div>` : ''}
      <form id="break-form" class="brk-form" autocomplete="off">
        <div class="brk-dates">
          <label>From <input class="field" type="date" name="from" required value="${esc(s.from)}" min="${m.today}"></label>
          <label>To <input class="field" type="date" name="to" required value="${esc(s.to)}" min="${esc(s.from || m.today)}"></label>
        </div>
        <input class="field" name="label" maxlength="100" placeholder="Name, e.g. Trip home" value="${esc(s.label)}" aria-label="Name">
      </form>
      ${sim.sessions.length ? `
        <button class="btn ${level === 'fail' ? 'btn-destructive' : 'btn-primary'} brk-save" data-action="save-break">Save break</button>
        <p class="hint">Marks ${plural(sim.sessions.length, 'session')} as planned absences. You can remove it later.</p>` : ''}
    </div>`;
}

function savedView(saved) {
  if (!saved.length) return '';
  return `
    <section class="brk-saved">
      <h3 class="section-title"><span>Saved breaks</span><span class="muted">${saved.length}</span></h3>
      ${saved.map((b) => `
        <div class="brk-saved-row">
          <div>
            <div>${esc(b.label || 'Break')}</div>
            <div class="brk-facts">${esc(rangeLabel(b.start_date, b.end_date))} · ${plural(b.sessions, 'session')} marked absent</div>
          </div>
          <button class="btn btn-borderless btn-small" data-remove-break="${b.id}" title="Remove and restore those sessions" aria-label="Remove"><svg class="ic" aria-hidden="true"><use href="#i-trash"/></svg></button>
        </div>`).join('')}
    </section>`;
}

// ---------------------------------------------------------------- page

export function renderBreaks() {
  const s = ui();
  const m = model();
  const unit = state.data.user.unit;
  const grid = startDayGrid(m, s.len);
  const best = findBreakWindows(m, { days: s.len, limit: 4 });
  const sim = s.from && s.to && s.to >= s.from ? simulateBreak(m, s.from, s.to) : null;
  const picked = (w) => w.from === s.from && w.to === s.to;
  const lenWord = s.len === 1 ? 'day off' : `${s.len}-day break`;

  $app.innerHTML = `
    <div class="brk-toolbar">
      <div class="seg" role="radiogroup" aria-label="Break length">
        ${LENGTHS.map(([d, l]) => `<label class="segment"><input type="radio" name="brk-len" value="${d}" ${d === s.len ? 'checked' : ''}><span>${l}</span></label>`).join('')}
      </div>
      <span class="hint">Calendar until ${esc(dayLabel(m.lastDay, { day: 'numeric', month: 'short' }))}</span>
    </div>

    <div class="brk">
      <div class="brk-main">
        <section class="panel">
          <h3>Best dates</h3>
          ${best.windows.length ? `<div class="list">${best.windows.map((w) => `
            <button class="brk-window ${w.level}${picked(w) ? ' picked' : ''}" data-use="${w.from}|${w.to}">
              <span class="cal-dot" aria-hidden="true"></span>
              <span class="brk-window-range">${esc(rangeLabel(w.from, w.to))}</span>
              <span class="brk-facts">${w.sessions ? `skips ${plural(w.sessions, 'session')}` : 'no classes missed'}${w.holidayDays ? ` · ${plural(w.holidayDays, 'holiday day')}` : ''}${w.level === 'risk' ? ' · no spare skips after' : ''}</span>
            </button>`).join('')}</div>`
          : `<p class="empty">No safe ${lenWord} left in your calendar.${best.longest
            ? ` The longest is <button class="link" data-use="${best.longest.from}|${best.longest.to}">${esc(rangeLabel(best.longest.from, best.longest.to))}</button> (${plural(best.longest.days, 'day')}).` : ''}</p>`}
        </section>

        <section class="panel">
          <h3><span>Start a ${lenWord} on…</span>
            <span class="brk-legend"><i class="ok"></i>safe<i class="risk"></i>tight<i class="fail"></i>not possible<i class="free"></i>no classes</span></h3>
          <div class="cal">
            ${['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => `<div class="cal-dow">${d}</div>`).join('')}
            ${grid.flat().map((c, i) => calCell(c, i, s)).join('')}
          </div>
          ${m.noSkip.length ? `<p class="hint">No spare absences in ${m.noSkip.map((c) => `<b>${esc(c.name)}</b>`).join(', ')}:
            any break over one of their sessions drops it below ${pct(state.data.user.threshold)}.</p>` : ''}
        </section>
      </div>

      <aside class="brk-side">
        ${sim ? detailView(sim, s, m, unit) : `
          <div class="brk-card brk-empty">
            <svg class="ic" aria-hidden="true"><use href="#i-breaks"/></svg>
            <p>Pick a day on the calendar or one of the best dates to see what the break costs in every course.</p>
            <p class="hint">Or ask the <a href="#assistant" data-goto="assistant">Assistant</a>, e.g. “when could I go home for a week in November?”</p>
          </div>`}
        ${savedView(state.data.breaks || [])}
      </aside>
    </div>`;
}

// ---------------------------------------------------------------- handlers

function pick(from, to) {
  const s = ui();
  [s.from, s.to] = [from, to];
  render();
  // On narrow screens the panel sits below the calendar; bring it into view.
  if (matchMedia('(max-width: 1100px)').matches) document.querySelector('.brk-side')?.scrollIntoView({ block: 'start', behavior: 'smooth' });
}

export function onClick(e) {
  const t = e.target;
  const s = ui();
  const start = t.closest('[data-start]');
  if (start) { pick(start.dataset.start, addDays(start.dataset.start, s.len - 1)); return true; }
  const use = t.closest('[data-use]');
  if (use) { pick(...use.dataset.use.split('|')); return true; }
  if (t.closest('[data-action="clear-break"]')) { Object.assign(s, { from: '', to: '', label: '' }); render(); return true; }
  if (t.closest('[data-action="save-break"]')) { save(); return true; }
  const rm = t.closest('[data-remove-break]');
  if (rm) { remove(Number(rm.dataset.removeBreak)); return true; }
  return false;
}

export function onChange(e) {
  const s = ui();
  if (e.target.name === 'brk-len') {
    s.len = Number(e.target.value);
    if (s.from) s.to = addDays(s.from, s.len - 1); // keep the picked break the chosen length
    render();
    return true;
  }
  const form = e.target.closest('#break-form');
  if (!form || e.target.name === 'label') return false;
  s.from = form.elements.from.value;
  s.to = form.elements.to.value;
  if (s.from && (!s.to || s.to < s.from)) s.to = s.from;
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
  if (document.querySelector('[data-action="save-break"]')) save();
  return true;
}

async function save() {
  const s = ui();
  const sim = simulateBreak(model(), s.from, s.to);
  if (sim.level === 'fail' && !confirm('This break would drop a course below the minimum or miss an exam/mandatory session. Save it anyway?')) return;
  try {
    const r = await api('/api/breaks', { method: 'POST', body: { from: s.from, to: s.to, label: s.label, tz: tz() } });
    await reload();
    Object.assign(s, { from: '', to: '', label: '' });
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
