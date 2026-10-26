// Today: the home page. What's on now, what to do next and where it fits, from public/plan.js.
import { state, esc, fmtTime, courseById, sessionRow } from './app.js';
import { buildPlan, DAY_START, DAY_END } from './plan.js';
import * as hw from './homework-ui.js';
import { todayPanel } from './focus-ui.js';

const $app = document.getElementById('app');

const hours = (h) => (h < 1 ? `${Math.round(h * 60)} min` : `${Math.round(h * 2) / 2} h`);
const mins = (m) => (m < 60 ? `${m} min` : `${Math.round(m / 30) / 2} h`);

// One sentence for the top of the page: where you are and what comes next.
function headline(plan, ev, byId) {
  const now = plan.now;
  const cur = plan.current.map(ev)[0];
  const next = plan.next && ev(plan.next);
  const nextToday = next && plan.todaySessions.includes(next.id);
  const first = plan.todo[0];
  const parts = [];
  if (cur) parts.push(`In <b>${esc(byId[cur.course_id]?.name)}</b> until ${fmtTime(cur.end_at)}.`);
  if (nextToday) parts.push(`Next: <b>${esc(byId[next.course_id]?.name)}</b> at ${fmtTime(next.start_at)}${next.location ? `, ${esc(next.location)}` : ''}.`);
  else if (!cur) parts.push('No more classes today.');
  const gap = plan.gaps.find((g) => g.fits.length);
  if (gap) parts.push(`${gap.start <= now + 60000 ? 'Now' : `Free from ${fmtTime(gap.start)}`}: <b>${esc(gap.fits[0].title)}</b>.`);
  else if (first?.urgency === 'overdue') parts.push(`<b>${esc(first.title)}</b> is overdue.`);
  return parts.join(' ');
}

export function renderToday() {
  const { courses, events, homework } = state.data;
  const plan = buildPlan({ courses, events, homework, now: Date.now() });
  const evById = new Map(events.map((e) => [e.id, e]));
  const ev = (id) => evById.get(id);
  const byId = courseById();
  const hwById = new Map(homework.map((h) => [h.id, h]));
  const { week } = plan;
  state.todayCount = plan.todo.length;

  const tomorrow = plan.tomorrowFirst && ev(plan.tomorrowFirst);
  const clash = week.clashes.map(([a, b]) => [ev(a), ev(b)]).filter(([a, b]) => a && b);

  $app.innerHTML = `
    <p class="today-line">${headline(plan, ev, byId)}</p>

    ${clash.length ? `<div class="banner warn"><span>${clash.length === 1 ? 'Two sessions overlap' : `${clash.length} overlaps`} this week:
      ${clash.slice(0, 3).map(([a, b]) => `${esc(byId[a.course_id]?.name)} and ${esc(byId[b.course_id]?.name)} on ${esc(new Date(a.start_at).toLocaleDateString([], { weekday: 'short' }))} ${fmtTime(Math.max(a.start_at, b.start_at))}`).join('; ')}.</span></div>` : ''}

    <section class="panel">
      <h3><span>Do next</span><a href="#homework" data-goto="homework">all homework →</a></h3>
      ${plan.todo.length ? `<div class="list">${plan.todo.map((h) => hw.hwItem(hwById.get(h.id), plan.now, { urgency: h.urgency, effort: h.effort })).join('')}</div>`
        : '<p class="empty">Nothing due in the next 7 days.</p>'}
      ${plan.later ? `<p class="muted small">${plan.later} more due later.</p>` : ''}
    </section>

    ${todayPanel()}

    ${plan.gaps.length ? `
    <section class="panel">
      <h3>Free time today</h3>
      <div class="gaps">${plan.gaps.map((g) => `
        <div class="gap">
          <div class="when">${fmtTime(g.start)}<span>–${fmtTime(g.end)}</span></div>
          <div class="what">
            <div class="title">${mins(g.minutes)} free</div>
            ${g.fits.length ? `<div class="muted small">${g.fits.map((f) => `${f.partial ? 'start ' : ''}<a href="#homework" data-hw-open="${f.id}">${esc(f.title)}</a> (${mins(f.minutes)})`).join(', ')}</div>` : ''}
          </div>
        </div>`).join('')}</div>
    </section>` : ''}

    <section class="panel">
      <h3><span>Today's sessions</span><a href="#upcoming" data-goto="upcoming">week →</a></h3>
      ${plan.todaySessions.length ? `<div class="list">${plan.todaySessions.map((id) => sessionRow(ev(id))).join('')}</div>`
        : '<p class="empty">No classes today.</p>'}
      ${tomorrow ? `<p class="muted small">Tomorrow starts at ${fmtTime(tomorrow.start_at)}: ${esc(tomorrow.summary)}${tomorrow.location ? `, ${esc(tomorrow.location)}` : ''}.</p>` : ''}
    </section>

    <section class="panel">
      <h3>Next 7 days</h3>
      <p class="week-line ${week.load > 0.6 ? 'risk' : ''}">${hours(week.classHours)} of class, ${hours(week.workHours)} of work due,
        about ${hours(week.freeHours)} free between ${DAY_START}:00 and ${DAY_END}:00.${week.load > 0.6 ? ' That\'s tight: start the biggest item early.' : ''}</p>
    </section>`;
}
