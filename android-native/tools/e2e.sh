#!/bin/bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
mode=${1:-device}
if [[ "$mode" != device && "$mode" != --smoke ]]; then echo 'Usage: e2e.sh [--smoke]' >&2; exit 2; fi
# Refuse to take over any server already listening on the chosen port.
for port in 8787 8788; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t | grep -q .; then echo "Port $port is already in use" >&2; exit 1; fi
done
tmp=$(mktemp -d "${TMPDIR:-/tmp}/a80-e2e.XXXXXXXX")
server=
cleanup() {
  if [[ -n "$server" ]]; then kill -TERM -- "-$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
  rm -rf "$tmp"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
touch "$tmp/.a80-e2e-isolated"
cp "$root/package.json" "$root/wrangler.jsonc" "$tmp/"
sed '/^[[:space:]]*DEV_LOGIN=/d' "$root/.dev.vars.example" > "$tmp/.dev.vars"
printf '\nDEV_LOGIN=1\n' >> "$tmp/.dev.vars"
cp "$root/android-native/tools/e2e-proxy.mjs" "$tmp/proxy.mjs"
for path in node_modules src public migrations; do ln -s "$root/$path" "$tmp/$path"; done
(cd "$tmp" && exec python3 -c 'import os; os.setsid(); os.execvp("sh", ["sh", "-c", "npm run dev -- --port 8788 & node proxy.mjs & wait"])') >"$tmp/server.log" 2>&1 &
server=$!
ready=0
for ((i=0; i<60; i++)); do
  if ! kill -0 "$server" 2>/dev/null; then cat "$tmp/server.log" >&2; exit 1; fi
  if curl --max-time 2 -fsS http://localhost:8787/auth/config >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
if [[ "$ready" != 1 ]]; then cat "$tmp/server.log" >&2; echo 'Local server did not become ready' >&2; exit 1; fi
listener=$(lsof -nP -iTCP:8787 -sTCP:LISTEN -t | head -1)
listener_group=$(ps -o pgid= -p "$listener" | tr -d ' ')
if [[ "$listener_group" != "$server" ]]; then echo 'Port 8787 is not owned by this run' >&2; exit 1; fi
node "$root/android-native/tools/seed-local.mjs" "$tmp"
if [[ "$mode" == --smoke ]]; then echo 'Local HTTP and seed smoke passed; no device tests ran.'; exit 0; fi
touch "$tmp/device-started"
(cd "$root/android-native" && ./gradlew pixel6Api35ProdDebugAndroidTest -PbaseUrl=http://10.0.2.2:8787 --rerun-tasks --no-daemon)
python3 - "$root/android-native/app/build/outputs/androidTest-results/managedDevice" "$tmp/device-started" <<'PYTEST'
import pathlib, sys, xml.etree.ElementTree as ET
root, marker = map(pathlib.Path, sys.argv[1:])
seen = set()
for path in root.rglob('*.xml'):
    if path.stat().st_mtime < marker.stat().st_mtime: continue
    for case in ET.parse(path).iter('testcase'):
        if case.get('classname', '').endswith('LocalServerTest') and not any(child.tag in ('failure', 'error', 'skipped') for child in case):
            seen.add(case.get('name'))
required = {'a_signInAndLiveShell', 'b_cachedOfflineProcessRestart'}
if not required <= seen:
    sys.exit(f'Missing passing instrumented results: {sorted(required - seen)}')
print('Both local-server instrumented tests passed.')
PYTEST
