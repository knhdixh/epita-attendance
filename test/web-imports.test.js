// Every function the website's modules call on another module (`import * as x` then `x.name(...)`, or
// `import { name }`) is exported by that module. Merges have dropped one before (habits-ui's todayCounts), which only
// showed as "x.name is not a function" in the browser. The UI modules touch the DOM when imported, so this reads the
// source instead of importing it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const dir = new URL('../public/', import.meta.url).pathname;
const read = (f) => fs.readFileSync(path.join(dir, f), 'utf8');

function exportsOf(file) {
  const s = read(file), out = new Set();
  for (const m of s.matchAll(/export\s+(?:async\s+)?(?:function\*?|class)\s+([A-Za-z_$][\w$]*)/g)) out.add(m[1]);
  // export const A = …, B = …
  for (const m of s.matchAll(/export\s+(?:const|let)\s+([^;]*)/g)) {
    for (const d of m[1].matchAll(/(?:^|,)\s*([A-Za-z_$][\w$]*)\s*=/g)) out.add(d[1]);
  }
  for (const m of s.matchAll(/export\s*\{([^}]*)\}/g)) {
    m[1].split(',').map((x) => x.trim().split(/\s+as\s+/).pop()).filter(Boolean).forEach((n) => out.add(n));
  }
  return out;
}

test('the website modules only call functions their imports export', () => {
  const missing = [];
  for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.js'))) {
    const s = read(f);
    for (const [, ns, from] of s.matchAll(/import\s+\*\s+as\s+(\w+)\s+from\s+'\.\/([\w-]+\.js)'/g)) {
      const ex = exportsOf(from);
      for (const [, name] of s.matchAll(new RegExp(`(?<![\\w.$])${ns}\\.(\\w+)\\s*\\(`, 'g'))) {
        if (!ex.has(name)) missing.push(`${f}: ${ns}.${name}() is not exported by ${from}`);
      }
    }
    for (const [, names, from] of s.matchAll(/import\s*\{([^}]*)\}\s*from\s*'\.\/([\w-]+\.js)'/g)) {
      const ex = exportsOf(from);
      for (const n of names.split(',').map((x) => x.trim().split(/\s+as\s+/)[0]).filter(Boolean)) {
        if (!ex.has(n)) missing.push(`${f}: { ${n} } is not exported by ${from}`);
      }
    }
  }
  assert.deepEqual([...new Set(missing)], []);
});
