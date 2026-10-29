# Overnight run: build the native app unattended

The owner is asleep and has no phone plugged in. You (the session reading this) are the **reviewer and
orchestrator**. A cheaper model writes the code. Claude audits the whole result in the morning. Work through
the night without asking questions: nobody will answer. When the plan leaves something open, choose the option
closest to the website's behaviour, write the choice in the progress log, and go on.

Read first: `AGENTS.md`, then `docs/native-android-plan.md` (sections A, B, 0, 8 and 9 in full, plus the phase
you're on), then the end of `docs/native-android-progress.md`.

## Roles

- **Worker:** `gpt-6.1-terra`, run from the shell, one phase (or one fix round) per run:
  ```
  codex exec -m gpt-6.1-terra --dangerously-bypass-approvals-and-sandbox -C /Users/khanh.dang/Projects/epita_calendar "<prompt>"
  ```
  The prompt always says: which phase, which branch, "read AGENTS.md, docs/overnight-run.md and the plan sections
  A, B, 0, 8, 9 and Phase <n>", and for a fix round, the review file to fix. The worker commits and stops; it
  never merges.
- **You (reviewer):** don't write app code yourself. You plan the phase split, start workers, review, run the
  checks, and merge passing phases into the night branch. Small edits to the review files, the progress log and
  the night report are yours.

## Branches

- `native/night` is created once from `main`. **Never commit to, merge into or reset `main`.** Never push
  anything.
- Each phase: the worker creates `native/phase-<n>` from the current `native/night`. After a PASS you run
  `git switch native/night && git merge --no-ff native/phase-<n>`. This replaces plan section B's "the owner
  merges", for tonight only.

## Phase order for tonight

2b, 3, 4 (with 8.3), 5, 6, 7 (with 8.4 and 8.5), 8, then 10, 11, 12, 13. **Skip Phase 9** (release and
replacing the TWA): the owner and Claude do it with a device. A big phase may be split into several worker runs
(for example 4a: Today and Attendance, 4b: …). Each run ends in a commit, and the review still covers the whole
phase.

## Review loop for each phase

1. Read the phase in the plan, the worker's progress-log entry and `git diff native/night...native/phase-<n>`.
2. **Run the checks yourself.** Never trust the log: `npm test`, then in `android-native/`
   `./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug`, plus the device checks below once they
   exist. Compare the counts with what the log claims.
3. Look at every new or changed screenshot (see "Proving it works without a phone"). Check layout, wording
   copied from the website, the colours in plan section 6, empty states, dark theme, and nothing cut off at 200%
   font size.
4. Write `docs/native-android-review-<n>.md`. Verdict **PASS** or **FIX**, then numbered findings (file:line, what's
   wrong, what to do), most important first. Check scope (nothing missing from the phase, nothing extra), tests
   that really test the behaviour, golden tests generated from JS (not hand-written), no faked or skipped tests,
   no secrets, no edits to existing migrations, D1 query plans for new server queries (see
   `test/habits.test.js` for the `EXPLAIN QUERY PLAN` pattern), and that the website still works.
5. On FIX, start a worker fix round on the same branch. Allow **at most 3 fix rounds**. If it still fails, mark
   the phase **BLOCKED** in the night report with the reason, and **stop the run** (later phases depend on
   earlier ones). Don't lower the bar to get past it.

## Proving it works without a phone

Each phase adds whichever of these apply. Phase 2b sets up 2 and 3, and Phase 3 sets up 4 and 5. If one can't
run on this Mac (for example, the emulator won't start), record it as **Unverified** with the exact error, keep
the tests in the code, and carry on with the others. Don't stall on it.

1. **JVM unit and golden tests** (already there).
2. **Compose UI tests on Robolectric** for every screen: empty state, populated state, and the main actions.
3. **Screenshot tests with Roborazzi** for every screen and sheet: light and dark, phone (411×891 dp) and tablet
   (840 dp wide), and font scale 2.0 for the main screens. Record them to
   `android-native/app/src/test/screenshots/` and commit the PNGs. Verify mode must pass in CI-style runs. Add
   Roborazzi and `androidx.test` (Espresso, UI Automator) to the version catalog. They are allowed despite rule
   0.7.
4. **Instrumented tests on a Gradle Managed Device** (`aosp-atd` image, API 35, Pixel 6 profile, headless:
   `./gradlew pixel6Api35ProdDebugAndroidTest`, with a debug variant pointed at `http://10.0.2.2:8787`). Cover:
   dev sign-in, each top-level screen loading real data, marking attendance, adding and completing homework, the
   focus timer (start, the alarm firing, the notification and its actions), notifications posted for check-ins and
   reminders, the outbox (offline change, back online, sent once), and the widget's snapshot.
5. **End to end against a local server**: `android-native/tools/e2e.sh` copies `.dev.vars.example` into a temp
   `.dev.vars` if needed (`DEV_LOGIN=1`), starts `npm run dev` (local D1 only), seeds it with
   `android-native/tools/seed-local.mjs` (a semester with courses, groups, homework, habits, countdowns, focus
   sessions; local `wrangler d1 execute --local` only), runs step 4, and stops the server.

What can't be proven here (real passkeys, Google sign-in in a Custom Tab, exact-alarm timing in Doze, installing
over the TWA, the widget on a real launcher) goes in the night report under **Needs a device**.

## Hard rules (on top of plan section B)

- Never deploy, never use `--remote`, never `wrangler login`, never push, never open PRs, never SSH. Cloudflare
  and GitHub credentials are disabled in this session's environment. If a command fails because of that, it's
  a command you shouldn't run.
- Local D1 (`.wrangler/state`) only. Don't delete `.wrangler/` or the `android/` keystore files.
- Don't edit `docs/native-android-plan.md`, except section 4 corrections that the plan allows, logged.
- Keep `npm test` at 103 of 104 or better. The known failure is the time-of-day countdown assertion; if you
  touch that test, fix it properly by pinning the clock.
- Keep each worker prompt self-contained. Workers don't see this conversation.

## Night report

Keep `docs/native-android-night-report.md` updated after every phase, committed on `native/night`. For each
phase: the verdict, how many fix rounds it took, the merge commit, and test counts by type (unit, golden,
Robolectric, screenshots, instrumented). Also keep: an index of screenshot folders, **Needs a device**,
**Unverified**, decisions you made that the plan left open, and open questions for the owner. The run is done
when every phase above is merged, or a phase is BLOCKED, or you run out of budget. In every case, leave
`native/night` building, with the report's last line saying where it stopped and why.
