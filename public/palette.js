// Command palette: "/" or Cmd/Ctrl+K opens it from anywhere. It searches pages, actions, courses, homework and
// sessions, and runs the chosen one. The items come from app.js on every keystroke, so they always match the data.
// An item is { id, section, label, sub?, icon, tone?, keys?, keywords?, home?, run }: `home` items are listed
// before anything is typed, and `keys` is the shortcut shown on the right, e.g. ['G', 'then', 'H'].

const $wrap = document.getElementById('palette');
const $q = document.getElementById('palette-q');
const $list = document.getElementById('palette-list');

const SECTIONS = ['Recent', 'Navigation', 'Actions', 'Focus', 'Courses', 'Homework', 'Sessions', 'Assistant'];
const PER_SECTION = { Focus: 6, Courses: 6, Homework: 6, Sessions: 6 };
const RECENT_KEY = 'a80-palette-recent';

let source = { items: () => [], fallback: () => [], help: () => '' };
let shown = [];
let active = 0;
let returnFocus = null;

export function setSource(s) { source = s; }
export const isOpen = () => !$wrap.hidden;

export function open(query = '') {
  returnFocus = document.activeElement;
  $wrap.hidden = false;
  $q.value = query;
  update();
  $q.focus();
}

export function close() {
  if ($wrap.hidden) return;
  $wrap.hidden = true;
  if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
}

// ---------------------------------------------------------------- matching

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const fold = (s) => s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');

// Prefix beats word start beats anywhere beats every word somewhere beats initials ("pm" = Project Management).
function score(item, q) {
  const label = fold(item.label);
  const i = label.indexOf(q);
  if (i === 0) return 100;
  if (i > 0) return (/[\s(–-]/.test(label[i - 1]) ? 80 : 60) - i / 100;
  const hay = `${label} ${fold(item.sub || '')} ${fold(item.keywords || '')}`;
  if (hay.includes(q)) return 40;
  const words = q.split(/\s+/).filter(Boolean);
  if (words.length > 1 && words.every((w) => hay.includes(w))) return 30;
  const initials = label.split(/[\s–-]+/).map((w) => w[0]).join('');
  return q.length > 1 && initials.startsWith(q.replace(/\s+/g, '')) ? 10 : 0;
}

function highlight(label, q) {
  const i = q ? fold(label).indexOf(q) : -1;
  if (i < 0) return esc(label);
  return `${esc(label.slice(0, i))}<mark>${esc(label.slice(i, i + q.length))}</mark>${esc(label.slice(i + q.length))}`;
}

function recentIds() {
  try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); } catch { return []; }
}
function remember(id) {
  if (!id) return;
  try { localStorage.setItem(RECENT_KEY, JSON.stringify([id, ...recentIds().filter((x) => x !== id)].slice(0, 5))); } catch { /* storage unavailable */ }
}

// ---------------------------------------------------------------- rendering

function update() {
  const raw = $q.value.trim();
  const q = fold(raw);
  active = 0;

  if (raw === '?') {
    shown = [];
    $list.innerHTML = source.help();
    $q.removeAttribute('aria-activedescendant');
    return;
  }

  const all = source.items();
  let groups;
  if (!q) {
    const byId = new Map(all.map((it) => [it.id, it]));
    const recent = recentIds().map((id) => byId.get(id)).filter(Boolean).slice(0, 3);
    const seen = new Set(recent.map((it) => it.id));
    groups = [['Recent', recent.map((it) => ({ ...it, section: 'Recent' }))],
      ...SECTIONS.slice(1).map((s) => [s, all.filter((it) => it.home && it.section === s && !seen.has(it.id))])];
  } else {
    const scored = all.map((it) => [it, score(it, q)]).filter(([, sc]) => sc > 0);
    groups = SECTIONS.map((s) => [s, scored.filter(([it]) => it.section === s)
      .sort((a, b) => b[1] - a[1]).slice(0, PER_SECTION[s] || 8).map(([it]) => it)]);
    groups.push(['Assistant', source.fallback(raw)]);
  }
  groups = groups.filter(([, items]) => items.length);
  shown = groups.flatMap(([, items]) => items);

  let n = 0;
  $list.innerHTML = groups.map(([section, items]) => `
    <div class="pal-section" role="presentation">${esc(section)}</div>
    ${items.map((it) => {
      const i = n++;
      return `<div class="pal-item" role="option" id="pal-${i}" data-i="${i}" aria-selected="false">
        <svg class="ic ${it.tone || ''}" aria-hidden="true"><use href="#${it.icon}"/></svg>
        <span class="pal-label">${highlight(it.label, it.section === 'Assistant' ? '' : q)}${it.sub ? `<span class="pal-sub">${esc(it.sub)}</span>` : ''}</span>
        ${it.keys ? `<span class="pal-hint">${it.keys.map((k) => (k === 'then' ? '<span class="then">then</span>' : `<kbd>${esc(k)}</kbd>`)).join('')}</span>` : ''}
      </div>`;
    }).join('')}`).join('') || `<div class="pal-empty">Nothing found for “${esc(raw)}”.</div>`;
  select(0);
}

function select(i, scroll = true) {
  if (!shown.length) return;
  active = (i + shown.length) % shown.length;
  $list.querySelector('[aria-selected="true"]')?.setAttribute('aria-selected', 'false');
  const el = document.getElementById(`pal-${active}`);
  el.setAttribute('aria-selected', 'true');
  $q.setAttribute('aria-activedescendant', el.id);
  if (scroll) el.scrollIntoView({ block: 'nearest' });
}

function run(i) {
  const it = shown[i];
  if (!it) return;
  close();
  remember(it.recentId ?? it.id);
  it.run();
}

// ---------------------------------------------------------------- events

$q.addEventListener('input', update);
$q.addEventListener('keydown', (e) => {
  const down = e.key === 'ArrowDown' || (e.ctrlKey && e.key === 'n') || (e.key === 'Tab' && !e.shiftKey);
  const up = e.key === 'ArrowUp' || (e.ctrlKey && e.key === 'p') || (e.key === 'Tab' && e.shiftKey);
  if (down || up) { e.preventDefault(); select(active + (down ? 1 : -1)); }
  else if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); run(active); }
  else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); }
});
$list.addEventListener('pointermove', (e) => {
  const el = e.target.closest('.pal-item');
  if (el && Number(el.dataset.i) !== active) select(Number(el.dataset.i), false);
});
$list.addEventListener('click', (e) => {
  const el = e.target.closest('.pal-item');
  if (el) run(Number(el.dataset.i));
});
$wrap.addEventListener('mousedown', (e) => { if (e.target === $wrap) close(); });
