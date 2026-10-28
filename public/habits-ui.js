// Habits: things to do on some days of the week, ticked off per day, with a streak, the last two weeks, and an
// optional reminder (a notification, see public/notify-ui.js). Days are in each habit's time zone.
import { state, esc, api, toast, render } from './app.js';
import * as H from './habits.js';

const $app = document.getElementById('app');
const STRIP = 14;
const tz = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
const habits = () => state.data.habits;
const todayOf = (h) => H.localTime(Date.now(), h.tz).day;

// "3 of 4 done today" for the page title and the sidebar count.
export function todayCounts() {
  const due = habits().filter((h) => H.isDue(h, todayOf(h)));
  return { due: due.length, done: due.filter((h) => h.checks.includes(todayOf(h))).length };
}
export function subtitle() {
  const { due, done } = todayCounts();
  return due ? `${done} of ${due} done today` : habits().length ? 'Nothing due today' : '';
}

function form(h) {
  const draft = h || { name: '', days: H.ALL_DAYS, remind_at: null };
  return `
    <form id="habit-form" class="habit-form${h ? ' editing' : ''}" autocomplete="off" data-habit-id="${h?.id ?? ''}">
      <input class="field" name="name" required maxlength="${H.NAME_LEN}" placeholder="New habit, e.g. Read 20 pages" value="${esc(draft.name)}" aria-label="Habit">
      <div class="habit-days" role="group" aria-label="Days">
        ${H.DAY_NAMES.map((d, i) => `<button type="button" class="tag-chip" data-habit-day="${i}" aria-pressed="${Boolean(draft.days & (1 << i))}" title="${d}">${d[0]}</button>`).join('')}
      </div>
      <label class="habit-time" title="Reminder (leave empty for none)">Remind at
        <input class="field" type="time" name="remind_at" value="${esc(draft.remind_at || '')}"></label>
      <input type="hidden" name="days" value="${draft.days}">
      <div class="habit-form-actions">
        <button class="btn btn-primary btn-small" type="submit">${h ? 'Save' : 'Add habit'}</button>
        ${h ? '<button class="btn btn-small" type="button" data-habit-cancel>Cancel</button>' : ''}
      </div>
    </form>`;
}

function strip(h, today) {
  const days = Array.from({ length: STRIP }, (_, i) => H.addDay(today, i - STRIP + 1));
  const done = new Set(h.checks);
  return `<div class="habit-strip" role="group" aria-label="Last ${STRIP} days">${days.map((d) => {
    const due = H.isDue(h, d);
    const label = new Date(`${d}T12:00:00Z`).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
    return `<button class="habit-day${done.has(d) ? ' on' : ''}${due ? '' : ' off'}${d === today ? ' today' : ''}" data-habit-toggle="${d}"
      title="${esc(label)}${due ? '' : ' (day off)'}${done.has(d) ? ': done' : ''}" aria-pressed="${done.has(d)}" aria-label="${esc(label)}"></button>`;
  }).join('')}</div>`;
}

function row(h) {
  if (state.habitEdit === h.id) return form(h);
  const today = todayOf(h);
  const due = H.isDue(h, today);
  const done = h.checks.includes(today);
  const n = H.streak(h, today);
  return `
    <div class="hw-item habit${done ? ' done' : ''}" data-habit="${h.id}">
      <label class="checkbox round hw-check" title="${!due ? 'Not scheduled today' : done ? 'Mark not done' : 'Mark done today'}">
        <input type="checkbox" data-habit-check ${done ? 'checked' : ''} aria-label="Done today: ${esc(h.name)}"><span></span></label>
      <div class="hw-main">
        <div class="hw-title">${esc(h.name)}</div>
        <div class="hw-meta">
          <span>${esc(H.daysLabel(h.days))}${due ? '' : ' · day off today'}</span>
          ${h.remind_at ? `<span title="Reminder">reminder ${esc(h.remind_at)}${h.tz !== tz() ? ` (${esc(h.tz)})` : ''}</span>` : ''}
          ${n ? `<span class="habit-streak" title="Scheduled days done in a row">${n}-day streak</span>` : ''}
        </div>
        ${strip(h, today)}
      </div>
      <div class="hw-actions">
        <button class="btn btn-borderless btn-small" data-habit-edit title="Edit" aria-label="Edit"><svg class="ic" aria-hidden="true"><use href="#i-edit"/></svg></button>
        <button class="btn btn-borderless btn-small" data-habit-delete title="Delete" aria-label="Delete"><svg class="ic" aria-hidden="true"><use href="#i-trash"/></svg></button>
      </div>
    </div>`;
}

export function renderHabits() {
  const list = habits();
  const n = state.data.notifications;
  const remind = list.some((h) => h.remind_at);
  const tip = remind && n?.key && (!n.devices || !n.settings.habits)
    ? `<p class="banner"><span>Reminders need notifications on.</span><button class="btn btn-small" data-goto="settings">Turn on in Settings</button></p>` : '';
  $app.innerHTML = `
    <section class="panel narrow">
      ${tip}
      ${state.habitEdit ? '' : form(null)}
      <div class="list habits">${list.map(row).join('')}</div>
      ${list.length ? '' : '<p class="muted small">Habits are things you want to do regularly: reading, sport, revising flashcards. Tick them off each day to keep a streak, and set a time to be reminded.</p>'}
    </section>`;
}

async function setCheck(h, day, done) {
  const before = h.checks;
  h.checks = done ? [...before, day].sort() : before.filter((d) => d !== day);
  render();
  try { await api(`/api/habits/${h.id}/checks/${day}`, { method: done ? 'PUT' : 'DELETE' }); }
  catch (err) { h.checks = before; render(); toast(err.message, true); }
}

const habitOf = (el) => habits().find((h) => h.id === Number(el.closest('[data-habit]')?.dataset.habit));

export function onClick(e) {
  const t = e.target;
  const dayBtn = t.closest('[data-habit-day]');
  if (dayBtn) {
    const formEl = dayBtn.closest('form');
    const input = formEl.elements.days;
    const days = Number(input.value) ^ (1 << Number(dayBtn.dataset.habitDay));
    if (!days) { toast('Pick at least one day.', true); return true; }
    input.value = days;
    dayBtn.setAttribute('aria-pressed', String(Boolean(days & (1 << Number(dayBtn.dataset.habitDay)))));
    return true;
  }
  if (t.closest('[data-habit-cancel]')) { state.habitEdit = null; render(); return true; }
  const tog = t.closest('[data-habit-toggle]');
  if (tog) {
    const h = habitOf(tog);
    setCheck(h, tog.dataset.habitToggle, !h.checks.includes(tog.dataset.habitToggle));
    return true;
  }
  if (t.closest('[data-habit-edit]')) { state.habitEdit = habitOf(t).id; render(); document.querySelector('#habit-form [name=name]')?.focus(); return true; }
  const del = t.closest('[data-habit-delete]');
  if (del) {
    const h = habitOf(del);
    if (!confirm(`Delete "${h.name}" and its history?`)) return true;
    api(`/api/habits/${h.id}`, { method: 'DELETE' })
      .then(() => { state.data.habits = habits().filter((x) => x.id !== h.id); render(); toast('Habit deleted.'); })
      .catch((err) => toast(err.message, true));
    return true;
  }
  return false;
}

export function onChange(e) {
  const box = e.target.closest('[data-habit-check]');
  if (!box) return false;
  const h = habitOf(box);
  setCheck(h, todayOf(h), box.checked);
  return true;
}

export function onSubmit(e) {
  if (e.target.id !== 'habit-form') return false;
  e.preventDefault();
  const fd = new FormData(e.target);
  const id = Number(e.target.dataset.habitId) || null;
  const body = { name: fd.get('name'), days: Number(fd.get('days')), remind_at: fd.get('remind_at') || null, tz: tz() };
  api(id ? `/api/habits/${id}` : '/api/habits', { method: id ? 'PATCH' : 'POST', body })
    .then((r) => {
      if (id) Object.assign(habits().find((h) => h.id === id), r.habit);
      else habits().push(r.habit);
      state.habitEdit = null;
      render();
      if (!id) document.querySelector('#habit-form [name=name]')?.focus();
    })
    .catch((err) => toast(err.message, true));
  return true;
}
