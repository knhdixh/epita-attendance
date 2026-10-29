# Native Android Phase 2b review

Verdict: **FIX**. Initial review, 2026-10-30. No fix rounds completed yet. Do not merge or start Phase 3.

Reviewed committed implementation through `91cf2b0`, plus the UI worker's uncommitted changes and untracked test files/PNGs. The UI worker correctly stopped without committing because its required checks failed. Preserve that work and finish it in the first fix round.

## Findings

1. **High — 200% font coverage is missing and both screenshot tests fail.** `android-native/app/src/test/java/com/knhdixh/attendance80/ui/ScreenScreenshotTest.kt:33` changes density in a single long-lived composition; the assertion at line 64 fails with `200% font must increase text height`. The earlier font1/font2 sign-in PNG pairs were byte-identical; the worker removed the eight misleading font2 files, leaving only 16 provisional PNGs. Establish the actual 2.0 configuration before the target content is measured (isolated configurations/test cases are an option), retain an assertion proving enlarged text, and record the full light/dark phone/tablet matrix including both main screens at 2.0. Keep content scrollable and actions reachable, without clipped text. Unfiltered record and verify must pass; do not disable or weaken the assertion.

2. **High — the create-account interaction does not become idle.** `android-native/app/src/test/java/com/knhdixh/attendance80/ui/SignInUiTest.kt:60` fails with `Compose did not get idle after 325618 attempts in 60 SECONDS`. Reviewer thread inspection independently showed the test in `RobolectricIdlingStrategy.runUntilIdle` while asserting the dialog. Correct the test host, state/lifecycle or layout scheduling so the real dialog can be opened, empty creation refused, name entered and submitted, reopened and cancelled, and developer sign-in exercised. Keep actual behaviour assertions; no skipped tests or replacing the dialog with a fake. The complete unit suite must finish and pass.

3. **Medium — Habits validation differs from JavaScript.** `android-native/app/src/main/java/com/knhdixh/attendance80/logic/Habits.kt:25` treats any primitive whose content is `true`/`false` as a boolean, including strings. JS `cleanSchedule({type:'weekly',n:'true'})` returns null, while Kotlin returns a weekly schedule; for `{type:'days',days:['true',2]}` JS keeps only day 2. Reviewer temporarily extended the resource fixtures and expectations using the real JS functions: `HabitsGoldenTest` failed at `$.schedules[11].clean`, expected JsonNull, actual JsonObject. Both files were restored byte-for-byte and the ordinary 35-test suite passed before UI additions. Distinguish string and boolean conversion, retain JS Number semantics for supported JSON values, and add permanent source fixtures generated through golden.mjs. Also avoid throwing on a non-primitive schedule type where JS returns null. Do not hand-edit expected outputs.

4. **Medium — dialog screenshot framing differs from the other captures.** `android-native/app/src/test/java/com/knhdixh/attendance80/ui/ScreenScreenshotTest.kt:67` emits a full activity image for the dialog node, including an extra native `Attendance80` title bar absent from the sign-in/loading captures. The light dialog also blends into the underlying form. Configure a consistent host matching the app, then capture either the actual dialog bounds or the complete correctly hosted screen, with its real surface/overlay. Inspect and replace all four dialog PNGs. This is about accurate visual evidence; do not alter production copy or simulate a dialog for the test.

## Independent checks and evidence

- `npm test`: 104 tests, **103 passed, 1 failed, 0 skipped**. Only the explicitly permitted countdown time-of-day assertion (`test/habits.test.js:222`, expected 10, got 11).
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug`: exit 1 after 1m15s. XML: **40 tests, 37 passed, 3 failed, 0 errors, 0 skipped**. Failures: phone screenshot, tablet screenshot and dialog interaction. Breakdown: 23 golden tests passed; 11 ordinary JVM tests passed; existing Robolectric ViewModel test passed; Compose interaction tests 2/3 passed; screenshot tests 0/2 passed. Instrumented tests: not introduced until Phase 3.
- Separate `./gradlew lintProdDebug assembleProdDebug`: **BUILD SUCCESSFUL**, exit 0. A building APK does not establish passing tests.
- `./gradlew verifyRoborazziProdDebug --tests '*PhoneScreenshotTest'`: exit 1 after 7s, same font-growth assertion. No whole-matrix verification claimed.
- Inspected **all 16 remaining PNGs** (four labelled contact sheets, plus original-size spot checks) under `app/src/test/screenshots/`: phone 411x891, tablet 840x1000; light/dark; sign-in/loading/placeholder/create-account dialog. No accepted 200% images exist. Native rendering replaced initial diagnostic dumps. Main screen backgrounds and accent token pixel values agree with the plan for these normal-font captures.
- `git diff --check`: passed. No server source or migration changes, so no new D1 query-plan requirement. Existing migrations and `android/` are unchanged. Plan changes are confined to permitted section 4 endpoint corrections. Assistant API/DTO removal and new generated API fixtures are present.
- Logic goldens are generated from the real JS modules. Worker mutation checks are documented; reviewer additionally reproduced the coercion defect using JS-computed expectations. No secrets or deployment changes were added to the reviewed paths.

Local evidence: `/tmp/epita-native-night/review-2b-initial-{npm,gradle,build,verify}.log`, `review-2b-test-thread.txt`, `review-habits-coercion.log`, and `screenshots-review-0.jpg` through `screenshots-review-3.jpg`. These are local review aids; durable findings and counts are recorded here.

## Re-review gate

Fix every finding on `native/phase-2b`, append a `Review 2b fixes — round 1` entry with exact counts/commands/commit references, and commit only after required checks pass. Then stop for independent review. Maximum three fix rounds, per overnight-run.md. The night branch remains at its last independently building state until PASS.
