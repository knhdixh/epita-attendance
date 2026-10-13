// Breaks tab: week-by-week overview, break planner with live preview, best-window finder, saved breaks.
import { state, esc, api, toast, render, reload } from './app.js';
import { breakModel, simulateBreak, findBreakWindows, weekOverview, addDays } from './breaks.js';

const $app = document.getElementById('app');
const tz = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
const pct = (x) => `${Math.round(x * 1000) / 10}%`;
const fmtN = (n) => (Number.isInteger(n) ? n : Math.round(n * 10) / 10);
const dayLabel = (d, opts = { weekday: 'short', day: 'numeric', month: 'short' }) =>
  new Date(d + 'T12:00:00Z').toLocaleDateString([], { ...opts, timeZone: 'UTC' });
const LEVEL = {
  free: ['No classes', 'free'], ok: ['Safe', 'ok'], risk: ['Possible, no spare skips left', 'risk'], fail: ['Not possible', 'fail'],
};
const LENGTHS = [[3, 'Long weekend (3 days)'], [4, '4 days'], [5, '5 days'], [7, 'A week'], [9, '9 days (week + 2 weekends)'], [14, 'Two weeks']];

function model() {
  const { courses, events, user } = state.data;
  return breakModel({ courses, events, threshold: user.threshold, unit: user.unit, tz: tz() });
}

function ui() {
  if (!state.breaks) {
    state.breaks = { from: '', to: '', label: '', days: 7, within: '', result: null };
  }
  return state.breaks;
}

function skipsLeft(s, unit) {
  if (s.remaining < -1e-9) return `<span class="bad">over by ${fmtN(Math.ceil(-s.remaining * 2) / 2)}</span>`;
  return `${fmtN(s.remainingDisplay)}${unit === 'hours' ? ' h' : ''}`;
}

function simulationView(sim, unit) {
  if (!sim.sessions.length && !sim.conflicts.length) {
    return `<div class="verdict free">No classes to skip between ${esc(dayLabel(sim.from))} and ${esc(dayLabel(sim.to))}. Enjoy! ${sim.holidayDays ? `(${sim.holidayDays} school-holiday days)` : ''}</div>`;
  }
  const [label, cls] = LEVEL[sim.level];
  return `
    <div class="verdict ${cls}">
      <strong>${label}.</strong> ${sim.days} day${sim.days > 1 ? 's' : ''} off, skipping ${sim.sessions.length} session${sim.sessions.length === 1 ? '' : 's'}${sim.holidayDays ? `, ${sim.holidayDays} school-holiday day${sim.holidayDays > 1 ? 's' : ''} included` : ''}.
    </div>
    ${sim.conflicts.length ? `<div class="note bad">Can't-miss sessions in this break:
      <ul>${sim.conflicts.map((e) => `<li>${esc(e.summary)}, ${esc(new Date(e.start_at).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }))}</li>`).join('')}</ul></div>` : ''}
    <table class="courses sim">
      <thead><tr><th>Course</th><th class="num">Skipped</th><th class="num">Skips left</th><th class="num">After break</th></tr></thead>
      <tbody>${sim.courses.map((c) => `
        <tr><td>${esc(c.course.name)}</td><td class="num">${c.skipped}</td>
          <td class="num">${skipsLeft(c.before, unit)} → ${skipsLeft(c.after, unit)}</td>
          <td class="num ${c.level}">${pct(c.after.projectedRate)}</td></tr>`).join('')}</tbody>
    </table>`;
}

export function renderBreaks() {
  const s = ui();
  const m = model();
  const unit = state.data.user.unit;
  const weeks = weekOverview(m, 12);
  const sim = s.from && s.to && s.to >= s.from ? simulateBreak(m, s.from, s.to) : null;
  const saved = state.data.breaks || [];

  $app.innerHTML = `
    <section class="panel">
      <h3>Week by week <span class="muted small">— could you skip the whole week?</span></h3>
      <div class="weeks">
        ${weeks.map((w) => `
          <button class="week ${LEVEL[w.level][1]}" data-week="${w.from}" title="${esc([LEVEL[w.level][0], ...w.blockers].join('\n'))}">
            <span class="wk-date">${esc(dayLabel(w.from, { day: 'numeric', month: 'short' }))}</span>
            <span class="wk-status">${{ free: '🌴', ok: '✓', risk: '!', fail: '✗' }[w.level]}</span>
            <span class="wk-meta">${w.level === 'free' ? 'no classes' : `${w.sessions} sessions`}</span>
          </button>`).join('')}
      </div>
      ${m.noSkip.length ? `<p class="note warn">No spare absences at all in: ${m.noSkip.map((c) => `<b>${esc(c.name)}</b>`).join(', ')}.
        Any break over one of their sessions would drop that course below ${pct(state.data.user.threshold)}, so plan breaks around them (or check if a session can be excused).</p>` : ''}
      <p class="muted small">✓ safe · ! possible but leaves no spare skips · ✗ would drop a course below ${pct(state.data.user.threshold)} or miss an exam · 🌴 no classes. Click a week to check it.</p>
    </section>

    <div class="breaks-layout">
      <section class="panel">
        <h3>Plan a break</h3>
        <form id="break-form" class="form">
          <div class="form-row">
            <label>First day off <input type="date" name="from" required value="${esc(s.from)}" min="${m.today}"></label>
            <label>Last day off <input type="date" name="to" required value="${esc(s.to)}" min="${esc(s.from || m.today)}"></label>
          </div>
          <label>Name <input name="label" maxlength="100" placeholder="Optional, e.g. Trip home" value="${esc(s.label)}"></label>
        </form>
        <div id="break-result">${sim ? simulationView(sim, unit) : '<p class="muted">Pick dates to see the effect on every course.</p>'}</div>
        ${sim && sim.sessions.length ? `
          <button class="btn ${sim.level === 'fail' ? 'danger' : 'primary'}" data-action="save-break">
            Save break (mark ${sim.sessions.length} session${sim.sessions.length === 1 ? '' : 's'} as planned absences)</button>` : ''}
      </section>

      <section class="panel">
        <h3>Find the best time</h3>
        <form id="find-form" class="form">
          <label>How long
            <select name="days">${LENGTHS.map(([d, l]) => `<option value="${d}" ${d === s.days ? 'selected' : ''}>${l}</option>`).join('')}</select>
          </label>
          <label>Finish before <input type="date" name="within" value="${esc(s.within)}" min="${m.today}" max="${m.lastDay}" title="Optional"></label>
          <button class="btn primary" type="submit">Find dates</button>
        </form>
        <div id="find-result">${s.result ? findView(s.result) : `<p class="muted small">Ranks breaks by fewest classes missed. Weekends and school holidays count as free days, and breaks over exams are excluded. Your calendar runs until ${esc(dayLabel(m.lastDay))}.</p>`}</div>
      </section>
    </div>

    ${saved.length ? `
    <section class="panel">
      <h3>Saved breaks</h3>
      <div class="list">${saved.map((b) => `
        <div class="hw-item">
          <span>🌴</span>
          <div class="hw-main">
            <div class="hw-title">${esc(b.label || 'Break')}</div>
            <div class="hw-meta small">${esc(dayLabel(b.start_date))} – ${esc(dayLabel(b.end_date))} · ${b.sessions} session${b.sessions === 1 ? '' : 's'} marked absent</div>
          </div>
          <div class="hw-actions"><button class="btn small ghost" data-remove-break="${b.id}" title="Remove and restore those sessions">Remove</button></div>
        </div>`).join('')}</div>
    </section>` : ''}
    <p class="muted small">Tip: you can also ask the Assistant, e.g. “when could I go home for a week in November?”.</p>`;
}

function findView(r) {
  if (!r.windows.length) {
    return `<div class="verdict fail">No safe ${r.days}-day break${r.within ? ` before ${esc(dayLabel(r.within))}` : ''}.</div>
      ${r.longest ? `<p>Longest safe break: <b>${esc(dayLabel(r.longest.from))} – ${esc(dayLabel(r.longest.to))}</b> (${r.longest.days} days, ${r.longest.sessions} sessions).
        <button class="btn small" data-use="${r.longest.from}|${r.longest.to}">Check it</button></p>` : ''}`;
  }
  return `<div class="list">${r.windows.map((w) => `
    <div class="window ${w.level}">
      <div><b>${esc(dayLabel(w.from))} – ${esc(dayLabel(w.to))}</b>
        <div class="muted small">${w.sessions ? `skips ${w.sessions} session${w.sessions === 1 ? '' : 's'}` : 'no classes missed'}${w.holidayDays ? ` · ${w.holidayDays} holiday days` : ''}${w.level === 'risk' ? ' · leaves no spare skips' : ''}</div></div>
      <button class="btn small" data-use="${w.from}|${w.to}">Check</button>
    </div>`).join('')}</div>`;
}

// ---------------------------------------------------------------- handlers

export function onClick(e) {
  const t = e.target;
  const s = ui();
  const week = t.closest('[data-week]');
  if (week) {
    const from = week.dataset.week;
    s.from = from < model().today ? model().today : from;
    s.to = addDays(from, 6);
    render();
    document.getElementById('break-form')?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    return true;
  }
  const use = t.closest('[data-use]');
  if (use) {
    [s.from, s.to] = use.dataset.use.split('|');
    render();
    document.getElementById('break-form')?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    return true;
  }
  if (t.closest('[data-action="save-break"]')) { save(); return true; }
  const rm = t.closest('[data-remove-break]');
  if (rm) { remove(Number(rm.dataset.removeBreak)); return true; }
  return false;
}

export function onChange(e) {
  const form = e.target.closest('#break-form');
  if (!form) return false;
  const s = ui();
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
  if (e.target.id === 'break-form') { e.preventDefault(); return true; }
  if (e.target.id !== 'find-form') return false;
  e.preventDefault();
  const s = ui();
  const fd = new FormData(e.target);
  s.days = Number(fd.get('days'));
  s.within = fd.get('within') || '';
  const r = findBreakWindows(model(), { days: s.days, to: s.within || undefined });
  s.result = { ...r, days: s.days, within: s.within };
  render();
  return true;
}

async function save() {
  const s = ui();
  const sim = simulateBreak(model(), s.from, s.to);
  if (sim.level === 'fail' && !confirm('This break would drop a course below the minimum or miss an exam/mandatory session. Save it anyway?')) return;
  try {
    const r = await api('/api/breaks', { method: 'POST', body: { from: s.from, to: s.to, label: s.label, tz: tz() } });
    await reload();
    Object.assign(s, { from: '', to: '', label: '', result: null });
    render();
    toast(`Break saved: ${r.sessions} session${r.sessions === 1 ? '' : 's'} marked as planned absences.`);
  } catch (err) { toast(err.message, true); }
}

async function remove(id) {
  const b = state.data.breaks.find((x) => x.id === id);
  if (!confirm(`Remove "${b?.label || 'this break'}"? Its sessions go back to how they were marked before.`)) return;
  try {
    const r = await api(`/api/breaks/${id}`, { method: 'DELETE' });
    await reload();
    render();
    toast(`Break removed, ${r.restored} session${r.restored === 1 ? '' : 's'} restored.`);
  } catch (err) { toast(err.message, true); }
}
