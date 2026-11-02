#!/usr/bin/env bash
# Runs one phase of docs/native-android-plan.md in a fresh, headless Claude Code session, as cheaply as is sensible:
# Sonnet at medium effort, a fresh context per phase, a hard dollar cap, and /goal so it keeps going until the checks
# pass and the progress log is committed. Never pushes, merges or deploys (denied below, as AGENTS.md says).
#
#   android-native/tools/run-phase.sh 5            # Phase 5 on native/phase-5 (created from the current branch)
#   BUDGET=20 MODEL=opus android-native/tools/run-phase.sh 7
#   android-native/tools/run-phase.sh 5 --resume   # carry on the last Phase 5 session if it stopped early
#
# Output: android-native/build/phase-runs/phase-<n>.json (cost, session id) and .log (the run, streamed).
set -euo pipefail

phase="${1:?usage: run-phase.sh <phase> [--resume]}"
mode="${2:-}"
MODEL="${MODEL:-sonnet}"
EFFORT="${EFFORT:-medium}"
BUDGET="${BUDGET:-15}"

root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$root"
out="android-native/build/phase-runs"
mkdir -p "$out"
branch="native/phase-$phase"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "The working tree has uncommitted changes. Commit or stash them first." >&2
  exit 1
fi
if git show-ref --quiet "refs/heads/$branch"; then git switch -q "$branch"; else git switch -q -c "$branch"; fi
echo "Phase $phase on $branch with $MODEL/$EFFORT, budget \$$BUDGET."

# What the agent must never do, enforced by Claude Code whatever the prompt says.
deny=(
  "Bash(git push *)" "Bash(git merge *)" "Bash(git rebase *)" "Bash(git reset --hard *)"
  "Bash(npm run deploy*)" "Bash(wrangler deploy*)" "Bash(npx wrangler deploy*)"
  "Bash(* --remote*)" "Bash(ssh *)" "Bash(gh *)"
  "Read(.dev.vars)" "Read(android/keystore.properties)" "Read(android/signing.txt)"
)

prompt="Implement Phase $phase of docs/native-android-plan.md on the branch $branch (already checked out).
Read AGENTS.md first, then the plan's sections A, B, 0, 8 and 9 and the whole of Phase $phase, then the end of
docs/native-android-progress.md (the last few entries only; it is long). Build from the existing code in
android-native/; match its patterns (ShellViewModel actions, DataRepository.mutate, Roborazzi screenshot tests).
Work in small steps and commit after each numbered task (subject 'Android: …', no server and Android changes in one
commit). Keep Gradle in the foreground and only print its tail; read test XML for failures.
Before the last commit run: npm test; and in android-native with ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk:
./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon
then tools/e2e.sh. Append a 'Phase $phase' entry to docs/native-android-progress.md with what you did per task and
commit hashes, the exact commands and pass/fail counts, deviations, and what is Unverified (needs a real device).
Never push, merge, deploy, use --remote, edit an existing migration, or weaken/skip a test. If something can't be done,
write it in the log and stop."

goal="/goal Phase $phase of docs/native-android-plan.md is implemented on $branch; in this session the full Gradle check
(testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug) printed
BUILD SUCCESSFUL with 0 test failures, npm test passed, and a Phase $phase entry in docs/native-android-progress.md was
committed (git log shows it). Or: the agent wrote in the log that it is blocked and why."

args=(-p --model "$MODEL" --effort "$EFFORT" --max-budget-usd "$BUDGET"
      --permission-mode auto --permission-prompts none
      --append-system-prompt "$prompt"
      --disallowedTools "${deny[@]}"
      --output-format stream-json --verbose)

if [[ "$mode" == "--resume" ]]; then
  sid="$(jq -r 'select(.type=="result") | .session_id' "$out/phase-$phase.log" | tail -1)"
  [[ -n "$sid" && "$sid" != "null" ]] || { echo "No earlier session for Phase $phase." >&2; exit 1; }
  claude "${args[@]}" --resume "$sid" "Carry on with Phase $phase until the goal is met." | tee -a "$out/phase-$phase.log" >/dev/null
else
  claude "${args[@]}" "$goal" | tee "$out/phase-$phase.log" >/dev/null
fi

# The summary line: cost, turns, whether it ended in an error.
jq -c 'select(.type=="result") | {subtype, is_error, num_turns, total_cost_usd, session_id}' "$out/phase-$phase.log" | tail -1 | tee "$out/phase-$phase.json"
git log --oneline -5
