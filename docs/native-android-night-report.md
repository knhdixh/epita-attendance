# Native Android overnight report

Run started 2026-10-29; current date 2026-10-30. Parent Codex reviews and orchestrates; implementation workers are `gpt-6.1-terra` through `codex exec`. `native/night` was created from main `e97f6ca`; main remains unchanged. No push, deployment or remote migration.

| Phase | Verdict | Fix rounds | Merge commit | Independent checks |
|---|---|---|---|---|
| 2b | PASS | 2 | `98b2002` | npm 103/104; 62/62 Android; lint/debug APK and full screenshot verification pass |
| 3 | PASS after round2; merge pending | 2 | — | Independent npm104/104; fresh244/244 Android, all83 tasks;136 PNG/full verify; real local smoke and2/2 managed-device e2e |
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

- Phase3 device/local-server harness now passes independently; actual browser/passkey/Google flow and physical-device behavior remain unverified.
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


## Phase 3 data and shell progress

- Resumed data worker completed as `4ecfaf6`: atomic rename failure protection, refresh/mutation ordering, conditional token clearing and session isolation, persisted scope tests. Worker reported 72 passing Android tests, lint, debug assembly and existing screenshot verification; npm 104/104. Reviewer partial Gradle command passed, but the worker subsequently replaced XML with targeted runs; no independently retained whole-phase count is claimed yet.
- Data cache limitation is documented: successful single-row mutations stay in memory until a full refresh writes the raw snapshot; offline restart before that refresh can show earlier data. Phase 11 must persist task mutations as it generalises the outbox. Device background execution remains unverified.
- Initial shell run produced only an unconnected URI parser and stopped without a commit. Resumed that session to complete the actual assignment; this is still initial implementation, no review fix rounds consumed.
- Shell task committed as `ebf369e`; navigation/shortcuts/deep-link handling and search are being completed. Live shell handle 63939, log `/tmp/epita-native-night/phase-3-shell-resume-worker.log`, Codex session `01a18ab9-3112-7c13-93bd-c8c3ae4c7d68`.
- Interim visual inspection found default Material purple navigation/card styling and poor 200% footer wrapping. Revalidate against final PNGs during the whole-phase review; no Phase 3 PASS or merge is claimed.


## Phase 3 shell and harness continuation

- Shell/navigation/search split committed `ebf369e`, `74dd6c4`, `cd51b84`; log `960d3f3`. Worker reports 218 passing Android tests, lint, debug assembly and unfiltered verification of 136 PNGs; npm 104/104. Task 5 is explicitly incomplete: recent searches, natural-language fallback and several calendar/attendance/habit/focus palette actions remain. New-item entries currently open read-only routes; real forms/controllers are not claimed.
- Reviewer has inspected all 68 phone PNGs at these source hashes; tablet review remains pending. Review manifest with filenames and SHA256: `/tmp/epita-native-night/phase3-image-review.json`; contact sheets `phase3-phone-sheet-00.jpg` through `11.jpg`, and tablet sheets `00` through `16`. Final phone tokens/flat rows and adaptive large-font navigation corrected the earlier purple/footer issues. Source review still needs pending-link offline lifecycle, NavHost architecture, device-locale formatting and stable list keys checked.
- Harness partial commit `d201d03`: managed-device profile plus isolated local server/seed smoke. Actual local HTTP acquisition succeeded for seeded data and cleanup left port8787 free. No instrumented tests were implemented in that run; normal e2e deliberately fails rather than claiming an empty test APK passes.
- Initial managed-device provisioning was interrupted while preparing Android Emulator37.2.13; this is NOT proof that the Mac cannot run it. Root checked that no provisioning process remained. Resumed harness session `01a18ad7-6fa6-7530-b9f9-76ed72d80572`, live shell handle67965, log `/tmp/epita-native-night/phase-3-e2e-resume-worker.log`, with real test implementation and a definitive provisioning attempt required.
- No Phase3 review verdict or fix rounds yet; initial implementation and review evidence remain incomplete. No Phase3 merge.

Current position: Phase2b merged and native/night verified building; Phase3 harness worker is live, completing real instrumented tests and device provisioning. Tablet screenshot review and whole-phase review/fixes remain; later phases are pending.


## Phase 3 reviewer continuation

- Reviewer visually inspected all 136 current PNGs (68 phone, 68 tablet); all contact sheets in `/tmp/epita-native-night/phase3-image-review.json` are now marked reviewed against their source SHA256. No new clipping was found in the tablet rail, scope, search or 200% main-screen captures. These are read-only shells; detailed Phase 4 screen functionality is not claimed.
- Managed-device provisioning completed: Android Emulator 37.2.13 and API 35 AOSP ATD ARM64 image installed; adb saw emulator-5554 online. The first actual two-test run failed its local-host assertion because a concurrent worker verification build replaced the local APK configuration with the production URL. The worker is resolving its check ordering; this is a real failing check, not an environment exemption or a passing e2e claim.
- Instrumented test source now exists, exercising dev PKCE callback, seeded top-level data, scope/search/internal links, and an Orchestrator process restart with cached data while offline. It remains uncommitted and under worker validation. The local seed now imports two 16-week calendars with shared lectures and group memberships through real local APIs.

Current position: Phase 2b remains the only merged phase, verified building on native/night. Phase 3 initial harness validation is still running; all current screenshots have been inspected, but independent final checks and the formal whole-phase review remain.


## Phase 3 quota interruption and explicit continuation

- Harness worker terminal handle67965 exited1 with an explicit usage-limit error and reset08:45. Its last device run did not complete: e2e6 reported discovery0/2, and no passing test result is claimed. Source changes remain preserved on native/phase-3; no new Phase3 merge or fix round.
- On the user's active-goal continuation, reviewer checked live time07:52UTC/08:52Paris (2026-10-30), past the stated reset. No live Codex/Gradle wrapper remained. Stopped only orphan local-server process group20976 (temporary a80-e2e.TyPTKybS) and its owned emulator21114; port8787 is free. Resumed the same Terra session after reset, live shell55539, log `/tmp/epita-native-night/phase-3-e2e-second-resume-worker.log`. This is continuation after quota reset, not another model or quota bypass.

Current position: Phase2b is the only accepted merge. Phase3 harness source and real test completion are still being finished by the resumed Terra worker; all136 current PNGs reviewed. Formal Phase3 verdict/fixes and later phases remain pending.


## Phase 3 initial whole-phase review: FIX

- Harness completed as `cb8444d`, worker terminal exit0; branch source clean before reviewer checks. Independent reviewer `npm test`:104/104. Reviewer Android unit/lint/app+test assembly/unfiltered screenshot verify passed21s,218 tests with0 failures/errors/skips:23 golden,25 ordinary JVM,2 other Robolectric,1 Robolectric ViewModel,31 Compose interaction/integration,136 screenshots. All136 source PNG hashes match the visually inspected manifest.
- Independent local smoke passed: real48-event semester, both group memberships/shared lecture and all seeded entity categories. Independent full `tools/e2e.sh` passed1m57s with69 tasks executed. Fresh managed-device XML has2 passing tests,0 failures/errors/skips: actual localPKCE/shell/scope/search/links and cached offline restart in a different process. Both8787/8788 free afterward. Browser launch is intercepted; actual Custom Tab login remains Needs a device. Reviewer logs `/tmp/epita-native-night/review-3-final-{npm,gradle}.log`, `review-3-e2e-{smoke,device}.log`.
- `docs/native-android-review-3.md` verdictFIX: finish palette/catalog/dispatch/recents/fallback contract, prevent cached offline link replay after Back/recreation, restore planned NavHost, device-locale dates, stable list identity and native-appropriate empty guidance. Future forms/controllers remain in Phases4/5, with preserved action payload seams required now. NoPhase3 merge.
- Latest native/night source independently checked again:62 tests,0 failures/errors/skips, lint/debug assembly/full verify passed10s (up-to-date tasks reused where applicable); source remains accepted Phase2b. main unchanged at e97f6ca.

Current position: Phase2b merged and native/night verified building; Phase3 initial review isFIX and first fix round is starting. Phase3 code/screenshot/harness evidence is recorded, noPhase3 merge; later phases remain pending.


## Phase 3 fix round 1 launched

- Review committed on native/night as `030b563`; read-only copy supplied to worker branch. First formal fix round is active in Terra session `01a18be5-6c40-7a50-b0e2-7d79644c733c`, live shell98198, log `/tmp/epita-native-night/phase-3-fix-1-worker.log`. No other implementation worker is running.
- Worker has started NavHost/back-stack changes, device-locale date formatting, stable rows and focused saved-state/date tests; palette catalog/actions work continues. These uncommitted changes have not been accepted. Reviewer will independently rerun checks and inspect every changed PNG after the worker commits and stops.

Current position: Phase2b is the only merged phase and native/night builds. Phase3 isFIX with round1 in progress; noPhase3 merge and later phases pending. Goal remains active.


## Phase 3 fix round 1 reviewer progress

- Terra worker handle98198 remains live on native/phase-3; no fix commit yet. Reviewer completed all changed-image inspection:84 distinct PNGs changed against cb8444d, with12 revised/new form captures additionally inspected after an earlier80-image pass. Final reviewed-hash union `/tmp/epita-native-night/fix1-current-reviewed-hashes.json` covers136 current PNGs; no pending hashes at last comparison. Contact manifests `fix1-image-review.json` and `fix1-extra-image-review.json`. Full-size spot checks resolved unclear contact-sheet impressions; no new clipping found.
- Worker reports225 JVM tests passing after navigation/date/action/serialization regressions and two passing fresh managed-device cases including a rejected app session, real server401/devPKCE and pending-session delivery. These are worker results until reviewer repeats the final checks after commit/stop. Further catalog edits and final checks are still in progress.
- Reviewer candidate remaining issues, to revalidate final source: copied timetable feed ignores Count groups, recents use SharedPreferences instead of planned DataStore, and child routes lose parent navigation highlight. Private review notes `/tmp/epita-native-night/review-3-fix-1-notes.md`; no second formal verdict or fix round yet.

Current position: Phase2b is the only merged phase and native/night builds. Phase3 first fix worker is still live; image QA is complete for the current hashes, final independent checks/code review remain. Goal active, noPhase3 merge and later phases pending.


## Phase 3 fix round 1 independently reviewed — FIX

- Worker completed and stopped at `b2b1ff3`. Reviewer independently ran npm104/104 and Android unit/lint/app+test assemblies/unfiltered screenshot verification:225 tests,0 failures/errors/skips, passed17s. Counts by type:23golden,29ordinaryJVM,3otherRobolectric,1RobolectricViewModel,33Composeinteraction/integration,136screenshots. All136 final PNG hashes match the independently inspected union,84 changed against cb8444d.
- Independent local smoke passed. Independent full managed-device/local-server e2e passed1m30s,69 tasks executed; fresh XML2 cases,0 failures/errors/skips, including actual local rejected-session401/sign-in/pendingSession delivery and offline new-process cached startup. Both local ports8787/8788 free after cleanup. Logs `/tmp/epita-native-night/review-3-fix1-{npm,gradle,smoke,device}.log`. No new website/server/migration/secret/plan/android/ changes; main still e97f6ca.
- Review3 now records round1FIX, with four remaining findings: Count-scoped copied feed URL; parent nav highlighting/semantics on child routes; DataStore-backed persisted recents; dedicated two-account AppNav/restoration isolation test. Other original fixes accepted at this stage. Forms/controllers remain deliberately assigned to4/5 with saved payloads/checklist.

Current position: Phase2b merged and native/night builds. Phase3 isFIX after1 completed fix round; round2 starting on the same branch. NoPhase3 merge; later phases pending.


## Phase 3 fix round 2 quota interruption and resumed work

- New Terra fix-round2 session `01a18cd2-6ad7-7222-bd4c-4329f7e67196` (handle73803) terminated exit1 on usage limit with reset13:51 before changing source. The user continued the active goal; reviewer checked live time12:55:57UTC/13:55:57Paris2026-10-30, past reset. No implementation/Gradle/e2e process remained and source was clean apart from reviewer-owned untracked review copy.
- Resumed the SAME Terra session after reset, live shell22486, log `/tmp/epita-native-night/phase-3-fix-2-resume-worker.log`. This remains fix round2, not another fix attempt. No alternative model or quota bypass. The resumed worker is implementing the four remaining findings from the committed review.

Current position: Phase2b remains the only accepted merge and native/night builds. Phase3FIX after round1; round2 is live after quota reset, noPhase3 merge and later phases pending.


## Phase 3 final review — PASS after two fix rounds

- Accepted worker commit `6947dcd` after prior `b2b1ff3`. All review findings fixed; reviewer-authored review3 nowPASS, with initial/round1 FIX history and explicit resolution evidence.
- Reviewer forced final Android command with `--rerun-tasks`: all83 tasks executed and passed1m19s; fresh244 tests,0 failures/errors/skips (23golden,29ordinaryJVM,4otherRobolectric,1RoboVM,51Composeinteraction,136screenshots). npm104/104. All136 current hashes independently visually reviewed;32 round2 changes inspected and match final manifest `/tmp/epita-native-night/fix2-current-reviewed-hashes.json`.
- Independent local smoke and full managed-device e2e passed, fresh2 cases0 failures/errors/skips; device run1m32s/all69 tasks. Both local ports free. Logs `/tmp/epita-native-night/review-3-fix2-{npm,gradle,forced-gradle,smoke,device}.log`.
- Full read-only shell/typed action seams are accepted for Phase3. Detailed forms/screens in4, actual timer controller in5, widget publication in6, durable generalized task/habit outbox in11 and final design audit in8 remain mandatory, with hooks recorded in progress/review. No real external identity-provider/browser/physical-device claim.

Current position: Phase3PASS at6947dcd, two fix rounds; merge into native/night is next, followed by merged-branch verification and Phase4a. Main unchanged; no push/deploy.
