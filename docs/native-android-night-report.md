# Native Android overnight report

Run started 2026-10-29; current date 2026-10-30. Parent Codex reviews and orchestrates; implementation workers are `gpt-6.1-terra` through `codex exec`. `native/night` was created from main `e97f6ca`; main remains unchanged. No push, deployment or remote migration.

| Phase | Verdict | Fix rounds | Merge commit | Independent checks |
|---|---|---|---|---|
| 2b | PASS | 2 | `98b2002` | npm 103/104; 62/62 Android; lint/debug APK and full screenshot verification pass |
| 3 | In progress: Terra data/scope/background worker on native/phase-3 | 0 | — | Worker checks running; independent review pending |
| 4, 5, 6, 7, 8, 10, 11, 12, 13 | Pending, in that order | — | — | Pending |
| 9 | Skipped by instruction | — | — | Release/TWA replacement belongs to owner/device |

## Test counts and accepted evidence

Phase 2b: 23 golden methods; 11 ordinary JVM methods; 1 existing Robolectric ViewModel method; 3 Compose interaction methods; 24 screenshot methods/PNGs; 0 instrumented tests. Total 62, zero failures/errors/skips. The 24 screenshots were all inspected by the reviewer. A deliberately corrupted expected PNG correctly failed targeted verification; restoring the original bytes and running full verification passed. JS golden regeneration produced no tracked changes.

After merge, on native/night: `./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug verifyRoborazziProdDebug` passed in 34s, all 62 tests. Reviewed Android source and merged Android source are identical. The web suite remains at its explicitly allowed baseline: 103/104, only the time-of-day countdown assertion (`test/habits.test.js:222`, expected 10, got 11).

Phase 2b review: `docs/native-android-review-2b.md`, including original FIX, round-1 FIX and round-2 PASS. App/test/screenshot commit `af8a72c`; worker log `4fe80d7`; append-only implementation detail in `docs/native-android-progress.md`. Local review logs live under `/tmp/epita-native-night/`.

## Screenshot index

`android-native/app/src/test/screenshots/`: 24 accepted PNGs. Phone 411×891 dp and tablet 840×1000 dp; light/dark; sign-in, loading, authenticated placeholder and create-account dialog. Both main screens additionally at real font scale 2.0, with measured growth and action reachability assertions. Diagnostic dumps and incorrectly labelled identical font-scale images were replaced before acceptance.

## Needs a device

- Real passkey and Google Custom Tab sign-in.
- Exact-alarm timing in Doze and promoted ongoing notifications.
- Updating over the existing TWA and importing its running timer.
- Widget behaviour on a real launcher.
- Dialog window dimming: Robolectric full-window captures show the real dialog surface/content but do not render the system dim layer.

## Unverified

- Phase 3 managed-device and local-server end-to-end harness has not yet been introduced or run.
- No physical-device or release/TWA replacement claim is made.

## Decisions and owner questions

- Preserved the pre-existing Phase 2b task commit and branch; they descend from the same main base as native/night.
- Allowed the documented 103/104 npm baseline exactly as overnight-run.md permits. A proper clock-pinning fix is permitted if that test is touched.
- Split Phase 2b into focused worker runs; reviewed the whole phase before merge.
- The real account dialog uses responsive explicit width constraints (24dp side padding, max 560dp, platform default width disabled). This preserves the real input and actions while avoiding the width-qualifier dialog idle issue described in https://github.com/robolectric/robolectric/issues/8460.
- Phase 3 will be split into data/scope/background work, shell/navigation/search with screenshots, then the local-server/managed-device harness. Future-feature instrumented actions are added when their phases implement them; no skipped or fake tests.
- No owner response is needed to continue.

## Phase 3 worker continuation

- Data repository task committed as `9d49090`, Count scope as `c069268`, hourly background refresh as `c2746b8` on `native/phase-3`. Nothing from Phase 3 is merged.
- The initial worker terminated at 00:52 Paris with an explicit CLI usage-limit error and a stated 03:41 reset. It left session-race hardening uncommitted; its last full checks reported 68 tests passing, which still needs independent review.
- At 03:45 Paris the reset time had passed. Resumed the same Codex session `01a18a0d-df28-7b51-a904-a2a5ee8e9b81`; live shell handle 14272 and log `/tmp/epita-native-night/phase-3-data-resume-worker.log`. It is reading and completing the preserved changes, including stronger atomic-write and race checks. No alternative worker model or quota bypass is used.

Current position: Phase 2b merged and native/night verified building; Phase 3 data/scope/background worker resumed after the CLI quota reset. Shell/search/screenshots and the local-device harness follow; later phases remain pending.
