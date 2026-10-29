# Native Android overnight report

Run started 2026-10-29. Reviewer/orchestrator: parent Codex session. Implementation workers: `gpt-6.1-terra` through `codex exec`. No push, deployment, remote migration or change to main is authorised.

`native/night` was created from `main` at `e97f6ca`. Existing Phase 2b task 1 and the owner's overnight instructions on `native/phase-2b` are preserved. That branch descends from the same base. No phase has been merged yet.

| Phase | Verdict | Fix rounds | Merge | Independent checks |
|---|---|---|---|---|
| 2b | In progress: Logic ports and endpoint audit committed; Compose/screenshot worker running | 0 | — | Pending |
| 3, 4, 5, 6, 7, 8, 10, 11, 12, 13 | Pending | — | — | Pending |
| 9 | Skipped by instruction | — | — | Owner/device release work |

## Screenshot index

Phase 2b worker is recording to `android-native/app/src/test/screenshots/`: phone411x891 and tablet840x1000, light/dark, sign-in/loading/placeholder/dialog; main screens also labelled font1/font2. Current images are interim and not accepted.

## Needs a device

- Real passkey and Google Custom Tab sign-in.
- Exact-alarm behaviour in Doze and promoted ongoing notifications.
- Update over the existing TWA and import of its running timer.
- Widget behaviour on a real launcher.

## Unverified

- Independent baseline on the existing Phase 2b task 1 work: `npm test` 103/104 (known countdown failure); `./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug` BUILD SUCCESSFUL, 31 JVM tests, 0 failures/errors/skips. Final Phase 2b checks remain pending.
- Managed-device and local server end-to-end checks are introduced in Phase 3.

## Decisions and owner questions

- Preserve the existing Phase 2b branch rather than reset already committed work.
- The known countdown time-of-day assertion may remain at 103/104 as explicitly allowed by `overnight-run.md`; it does not stop this run. A proper clock-pinning fix is allowed if that test is touched.
- No owner response is required to continue.

## Worker runs

- Phase 2b initial run completed DTO/fixture work in `e533c9d` and log in `9aa1a9f`; explicitly reported remaining scope rather than claiming phase completion.
- Habits split run committed `b958c40`: port and JS-generated golden tests. Worker performed deliberate golden mutation then restored it. Independent partial review: `./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug` passed with 32 JVM tests, 0 failures/errors/skips. Final whole-phase review remains pending.
- Countdowns split worker initially exited after inspection with no changes or checks; resumed the same Codex session `01a189e4-ee11-7593-a1e2-468341474267` with instruction to implement. No failure review rounds consumed.
- Countdowns completed in `ac1eba9` (log `d2eea23`); independent unit/lint/assemble check passed with 33 JVM tests, 0 failures/errors/skips.
- CalendarMath completed in `9545857`; independent unit/lint/assemble check passed with 34 JVM tests, 0 failures/errors/skips. Both split workers performed golden mutation checks.
- Endpoint audit completed: missing methods in `bf7f58f`, audit corrections in `b84546c`, inventory in `91cf2b0` (partial log `58910c2`). Corrected flat calendar responses, GCal resync fields, explicit-null partial writes and section 4 only. Independent full unit/lint/assemble check after restored review inputs passed: 35 JVM tests, 0 failures/errors/skips.
- Compose/Roborazzi infrastructure worker running, Codex session `01a189ee-7fdc-7de3-93af-c741d9a82882` (shell handle 50376; log `/tmp/epita-native-night/phase-2b-ui-worker.log`). Interim native screenshots exist. Reviewer inspected initial captures: diagnostic dumps were replaced with real images; current sign-in font1/font2 pairs are byte-identical, so 200% coverage is unproven. Final matrix review and independent verify run remain pending.
- Reviewer independently reproduced a Habits numeric-coercion mismatch with JS-computed temporary expectations: `weekly n:"true"` returned an object rather than null. Targeted test failed as expected; test resource files restored byte-for-byte and full check passed. Requires a permanent regression and fix during the whole-phase review; phase remains unmerged.
- Independent native/night baseline: `./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug` passed, 31 JVM tests, 0 failures/errors/skips. Secondary worktree initially lacked SDK configuration; configured only the existing sdk.dir locally and reran successfully.
- Bootstrap report/instructions documentation check on native/night: `npm test` 103/104 with known countdown assertion; initial secondary worktree attempt lacked node_modules, resolved by sharing the existing dependency installation. No dependency or app code changed for this.

Current position: Phase 2b UI worker remains live, debugging Compose interaction checks and screenshot font-scale evidence; no phase has passed review or merged yet.
