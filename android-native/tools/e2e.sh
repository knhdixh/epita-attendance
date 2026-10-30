#!/bin/bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
mode=${1:-device}
if [[ "$mode" != device && "$mode" != --smoke ]]; then echo 'Usage: e2e.sh [--smoke]' >&2; exit 2; fi
# Refuse to take over any server already listening on the chosen port.
if lsof -nP -iTCP:8787 -sTCP:LISTEN -t | grep -q .; then echo 'Port 8787 is already in use' >&2; exit 1; fi
tmp=$(mktemp -d "${TMPDIR:-/tmp}/a80-e2e.XXXXXXXX")
server=
cleanup() {
  if [[ -n "$server" ]]; then kill -TERM -- "-$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
  rm -rf "$tmp"
}
trap cleanup EXIT INT TERM
cp "$root/package.json" "$root/wrangler.jsonc" "$tmp/"
cp "$root/.dev.vars.example" "$tmp/.dev.vars"
for path in node_modules src public migrations; do ln -s "$root/$path" "$tmp/$path"; done
(cd "$tmp" && exec python3 -c 'import os; os.setsid(); os.execvp("npm", ["npm", "run", "dev"])') >"$tmp/server.log" 2>&1 &
server=$!
ready=0
for ((i=0; i<60; i++)); do
  if ! kill -0 "$server" 2>/dev/null; then cat "$tmp/server.log" >&2; exit 1; fi
  if curl -fsS http://localhost:8787/auth/config >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
if [[ "$ready" != 1 ]]; then cat "$tmp/server.log" >&2; echo 'Local server did not become ready' >&2; exit 1; fi
node "$root/android-native/tools/seed-local.mjs" "$tmp"
if [[ "$mode" == --smoke ]]; then echo 'Local HTTP and seed smoke passed; no device tests ran.'; exit 0; fi
(cd "$root/android-native" && ./gradlew pixel6Api35ProdDebugAndroidTest -PbaseUrl=http://10.0.2.2:8787 --no-daemon)
echo 'Incomplete: Phase 3 instrumented tests have not been implemented.' >&2
exit 1
