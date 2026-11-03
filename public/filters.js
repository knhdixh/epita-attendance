// Filters: a small version of Todoist's filter language, plus two of ours.
//
//   today  tomorrow  yesterday  overdue  no date  date: <date>  due before: <date>  due after: <date>  next 7 days
//   p1 p2 p3 p4 (p1 is the most urgent)  no priority   #project  ##project (and its subprojects)  /section
//   @label  no labels  subtask  recurring  search: text   course: <name>  school (has a course)  all
//   &  |  !  ( )   and  ,  to show separate lists ("today, overdue").  ! binds tightest, then &, then |.
//
// A date is anything quick add reads ("fri", "jan 27", "in 3 days", "2026-12-01"). A term runs to the next operator, so
// "#Big project & p1" and "search: buy milk" need no quotes (quotes work too). Names are matched without regard to
// case. Only open tasks are looked at, and tasks in archived projects are left out. A task with no date matches none of
// the date terms except "no date" and "!" of them. Pure functions: pass `today` (a day key) and the lists the filter
// refers to.
import { addDays } from './habits.js';
import { dayFromPhrase } from './quickadd.js';

const NO_DATE = '9999-12-31';
const HELP = 'Try "today", "overdue", "p1", "#project", "@label" or "search: words".';

// ---------------------------------------------------------------- reading a query

// Splits on & | ! ( ) , outside quotes. Pieces are { op } or { text, at }.
function pieces(query) {
  const out = [];
  let text = '', at = 0, quoted = false;
  const flush = () => { if (text.trim()) out.push({ text: text.trim(), at: at + text.length - text.trimStart().length }); text = ''; };
  for (let i = 0; i < query.length; i++) {
    const c = query[i];
    if (c === '"') quoted = !quoted;
    if (!quoted && '&|!(),'.includes(c)) { flush(); out.push({ op: c, at: i }); at = i + 1; continue; }
    if (!text) at = i;
    text += c;
  }
  flush();
  return out;
}

const unquote = (s) => s.trim().replace(/^"(.*)"$/, '$1').trim();

// One term, from its text, or an error.
function term(raw, today) {
  const t = raw.toLowerCase().replace(/\s+/g, ' ').trim();
  let m;
  if (t === 'today') return { t: 'date', is: today };
  if (t === 'tomorrow') return { t: 'date', is: addDays(today, 1) };
  if (t === 'yesterday') return { t: 'date', is: addDays(today, -1) };
  if (t === 'overdue' || t === 'over due') return { t: 'before', day: today };
  if (t === 'no date' || t === 'no due date') return { t: 'nodate' };
  if (t === 'all' || t === 'view all') return { t: 'all' };
  if (t === 'subtask') return { t: 'subtask' };
  if (t === 'recurring') return { t: 'recurring' };
  if (t === 'no labels' || t === 'no label') return { t: 'nolabels' };
  if (t === 'no priority') return { t: 'priority', n: 1 };
  if (t === 'school') return { t: 'school' };
  if ((m = t.match(/^p([1-4])$/))) return { t: 'priority', n: 5 - Number(m[1]) };
  if ((m = t.match(/^next (\d{1,3}) days?$/))) return { t: 'range', from: today, to: addDays(today, Number(m[1])) };

  const dateArg = (label, arg, make) => {
    const day = dayFromPhrase(arg, today);
    if (!day) return { error: arg.trim() ? `I couldn't read the date "${arg.trim()}" in "${label}".` : `"${label}" needs a date, for example "${label} fri".` };
    return make(day);
  };
  if ((m = raw.match(/^\s*date\s*:\s*(.*)$/i))) return dateArg('date:', m[1], (day) => ({ t: 'date', is: day }));
  if ((m = raw.match(/^\s*(?:due\s+)?before\s*:\s*(.*)$/i))) return dateArg('due before:', m[1], (day) => ({ t: 'before', day }));
  if ((m = raw.match(/^\s*(?:due\s+)?after\s*:\s*(.*)$/i))) return dateArg('due after:', m[1], (day) => ({ t: 'after', day }));
  if ((m = raw.match(/^\s*search\s*:\s*(.*)$/i))) {
    const text = unquote(m[1]);
    return text ? { t: 'search', text: text.toLowerCase() } : { error: '"search:" needs some words, for example "search: buy milk".' };
  }
  if ((m = raw.match(/^\s*course\s*:\s*(.*)$/i))) {
    const text = unquote(m[1]);
    return text ? { t: 'course', text: text.toLowerCase() } : { error: '"course:" needs a course name, for example "course: algorithms".' };
  }
  if ((m = raw.match(/^\s*##\s*(.+)$/))) return { t: 'project', name: unquote(m[1]).toLowerCase(), sub: true };
  if ((m = raw.match(/^\s*#\s*(.+)$/))) return { t: 'project', name: unquote(m[1]).toLowerCase(), sub: false };
  if ((m = raw.match(/^\s*\/\s*(.+)$/))) return { t: 'section', name: unquote(m[1]).toLowerCase() };
  if ((m = raw.match(/^\s*@\s*(\S+)$/))) return { t: 'label', name: m[1].toLowerCase() };
  if (/^@/.test(raw.trim())) return { error: 'A label name has no spaces. Join labels with & or |.' };
  const day = dayFromPhrase(raw.trim(), today);
  if (day) return { t: 'date', is: day };
  return { error: `I don't know "${raw.trim()}". ${HELP}` };
}

// Reads a query. { ok: true, lists: [ast, ...] } (one per comma-separated list), or { ok: false, error, at }.
export function parseFilter(query, { today } = {}) {
  const ps = pieces(String(query ?? ''));
  if (!ps.length) return { ok: false, error: `Type a filter. ${HELP}`, at: 0 };
  let i = 0;
  const fail = (error, at) => { throw Object.assign(new Error(error), { at }); };
  const peek = () => ps[i];
  const atom = () => {
    const p = ps[i];
    if (!p) fail('The filter ends too soon. Something is missing after the last & | ! or (.', String(query).length);
    if (p.op === '(') {
      i++;
      const inner = or();
      if (!peek() || peek().op !== ')') fail('Missing ")".', p.at);
      i++;
      return inner;
    }
    if (p.op) fail(`I didn't expect "${p.op}" here.`, p.at);
    i++;
    const r = term(p.text, today);
    if (r.error) fail(r.error, p.at);
    return r;
  };
  const not = () => {
    if (peek()?.op === '!') { i++; return { t: 'not', of: not() }; }
    return atom();
  };
  const and = () => {
    const parts = [not()];
    while (peek()?.op === '&') { i++; parts.push(not()); }
    return parts.length === 1 ? parts[0] : { t: 'and', of: parts };
  };
  function or() {
    const parts = [and()];
    while (peek()?.op === '|') { i++; parts.push(and()); }
    return parts.length === 1 ? parts[0] : { t: 'or', of: parts };
  }
  try {
    const lists = [];
    while (i < ps.length) {
      if (peek().op === ',') { i++; continue; }          // empty lists are skipped
      lists.push(or());
      if (i < ps.length && peek().op !== ',') fail(peek().op === ')' ? 'Too many ")".' : `I didn't expect "${peek().op ?? peek().text}" here.`, peek().at);
    }
    if (!lists.length) return { ok: false, error: `Type a filter. ${HELP}`, at: 0 };
    return { ok: true, lists };
  } catch (e) {
    if (e.at === undefined) throw e;
    return { ok: false, error: e.message, at: e.at };
  }
}

// ---------------------------------------------------------------- running it

const dateOf = (t) => (t.due_date && t.due_date !== NO_DATE ? t.due_date : null);

// Tasks in order: by date (none last), then time, then priority (urgent first), then their place in the list.
export function sortTasks(tasks) {
  const day = (t) => dateOf(t) ?? '9999-99-99';
  return [...tasks].sort((a, b) => (day(a) < day(b) ? -1 : day(a) > day(b) ? 1 : 0)
    || (a.due_time ?? '') .localeCompare(b.due_time ?? '')
    || (b.priority ?? 1) - (a.priority ?? 1) || (a.position ?? 0) - (b.position ?? 0) || a.id - b.id);
}

// Turns a parsed filter into a function on tasks. ctx: { projects, sections, labels, courses } (lists of {id, name,
// parent_id?}), looked up by name once.
function compile(ast, ctx) {
  const lower = (s) => String(s ?? '').toLowerCase();
  const byName = (list, name) => (list ?? []).filter((x) => lower(x.name) === name).map((x) => x.id);
  const withSubs = (ids) => {
    const all = new Set(ids);
    for (let grew = true; grew;) {
      grew = false;
      for (const p of ctx.projects ?? []) if (p.parent_id != null && all.has(p.parent_id) && !all.has(p.id)) { all.add(p.id); grew = true; }
    }
    return all;
  };
  const go = (n) => {
    switch (n.t) {
      case 'and': { const fs = n.of.map(go); return (t) => fs.every((f) => f(t)); }
      case 'or': { const fs = n.of.map(go); return (t) => fs.some((f) => f(t)); }
      case 'not': { const f = go(n.of); return (t) => !f(t); }
      case 'all': return () => true;
      case 'date': return (t) => dateOf(t) === n.is;
      case 'before': return (t) => dateOf(t) !== null && dateOf(t) < n.day;
      case 'after': return (t) => dateOf(t) !== null && dateOf(t) > n.day;
      case 'range': return (t) => dateOf(t) !== null && dateOf(t) >= n.from && dateOf(t) <= n.to;
      case 'nodate': return (t) => dateOf(t) === null;
      case 'priority': return (t) => (t.priority ?? 1) === n.n;
      case 'project': {
        const inbox = n.name === 'inbox';
        const ids = n.sub ? withSubs(byName(ctx.projects, n.name)) : new Set(byName(ctx.projects, n.name));
        return (t) => (inbox ? t.project_id == null : ids.has(t.project_id));
      }
      case 'section': { const ids = new Set(byName(ctx.sections, n.name)); return (t) => t.section_id != null && ids.has(t.section_id); }
      case 'label': { const ids = new Set(byName(ctx.labels, n.name)); return (t) => (t.labels ?? []).some((l) => ids.has(l)); }
      case 'nolabels': return (t) => !(t.labels ?? []).length;
      case 'subtask': return (t) => t.parent_id != null;
      case 'recurring': return (t) => Boolean(t.recurrence);
      case 'search': return (t) => lower(t.title).includes(n.text) || lower(t.notes).includes(n.text);
      case 'course': {
        const ids = new Set((ctx.courses ?? []).filter((c) => lower(c.name).includes(n.text)).map((c) => c.id));
        return (t) => t.course_id != null && ids.has(t.course_id);
      }
      case 'school': return (t) => t.course_id != null;
      default: throw new Error(`unknown filter node ${n.t}`);
    }
  };
  return go(ast);
}

// Runs a query over tasks. ctx: { today, tasks, projects, sections, labels, courses }.
// Returns { ok: true, lists: [[task, ...], ...], tasks } (tasks: every list joined, each task once) or { ok: false, error, at }.
export function runFilter(query, ctx) {
  const parsed = parseFilter(query, { today: ctx.today });
  if (!parsed.ok) return parsed;
  const archived = new Set((ctx.projects ?? []).filter((p) => p.archived).map((p) => p.id));
  const open = (ctx.tasks ?? []).filter((t) => !t.done && !archived.has(t.project_id));
  const lists = parsed.lists.map((ast) => sortTasks(open.filter(compile(ast, ctx))));
  const seen = new Set();
  const tasks = lists.flat().filter((t) => !seen.has(t.id) && seen.add(t.id));
  return { ok: true, lists, tasks };
}
