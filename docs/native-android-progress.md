# Native Android Implementation Progress

## Phase 1: Server Auth + Project Skeleton

### What was done
- **Task 1: Server Auth Changes & Tests** (`77e6ab1`)
  - Added migration `migrations/0019_app_auth.sql` creating `app_codes` table with PKCE challenge and 2-minute expiration.
  - Implemented `a80s_` app session tokens in `src/worker.js` with sliding expiry (under 15 days left extends to 30 days) and bypass of `tokenAllows` restrictions.
  - Implemented PKCE Google OAuth start (`GET /auth/app/google?challenge=`), callback handling (`com.knhdixh.attendance80:/auth?code=`), and token exchange (`POST /auth/app/token`).
  - Added dev sign-in for emulator testing (`GET /auth/app/dev?email=&challenge=`).
  - Added passkey support for native apps with `X-A80-App: 1` header, returning app tokens instead of session cookies.
  - Added `appOrigins` in `src/passkeys.js` computing `android:apk-key-hash:<base64url>` from `env.ANDROID_CERT_SHA256`.
  - Added `delegate_permission/common.get_login_creds` relation to `assetLinks()`.
  - Implemented `POST /api/app/web-link` and `GET /auth/app/web?code=` for browser flows such as Google Calendar connection with `?app=1` support and custom scheme callback (`com.knhdixh.attendance80:/gcal?result=connected`).
  - Wrote comprehensive test suite in `test/app-auth.test.js`.

- **Task 2: Android Project Skeleton** (`fdb5ac1`)
  - Created `android-native/` with Gradle 8.11.1 wrapper and AGP 8.9.1.
  - Set up version catalog in `gradle/libs.versions.toml`.
  - Configured `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, Java 17, and Kotlin 2.1.0 with Compose and Serialization plugins.
  - Configured product flavors: `prod` (`https://epita.knhdixh.com`) and `staging` (`https://epita-staging.knhdixh.com`), supporting `-PbaseUrl` in debug.
  - Configured signing reading `../android/keystore.properties` and enabled R8 code minification on release builds.
  - Added `res/xml/network_security_config.xml` in `src/debug/` allowing cleartext traffic to `10.0.2.2` and `localhost`.
  - Copied launcher icons (monochrome and adaptive) and widget drawables/colors.

- **Task 3: Shell, AppContainer, MainActivity & Theme** (`44da9f7`)
  - Implemented `Clock` interface and `SystemClock`.
  - Created Material 3 theme in `ui/theme/` (`Color.kt`, `Type.kt`, `Theme.kt`, `Shapes`) mapping the web site's design tokens to light and dark color schemes.
  - Created `AppContainer` interface and `DefaultAppContainer`.
  - Configured `App` application class and edge-to-edge `MainActivity`.

- **Task 4: Networking Layer (`net/`)** (`9a86ea1`)
  - Created Retrofit `Api` interface covering every endpoint in section 4.
  - Created `@Serializable` DTOs with `IntBooleanSerializer` for SQLite 0/1 boolean conversions.
  - Implemented `ApiError(status, message)` parsing `{ error }` response bodies.
  - Implemented `AuthInterceptor` adding `Authorization: Bearer <token>` to `/api/*` and triggering unauthorized callback on 401.
  - Implemented `ErrorInterceptor` throwing `ApiError` on HTTP failures.
  - Implemented `InMemoryCookieJar` for WebAuthn cookies.
  - Added unit tests in `AuthInterceptorTest.kt` verifying bearer header injection and 401 token clearing with `MockWebServer`.

- **Task 5: Secure Token Storage (`auth/TokenStore`)** (`5ea7d66`)
  - Implemented `TokenStore` with `EncryptedTokenStore` storing AES-GCM ciphertext in DataStore Preferences.
  - Implemented `AndroidKeystoreCipher` generating and using 256-bit AES key in Android Keystore under alias `a80_session`.
  - Provided in-memory cached `token` StateFlow for synchronous OkHttp `tokenProvider`.
  - Added unit tests in `TokenCipherTest.kt` verifying AES-GCM round-trip encryption/decryption.

- **Task 6: Sign-In Screen & PKCE** (`e4a2b7e`)
  - Implemented `Pkce` utility generating 43-character base64url random verifiers and SHA-256 challenges.
  - Implemented `AuthRepository` managing Google Custom Tabs, Credential Manager passkeys, and dev login.
  - Implemented `SignInViewModel` and `SignInScreen` with Material 3 styling matching web design.
  - Added deep link handler in `MainActivity` for `com.knhdixh.attendance80:/auth?code=`.
  - Added unit tests in `PkceTest.kt` verifying challenge computation and MockWebServer token exchange.

### Exact check commands and results
1. `npm test`
   - Result: **82 passed, 0 failed, 0 skipped** (498.9 ms)
2. `./gradlew :app:assembleProdDebug`
   - Result: **BUILD SUCCESSFUL** (37 tasks executed/up-to-date)
3. `./gradlew :app:assembleStagingDebug`
   - Result: **BUILD SUCCESSFUL**
4. `./gradlew :app:assembleProdRelease` (R8 minification + signing)
   - Result: **BUILD SUCCESSFUL**
5. `./gradlew :app:testProdDebugUnitTest`
   - Result: **6 passed, 0 failed, 0 skipped**
     - `PkceTest.pkceExchangeAgainstServer`: PASSED
     - `PkceTest.verifierAndChallengeAreValidBase64UrlSha256`: PASSED
     - `TokenCipherTest.encryptDecryptRoundTrip`: PASSED
     - `AuthInterceptorTest.bearerTokenAddedToApiRequests`: PASSED
     - `AuthInterceptorTest.unauthorized401ClearsTokenOnApiRequests`: PASSED
     - `AuthInterceptorTest.bearerTokenNotAddedToAuthRequests`: PASSED
6. `./gradlew :app:lintProdDebug`
   - Result: **BUILD SUCCESSFUL** (0 errors)

### Deviations from the plan
- Used standard `java.util.Base64` instead of `android.util.Base64` in `Pkce.kt` and `TokenStore.kt`. Since `minSdk` is 26, `java.util.Base64` is fully supported on all target Android versions (API 26+) and runs natively in pure JVM unit tests without needing mock stubs.
- Added `tools:ignore="AppLinkUrlError"` to the custom scheme intent filters in `AndroidManifest.xml` (`com.knhdixh.attendance80:/auth` and `com.knhdixh.attendance80:/gcal`) to satisfy Android Lint.

### Unverified items
- **Unverified**: Sign-in flow against production Google OAuth in a real browser tab and hardware passkeys with Credential Manager requires a physical device or emulator with Google Play Services.

### Open questions
- None for Phase 1.

## Phase 2: Logic Port with Golden Tests

### What was done
- **Pure logic port to `com.knhdixh.attendance80.logic` (`db1900c`)**
  - `Stats.kt`: `TAGS`, `STATUSES`, `splitGroup`, `countsAttendance`, `effectiveTag`, `computeStats`, `statsByCourse`. Maintained `1e-9` epsilons, `sessions` and `hours` units, planned skips, excused absences, and missed mandatory/exam flagging.
  - `Breaks.kt`: `HOLIDAY_RE`, date helpers (`isDate`, `addDays`, `weekdayOf`, `dayDiff`, `makeDayKey`), `levelOf`, `breakModel`, `simulateBreak`, `buckets`, `checkWindow`, `findBreakWindows`, `longestSafe`, `weekOverview`, `startDayGrid`.
  - `Plan.kt`: `DAY_START`, `DAY_END`, `effortOf`, `localMidnight` (DST-safe local calendar start of day using `java.time`), `atHour`, `freeBlocks`, `buildPlan` (to-do rank order, gap fitting, 7-day class and work load, clashes).
  - `FocusTimer.kt`: immutable `data class FocusTimer` with exact JSON shape (`phase, next, custom, run, startedAt, endsAt, pausedAt, pausedMs, done, target, updatedAt`), `cleanSettings`, `cleanTags`, `cleanTasks`, `idle`, `start`, `setLength`, `adjust`, `pause`, `resume`, `remaining`, `length`, `progress`, `isDue`, `finish`, `stop`, `skipIdle`.
  - `FocusStats.kt`: `dailyTotals`, `minutesByHomework`, `focusStats` (today total, streak, week vs last week, range breakdowns, peak hour), `focusSummary` for scripts and external consumers.
  - `Groups.kt`: `groupFamilies` with natural sorting on group prefixes (`GR1`, `GR2`, `GR10`), `sanitizeScope`, `applyScope`, `courseCalendars`, `groupLabel` ("All groups", "SE1 · SE2"), `scopeNote`, `toggleScope`.
  - `Dates.kt`: shared date/time formatting helpers using `java.time` and device locale/zone.
- **Fixture generator & golden test suite (`android-native/tools/golden.mjs`)**
  - Generated fixtures in `android-native/tools/fixtures/`:
    - `semester.json`: 9 courses (normal, group-split `GR1`/`GR2`/`GR10`, "Vacances" holiday, Sisu with `attendance: 0`, optional workshop), 98 sessions covering a semester with varying lengths (1h-4h), exam/mandatory sessions, past present/absent/excused, future planned skips, unmarked past sessions, 7 homework items, and 10 DST and normal time points for `Europe/Paris` and `Europe/Helsinki`.
    - `focus.json`: 9 timer states (fresh idle, custom length, targets, running focus/break, paused, resumed, due, stale), 469 focus sessions spanning a full year across streaks and gaps.
    - `groups.json`: group splitting strings, scope cases, toggle sequences, and calendar combinations.
  - `golden.mjs` runs against real `public/*.js` without modifying `public/` and writes golden outputs to `android-native/app/src/test/resources/golden/`.
  - Kotlin golden tests (`StatsGoldenTest.kt`, `BreaksGoldenTest.kt`, `PlanGoldenTest.kt`, `FocusTimerGoldenTest.kt`, `FocusStatsGoldenTest.kt`, `GroupsGoldenTest.kt`) comparing every single field with `1e-9` tolerance for doubles.

### Exact check commands and results
1. `node android-native/tools/golden.mjs`
   - Result: **All golden files generated successfully!**
2. `npm test`
   - Result: **82 passed, 0 failed, 0 skipped** (524.3 ms)
3. `./gradlew :app:testProdDebugUnitTest`
   - Result: **25 passed, 0 failed, 0 skipped**
     - `StatsGoldenTest` (3 tests): `testSplitGroupGolden`, `testComputeStatsGolden`, `testStatsByCourseGolden` PASSED
     - `BreaksGoldenTest` (2 tests): `testDateHelpersGolden`, `testBreaksCasesGolden` PASSED
     - `PlanGoldenTest` (3 tests): `testLocalMidnightGolden`, `testEffortOfGolden`, `testBuildPlanGolden` PASSED
     - `FocusTimerGoldenTest` (2 tests): `testCleanHelpersGolden`, `testTimerOperationsGolden` PASSED
     - `FocusStatsGoldenTest` (3 tests): `testDailyTotalsAndMinutesByHomeworkGolden`, `testFocusStatsGolden`, `testFocusSummaryGolden` PASSED
     - `GroupsGoldenTest` (6 tests): `testGroupFamiliesGolden`, `testApplyScopeGolden`, `testCourseCalendarsGolden`, `testGroupLabelGolden`, `testScopeNoteGolden`, `testToggleScopeGolden` PASSED
     - `PkceTest` (2 tests): PASSED
     - `TokenCipherTest` (1 test): PASSED
     - `AuthInterceptorTest` (3 tests): PASSED
4. `./gradlew :app:assembleProdDebug`
   - Result: **BUILD SUCCESSFUL**
5. `./gradlew :app:lintProdDebug`
   - Result: **BUILD SUCCESSFUL** (0 errors)

### Deviations from the plan
- `BreakWindow.margin` is modeled as `Double? = null` because JSON encodes `Infinity` (a window with 0 classes to skip, e.g. on holidays or weekends) as `null`.

### Unverified items
- None for Phase 2.

### Open questions
- None for Phase 2. Ready to begin Phase 3.

## Review 1 Fixes (Phases 1 and 2)

All items under **Must fix** (items 1–8) and **Should fix** (items 9–14) from `docs/native-android-review-1.md` have been resolved in dedicated, isolated commits.

### Fixed Items & Commits

1. **DTO schemas and nullability (`net/Models.kt`)** (`dbb8531`)
   - Removed `timetableFeedUrl` from `IntegrationsDto`.
   - Corrected nullability on `HomeworkDto` (`remind_minutes`, `effort_minutes`, `due_time`, `course_id`, `event_id`, `gcal_event_id`, `sync_error`), `FocusSessionDto` (`label`, `homework_id`, `course_id`), and `CalendarSyncDto`.
   - Aligned `FocusSettingsDto` names (`focus, short, long, longEvery, goal, autoBreaks, autoFocus, sound, notify, tasks, tags`) with defaults (`autoBreaks = true`).
   - Flattened `AssistantDto` (`used, limit, owner, byok`) with `AiByokDto`.
   - Added `android-native/tools/generate-api-fixtures.mjs` and generated `data.json` and `data_empty.json` from the local dev server.
   - Added `DataDtoTest.kt` verifying full and empty data decoding.
   - *Check*: `./gradlew :app:testProdDebugUnitTest --tests com.knhdixh.attendance80.net.DataDtoTest` PASSED.

2. **Time zone independence** (`95f0c25`)
   - Pinned `process.env.TZ = 'Europe/Paris'` in `golden.mjs` before imports, added dual run for `Europe/Paris` and `Europe/Helsinki` in `focus_stats_golden.json`.
   - Updated `FocusStatsGoldenTest.kt` to run against explicit `ZoneId` from fixture runs instead of `ZoneId.systemDefault()`.
   - *Check*: Ran against `TZ=UTC`, `TZ=America/New_York`, and `TZ=Europe/Helsinki`; all tests passed.

3. **Regex differences JS vs JVM** (`11f15c9`)
   - Aligned `HOLIDAY_RE` in `Breaks.kt` using simulated JS ASCII word boundaries and case folding for French accented characters (`[eéEÉ]`).
   - Aligned `splitGroup` in `Stats.kt` to handle Unicode whitespaces (`\u00A0`, etc.) and dashes.
   - Added unicode golden test cases in `groups.json` and `breaks_golden.json`.
   - *Check*: `node android-native/tools/golden.mjs` and `./gradlew :app:testProdDebugUnitTest` PASSED.

4. **Recursive deep JSON comparison in golden tests** (`50a8341`)
   - Created `JsonAssertions.kt` with recursive `assertJsonEquals` handling floating point tolerance (`1e-9`) and JS `null` for `Infinity`/`NaN`.
   - Added `cleanSettings(raw: JsonElement?)` to `FocusTimer.kt` and `day(ms, sessions, zoneId)` to `FocusStats.kt`.
   - Replaced hand-picked field assertions with `assertJsonEquals` across `BreaksGoldenTest`, `PlanGoldenTest`, `FocusTimerGoldenTest`, `FocusStatsGoldenTest`, and `StatsGoldenTest`.
   - *Check*: `TZ=UTC ./gradlew :app:testProdDebugUnitTest` and `TZ=Europe/Helsinki ./gradlew :app:testProdDebugUnitTest` PASSED.

5. **Fixture gaps and logic nits** (`56e0a9c`)
   - Added March 2026 sessions for spring DST coverage in Paris/Helsinki, course 10 (`Strict Seminar`) with `noSkip` active, and March homework.
   - Added timer fixture variations: paused break, stale break, idle with next short/long, custom lengths, out-of-range/junk cleanSettings, and focus sessions with tags and without label.
   - Addressed logic nits: `effortOf`, `nextLength`, `start` treating 0 as missing (`||`), `FocusStats.kt:165` JS truthiness (`label != null && label != ""`), `groupFamilies` sorting with `Collator.getInstance(Locale.ROOT)`, and removed unused `atHourResults` in `golden.mjs`.
   - *Check*: `node android-native/tools/golden.mjs` and `./gradlew :app:testProdDebugUnitTest` PASSED.

6. **PKCE verifier SavedStateHandle persistence** (`4a3c388`)
   - Updated `MainActivity.kt` view model factory to use `viewModelFactory { initializer { SignInViewModel(..., createSavedStateHandle()) } }`.
   - *Check*: `./gradlew :app:compileProdDebugKotlin` PASSED.

7. **Auth deep link replay prevention** (`3181afe`)
   - Updated `MainActivity.kt` to only process auth deep link when `savedInstanceState == null` and on `onNewIntent`, clearing `intent.data = null`.
   - *Check*: `./gradlew :app:compileProdDebugKotlin` PASSED.

8. **Startup token 401 race prevention** (`a3a6f71`)
   - Added `isLoaded: StateFlow<Boolean>` and `awaitToken(): String?` to `TokenStore` and `EncryptedTokenStore`.
   - Configured `AppContainer.kt` to await token before OkHttp requests.
   - Updated `AuthInterceptor.kt` to only invoke `onUnauthorized()` on 401 if the request actually carried an `Authorization: Bearer ...` header.
   - Updated `MainActivity.kt` to wait for `isLoaded` before rendering `AppNav` to prevent flash of sign-in screen.
   - Added unit tests in `AuthInterceptorTest.kt` verifying unauthenticated 401 does not clear token and async token load is awaited.
   - *Check*: `./gradlew :app:testProdDebugUnitTest --tests com.knhdixh.attendance80.net.AuthInterceptorTest` (5/5 tests PASSED).

9. **Server auth fixes** (`ebd6b2b`)
   - Enforced regex `/^[A-Za-z0-9_-]{43}$/` for PKCE challenges in `/auth/app/google` and `/auth/app/dev`.
   - Cleaned up expired `app_codes` on new code insertion.
   - Redirected `calendar-app` session errors to `com.knhdixh.attendance80:/gcal?error=session`.
   - Added comprehensive tests in `test/app-auth.test.js` for Google callback app flow, error redirects, calendar-app redirects, and passkey app token verify. Cleaned leftover comments and unused imports.
   - *Check*: `npm test` (88/88 tests PASSED).

10. **Exclude token DataStore from backup and device transfer** (`a3a5782`)
    - Added `res/xml/data_extraction_rules.xml` (API 31+) and `res/xml/backup_rules.xml` (API < 31) excluding `datastore/auth_prefs.preferences_pb`.
    - Referenced both in `AndroidManifest.xml` under `<application>`.
    - *Check*: `./gradlew :app:assembleProdDebug` PASSED.

11. **Restrict `-PbaseUrl` to debug build types only** (`5b76f6b`)
    - Moved `-PbaseUrl` override into `buildTypes.debug` in `android-native/app/build.gradle.kts`, ensuring release builds always point to the production/staging flavor host.
    - *Check*: Generated `BuildConfig.java` for `prodDebug` and `prodRelease` with `-PbaseUrl=http://10.0.2.2:8787` and verified `prodRelease` retained `"https://epita.knhdixh.com"`.

12. **`USE_EXACT_ALARM` permissions & Phase 5 fallback plan**
    - `USE_EXACT_ALARM` and `SCHEDULE_EXACT_ALARM` are both declared in `AndroidManifest.xml`.
    - Note recorded for Phase 5: Implement runtime check `canScheduleExactAlarms()` and fallback to `SCHEDULE_EXACT_ALARM` if `USE_EXACT_ALARM` is restricted.

13. **Dev sign-in email URL-encoding** (`d856754`)
    - URL-encoded email parameter in `SignInViewModel.startDevSignIn` using `java.net.URLEncoder.encode(email, "UTF-8")` and `generate-api-fixtures.mjs`.
    - Added unit test in `SignInViewModelTest.kt` verifying URL encoding with `+` and special characters.
    - *Check*: `./gradlew :app:testProdDebugUnitTest --tests com.knhdixh.attendance80.ui.signin.SignInViewModelTest` PASSED.

14. **Library scoping and strict JSON decoding** (`43bf8fb`)
    - Moved `okhttp-logging` from `implementation` to `debugImplementation` in `app/build.gradle.kts`.
    - Removed `isLenient = true` from `ApiClient.json`.
    - Confirmed `material-icons-extended` is not bundled in dependencies.
    - *Check*: `./gradlew :app:assembleProdDebug :app:assembleProdRelease :app:testProdDebugUnitTest` PASSED.

15. **Nit: Clarified `TokenCipherTest`** (`d1aa10f`)
    - Renamed test in `TokenCipherTest.kt` to `softwareAesGcmCipher_encryptDecryptRoundTrip` and added comment noting instrumented `AndroidKeystoreCipher` test scheduled for Phase 8.
    - *Check*: `./gradlew :app:testProdDebugUnitTest --tests com.knhdixh.attendance80.auth.TokenCipherTest` PASSED.

### Final Verification Results

- `npm test`: **88/88 passed** (0 failed, 589 ms)
- `cd android-native && TZ=UTC ./gradlew :app:testProdDebugUnitTest --rerun-tasks`: **31/31 passed**
- `cd android-native && TZ=America/New_York ./gradlew :app:testProdDebugUnitTest --rerun-tasks`: **31/31 passed**
- `cd android-native && TZ=Europe/Helsinki ./gradlew :app:testProdDebugUnitTest --rerun-tasks`: **31/31 passed**
- `cd android-native && ./gradlew :app:lintProdDebug`: **BUILD SUCCESSFUL** (0 errors)
- `cd android-native && ./gradlew :app:assembleProdDebug :app:assembleProdRelease`: **BUILD SUCCESSFUL** (release R8 minification verified)


## Merge into main (2026-10-29, by Claude with the owner)

- `native-android` (up to `acfe138`) was merged into `main` in `d67613b`, after `origin/main` (Web Push) was
  merged onto the live habits and countdowns line in `9202b56`. See the plan, section 8.1.
- `migrations/0015_app_auth.sql` was renamed to `0019_app_auth.sql`; the plan and this log were updated to match.
- The plan was amended: section B now asks for a branch and a review stop per phase, the assistant is gone,
  Phase 2b (catch up with `main`) comes next, and the Todoist phases 10–13 were added (section 9).
- Checks after the merge: `npm test` 103 of 104 pass. The failure is the countdown `days` assertion in
  `test/habits.test.js`, which depends on the time of day and fails on the live line too.
  `./gradlew testProdDebugUnitTest` 31 tests, 0 failures. `node android-native/tools/golden.mjs` changes
  `plan_golden.json` (the new `planned` list), so `Plan.kt` is behind `public/plan.js`; that's Phase 2b, task 1.

## Phase 2b — catch up with main (2026-10-29)

### Task 1 — day plan and focus timer
- Ported `plannedBlock`, busy-time subtraction, the `planned` result and exclusion of today's planned tasks from gap fitting. Added homework planning fields needed by this port.
- Diffed `public/focus.js` against `1243884`: the additions are `parseClock` and `setRemaining`; both are ported.
- Extended JS-generated fixtures with active planned blocks, typed-clock valid/invalid inputs, and remaining-time limits across every existing timer state and settings profile.
- Checks: `node android-native/tools/golden.mjs` — exit 0, “All golden files generated successfully!”.
- `npm test` — exit 1: 104 tests, 103 passed, 1 failed, 0 skipped; the known `test/habits.test.js:222` countdown assertion expects 10 days, receives 11. Reproduced before changes; left untouched as instructed by 8.2.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug` — exit 0, BUILD SUCCESSFUL in 21s; 31 tests, 0 failures/errors/skips. Initial sandbox attempt could not lock the Gradle cache; retry with cache access succeeded.
- Deviation: planning DTO fields introduced with task 1 because its port requires them, ahead of the remaining DTO work in task 3.

### Review stop — Phase 2b is incomplete
- Task 1 commit: `63206f0` (`Android: catch up day plan and focus timer logic`).
- **Deviation:** committed task 1 with the known baseline npm failure, despite section B's requirement that each task commit pass tests. The Android checks passed; the npm check did not. This is not a claim that Phase 2b passes.
- Final documentation check: `npm test` — exit 1, 104 tests, 103 passed, 1 failed, 0 skipped (660.452208 ms). Same `test/habits.test.js:222` assertion: actual `['Exam', 11, true]`, expected `['Exam', 10, true]`.
- **Open question:** Section B.2 requires each commit to pass tests and B.5 says “If a check fails and you can't fix it, stop and record it in the log.” Section 8.2 says the countdown assertion is “Known, not yours to fix” and to note it if seen. May task commits explicitly carry this one baseline failure, or should the owner resolve it first? Stopping under B.5/B.6 rather than changing that test or claiming it passed.
- **Unverified / remaining:** task 2 (Habits, Countdowns, CalendarMath ports and their golden tests), task 3 (regenerated API fixtures and remaining DTO changes), task 4 (endpoint verification and permitted section 4 corrections), and the new-golden mutation check have not been completed. No subsequent phase started; no deploy, remote migration, merge or push performed.

### Task 3 — data snapshot DTOs (continued)
- Commit `e533c9d` removes the obsolete assistant models and Retrofit methods, models the `/api/data` habits, logs, countdowns and notification settings, and regenerates both worker-backed API fixtures. The full fixture exercises a planned homework block, a scheduled habit and check-in, a countdown and changed notification settings; decoding assertions cover those fields.
- `node android-native/tools/generate-api-fixtures.mjs` — exit 0; generated both fixtures.
- `npm test` — exit 1: 104 tests, 103 passed, 1 failed, 0 skipped. The permitted baseline countdown assertion at `test/habits.test.js:222` returned 11 instead of 10.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug` — exit 0, BUILD SUCCESSFUL; test result XML reports 31 tests, 0 failures, 0 errors, 0 skipped; lint passed.
- **Unverified / remaining:** task 2's three JS ports and generated golden tests, task 4's full endpoint and DTO parity inspection, the deliberate golden mutation check, Compose Robolectric tests, Roborazzi screenshot infrastructure and its PNG matrix and verification. Phase 2b is incomplete and is not ready for review as a finished phase.

### Task 2 split — Habits logic and golden tests (2026-10-30)
- Added `logic/Habits.kt`: schedule and habit validation, labels, day keys, zoned reminder day, streaks and rates, monthly totals and quick-add parsing. DTO overloads accept the snapshot habit model directly. The emoji parser uses Extended_Pictographic ranges generated from the installed Node runtime; future Unicode-version changes may require refreshing these ranges.
- Added `tools/fixtures/habits.json` and JS-generated `habits_golden.json`; the single Habits golden test recursively compares every output field at 1e-9 tolerance. The input includes all five schedule types, daily targets above one, backfills, a midweek creation, spring and autumn DST dates in Paris and Helsinki, validation and all habit sentence examples from `test/habits.test.js`.
- Mutation check: increased `stats[0].stats.streak` in the generated golden by one; `cd android-native && ./gradlew testProdDebugUnitTest --tests '*HabitsGoldenTest'` exited 1 with 1 test run, 1 failure. Regenerated the file with `node android-native/tools/golden.mjs`; the restored golden passed in the full Android check.
- `npm test`: exit 1, 104 tests, 103 passed, 1 failed, 0 skipped; the permitted known countdown assertion at `test/habits.test.js:222` expected 10 days and got 11.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug`: exit 0, BUILD SUCCESSFUL; 32 JVM tests, 0 failures, 0 errors, 0 skipped; lint passed. Habits contributes one golden test containing multiple fixture comparisons.
- **Remaining Phase 2b:** Countdowns and CalendarMath ports and JS-generated golden tests, endpoint and DTO parity audit, Compose Robolectric tests, Roborazzi screenshot infrastructure and its PNG matrix and verification. No review or report was edited. The known npm baseline failure is carried under this split run's explicit instruction.
- **Unverified:** exhaustive JavaScript coercion and Unicode-version parity for arbitrary malformed inputs; no screen or device integration was exercised in this split.

### Task 2 split — Countdowns logic and golden tests (2026-10-30)
- Task commit `ac1eba9` ports validation, local date helpers, recurrence, working days, display and progress, sorting, and quick-add parsing to `logic/Countdowns.kt`. The calculation functions accept an explicit zone, and DTO overloads support the snapshot model; generic sorting also accepts a countdown selector.
- Added source fixtures and a separate golden generated by `public/countdowns.js`. The recursive Kotlin comparison checks 956 countdown cases across all units, repeats, after modes and all-day/timed values, 40 date-helper cases, 17 validation inputs in full and partial modes, and 22 sentences in each of Paris and Helsinki. Fixtures cover Jan 31, spring and autumn DST, every countdown parse example in `test/habits.test.js`, and emoji and Unicode titles. Numeric comparisons use the existing 1e-9 assertion.
- Mutation check: changed the first golden view's `big` value; `cd android-native && ./gradlew testProdDebugUnitTest --tests '*CountdownsGoldenTest'` exited 1 with a failing test. Regenerated the golden using `node android-native/tools/golden.mjs`, exit 0, before the full checks.
- `npm test`: exit 1, 104 tests, 103 passed, 1 failed, 0 skipped; the explicitly permitted known `test/habits.test.js:222` assertion expected 10 days and got 11.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug`: exit 0, BUILD SUCCESSFUL; 33 JVM tests, 0 failures, 0 errors, 0 skipped; lint passed. Countdowns contributes one recursive golden test.
- **Remaining Phase 2b:** CalendarMath port and generated golden tests, endpoint and DTO parity audit, Compose Robolectric tests, Roborazzi screenshot infrastructure and its PNG matrix and verification. The known npm failure is carried under this split run's explicit instruction.
- **Unverified:** arbitrary malformed JavaScript inputs and all Unicode or historical time-zone edge cases beyond these fixtures; screen and device integration remain future work. No review or night report was edited.

### Task 2 split — CalendarMath logic and golden tests (2026-10-30)
- Phase 2b task 2: ported `public/calendar.js` date helpers, period, shift, ISO week, overlap layout, course colours and auto-planning into `logic/CalendarMath.kt`. Calculations take an explicit `ZoneId`; local date-time resolution uses the earlier offset in overlaps and advances through gaps, matching JavaScript Date for the tested DST transitions. Planning retains the web's epoch-based snap grid and existing-block daily load.
- Added `tools/fixtures/calendar.json`, its test-resource copy, and `calendar_golden.json` generated by `node android-native/tools/golden.mjs` from the real `public/calendar.js`. One recursive golden test compares all fields using the shared 1e-9 assertion: 16 date cases in Paris and Helsinki (both DST changes and ISO year boundaries), all four views and both shifts, a connected overlap chain and touching events, 12 tracked/untracked course colours, and 4 planning cases with deadlines, daily cap, grid, buffers, existing blocks and unplaced work.
- Mutation check: changed the first expected ISO week from 13 to 14; `cd android-native && ./gradlew testProdDebugUnitTest --tests '*CalendarMathGoldenTest'` exited 1, 1 test run and 1 failure. `node android-native/tools/golden.mjs` exited 0 and restored the JS-generated golden; the restored test passed in the full check.
- `npm test`: exit 1, 104 tests, 103 passed, 1 failed, 0 skipped; the explicitly permitted baseline `test/habits.test.js:222` countdown assertion expected 10 days and got 11.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug`: exit 0, BUILD SUCCESSFUL; test XML reports 34 JVM tests, 0 failures, 0 errors, 0 skipped; lint passed. CalendarMath adds one recursive golden test.
- **Remaining Phase 2b:** endpoint and DTO parity audit, Compose Robolectric tests, Roborazzi screenshot infrastructure and screenshot verification. These are outside this authorised split run. The known npm baseline failure is carried under the explicit run instruction. No review or night report was edited.
- **Unverified:** arbitrary malformed inputs, all historical time-zone transitions, and screen or device integration. This split run does not establish full Phase 2b completion.

### Task 4 split — partial endpoint parity (2026-10-30)
- Commit `bf7f58f` adds Retrofit methods for homework bulk planning, habits CRUD/log/order, countdowns CRUD and notification settings. Partial bodies use JsonObject so explicit nulls can clear fields; homework creation includes planning fields. Added a MockWebServer check for bulk planning nulls and habit log path and body. Existing JS-generated snapshot fixtures check habit, countdown, notification and planning response fields.
- `npm test`: exit 1, 104 tests, 103 passed, 1 failed, 0 skipped; permitted baseline countdown assertion at `test/habits.test.js:222` expected 10, received 11.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug`: exit 0, BUILD SUCCESSFUL; 35 tests, 0 failures, 0 errors, 0 skipped; lint passed.
- **Incomplete / unverified:** this is not a complete endpoint and DTO parity audit. Remaining work includes auditing every other route and handler, expanding MockWebServer coverage for all added calls and response shapes, and checking section 4 corrections and delete/catchup semantics in detail. No plan correction is claimed. The untracked night report was left untouched.

### Task 4 — endpoint and DTO audit completed (2026-10-30)
- Implementation commit `b84546c` completes the work started in `bf7f58f`. Calendar import/update responses now decode the numeric `calendar` and flat import summary, and Google Calendar resync decodes `synced`, optional `failed`, and `remaining`. Homework creation requires a due date. Partial homework, attendance, course, calendar and focus settings bodies use `JsonObject` so callers can distinguish omission from explicit null and avoid overwriting unrelated settings. Bulk attendance also accepts null status. Removed obsolete partial request DTOs that could not represent clearing.
- Section 4 corrections: documented flat calendar response and resync fields, required settings fields, idempotent catch-up, and the limited data wipe. No server changes. MockWebServer checks added mutation methods and explicit null bodies, calendar/resync response shapes, catch-up and delete paths; habit/countdown responses are decoded from the existing JS-generated worker snapshot fixture.
- **Method/route audit inventory:**
  - `authConfig`, `exchangeToken`, `logout`, `me`: `src/worker.js` `handleAuth`, `/api/me` dispatch; configuration, code/verifier, token, cookie/bearer logout and nullable user shapes checked. `passkeyRegisterOptions`, `passkeyRegisterVerify`, `passkeyLoginOptions`, `passkeyLoginVerify`, `deletePasskey`: `src/passkeys.js` `handlePasskeyAuth` and `deletePasskey`; options JSON, challenge cookies, app header, response/token and last-passkey restriction checked.
  - `data`: `src/worker.js` `/api/data` query and mapping, `habitQueries`/`habitRow` in `src/habits.js`, `COUNTDOWN_COLUMNS` in `src/countdowns.js`, `listCalendars` in `src/calendars.js`, `focusRow` in `src/focus.js`, and `notifySettings` in `src/notify.js`; fields and SQLite booleans compared to Models and JS-generated fixtures.
  - `listFocus`, `logFocus`, `deleteFocus`, `saveFocusSettings`: `src/worker.js` focus dispatch and `src/focus.js` list/log/delete/save; `since` clamped by server, idempotent `client_id`, row tags and partial merged settings checked.
  - `updateAttendance`, `bulkAttendance`, `updateCourse`, `updateCoursesTracked`: `src/worker.js` attendance/course branches and `src/catchups.js` `setRecordingUrl`; nullable status/tag/recording and bulk status, ownership and return shapes checked.
  - `createCatchup`, `createHomework`, `updateHomework`, `deleteHomework`, `planHomework`: `src/catchups.js` `createCatchup`, `src/homework-store.js` `homeworkInput`, `planInput`, create/update/plan, and `src/worker.js` delete branch. Catch-up returns the existing row for the same session, including a race; homework delete is user-scoped, returns 404 when missing, removes linked Google event and returns `{ok}`. Planning accepts up to 50 items, null clears both fields, and returns only matched rows. Update merges supplied fields over stored values, so explicit nulls matter.
  - `createHabit`, `updateHabit`, `deleteHabit`, `logHabit`, `orderHabits`: `src/habits.js` create/update/delete/log/reorder and `public/habits.js` validation; 0 log count clears, order uses ids, and row / `{ok}` shapes checked. `createCountdown`, `updateCountdown`, `deleteCountdown`: `src/countdowns.js` and `public/countdowns.js` validation; linked IDs, integer booleans and row / `{ok}` shapes checked. `saveNotifySettings`: `src/notify.js` `saveNotifySettings`, `deviceCount` and worker dispatch; merged settings and devices checked.
  - `createBreak`, `deleteBreak`: `src/breaks-store.js` save/remove and worker wrapper; saved row plus session count and restored count checked. `addCalendar`, `updateCalendar`, `syncCalendar`, `updateCalendarFile`, `deleteCalendar`: `src/calendars.js` addLink/addFile/updateCalendar/syncCalendar/replaceFile/removeCalendar and importIcs; flat numeric calendar ID, optional import fields and removed count checked.
  - `resyncGcal`, `disconnectGcal`, `rotateFeed`: `src/gcal.js` resync/disconnect and worker dispatch; resync has no `failed` when no link; disconnect/rotate return `{ok}`. `createWebLink`: worker app-session-only branch validates destination and returns relative URL. `listTokens`, `createToken`, `deleteToken`: `src/tokens.js` list/create/revoke; token is returned only at creation. `updateSettings`, `deleteData`, `deleteAccount`: worker branches, `src/calendars.js` `wipeCalendars`, `src/account.js` `deleteAccount`; settings require both values, data deletion retains homework, account deletion removes user data and revokes Google access best-effort.
  - Section 4's `/api/plan` is deliberately not a Retrofit call (computed locally); browser-only auth redirects and Web Push routes are not native Retrofit calls. No assistant references found under app source. This is a source and fixture audit, not a device or live-service verification.
- `npm test`: exit 1, 104 tests, 103 passed, 1 failed, 0 skipped; permitted baseline `test/habits.test.js:222` expected 10 days, got 11. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug`: exit 0, BUILD SUCCESSFUL; 35 tests, 0 failures, 0 errors, 0 skipped; lint passed.

### Task 2 split — Compose and screenshot infrastructure, incomplete (2026-10-30)
- Added Roborazzi and AndroidX test catalog entries, resource-enabled unit tests, and a state/callback sign-in content layer preserving the ViewModel authentication wiring. Replaced fixed button heights with minimum heights and added scrolling. Extracted the existing authenticated placeholder. Added interaction and screenshot tests; none is ignored.
- Screenshot matrix intended: sign-in, loading, authenticated placeholder and create-account dialog, each at phone 411×891 dp and tablet 840×1000 dp in light and dark; sign-in and placeholder additionally at 200% font. The first full record produced 24 provisional PNGs (16 normal-size and 8 purported 200%); the 200% images were byte-identical to their 100% counterparts and were removed. The remaining 16 are provisional and are not verified goldens. An explicit text-height assertion now fails if the 200% configuration does not increase text size. Dialog screenshots require further visual inspection.
- `npm test`: exit 1; 104 tests, 103 passed, 1 failed, 0 skipped. The permitted baseline `test/habits.test.js:222` expected 10 days and got 11.
- `cd android-native && ./gradlew recordRoborazziProdDebug --no-daemon`: exit 1; 40 tests, 39 passed, 1 failed, 0 skipped, including 9 golden tests, 3 Compose interaction/placeholder tests and 2 screenshot matrix tests. The failing interaction was `SignInUiTest.actionsAndDialog`, `assertIsDisplayed` at the create-account dialog. This run preceded the new font-size assertion and subsequent test changes. The earlier legacy graphics attempt failed with `java.lang.OutOfMemoryError: Java heap space` at `ShadowTrace.endSection`; native graphics was then enabled.
- `cd android-native && ./gradlew recordRoborazziProdDebug --no-daemon --tests '*PhoneScreenshotTest'`: exit 1 after the font-size assertion was added: 1 screenshot test, 1 failure, `AssertionError: 200% font must increase text height`.
- `cd android-native && ./gradlew verifyRoborazziProdDebug --no-daemon --tests '*PhoneScreenshotTest'`: exit 1, 1 screenshot test, 1 failure with the same font-size assertion. The exact unfiltered record and verify commands are `cd android-native && ./gradlew recordRoborazziProdDebug` and `cd android-native && ./gradlew verifyRoborazziProdDebug`; neither has passed.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug --no-daemon` and `cd android-native && ./gradlew verifyRoborazziProdDebug --no-daemon`: interrupted with exit 130 after the phone font-size assertion failed and the dialog test stalled in Robolectric layout. No complete counts are available from these interrupted runs; they do not establish a passing test or verification result.
- `cd android-native && ./gradlew assembleProdDebug --no-daemon`: exit 0, BUILD SUCCESSFUL. Native library strip reported that `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so` were packaged unstripped.
- **Unverified / incomplete:** 200% font rendering, interactive dialog, full screenshot matrix and deterministic verify, the final full unit and lint checks. Work is deliberately uncommitted because required checks did not pass; no review or night report was edited. No Phase 3 screens were added.
- `cd android-native && ./gradlew lintProdDebug --no-daemon`: exit 0, BUILD SUCCESSFUL, run separately after the combined check was interrupted.

### Review 2b fixes — round 1 (incomplete, 2026-10-30)
- Work remains uncommitted on `native/phase-2b`, based on `91cf2b0`; no fix-round commit was made because the required checks did not pass. The review and night report were not edited or staged.
- Habits: distinguish JSON strings from booleans, handle array-to-string numeric coercion, numeric prefixes and non-primitive schedule types; expanded source inputs and regenerated the resource fixture and expected results using `node android-native/tools/golden.mjs` (exit 0). `cd android-native && ./gradlew testProdDebugUnitTest --tests '*HabitsGoldenTest' --no-daemon` passed: 1 test, 0 failures. This is not a claim of exhaustive JS coercion parity.
- UI work in progress: added a no-action-bar application theme; screenshot cases now start separate compositions at fixed scale and compare the actual heading height with a measured 100% baseline. Added 200% sign-in action reachability checks, which have not yet been run. `./gradlew recordRoborazziProdDebug --tests '*PhoneScreenshotTest.light_placeholder_font2' --no-daemon` passed: 1 test. Visually inspected that placeholder and the phone light 200% sign-in image: text is enlarged. Other images remain provisional and need complete visual inspection; the four dialog captures have not been replaced and verified.
- `npm test`: 104 tests, 103 passed, 1 failed, 0 skipped; the allowed baseline countdown assertion in `test/habits.test.js:222` expected 10 and got 11.
- `cd android-native && ./gradlew lintProdDebug assembleProdDebug --no-daemon`: exit 0, BUILD SUCCESSFUL; lint and debug assembly passed.
- Dialog blocker: `./gradlew testProdDebugUnitTest --tests '*SignInUiTest.actionsAndDialog' --no-daemon` with native graphics and the no-action-bar theme failed, 1 test, 1 failure, `AppNotIdleException` at the create-account dialog assertion after 60 seconds. A legacy graphics trial failed with `OutOfMemoryError` in `ShadowTrace.endSection`; a native trial with automatic clock advancement also failed with `AppNotIdleException`. Both trials were reverted. The real dialog and its interaction assertions remain in place.
- `./gradlew recordRoborazziProdDebug --tests '*PhoneScreenshotTest' --tests '*TabletScreenshotTest' --no-daemon` was interrupted after `PhoneScreenshotTest.dark_dialog_font1` reported `AppNotIdleException`; no complete counts or passing matrix are claimed. The prior matrix used provisional captures. Full unfiltered `testProdDebugUnitTest lintProdDebug assembleProdDebug`, unfiltered record, and unfiltered verify were not completed in this round; no screenshot verification or commit is claimed. The fix still needs the dialog idle problem resolved, full matrix recorded and inspected, action reachability and all checks passed before committing and stopping for review.

### Review 2b fixes — round 2 (2026-10-30)
- App and test commit: `af8a72c`, based on `91cf2b0` and the preserved uncommitted round 1 work. Kept the reviewer and night-report copies untracked and untouched.
- The account dialog retains its actual text field, callbacks and dismissal. `usePlatformDefaultWidth = false`, 24 dp side padding and a 560 dp maximum constrain it responsively; the targeted 411 dp interaction now completes. The result is consistent with the width-qualifier reproduction in Robolectric issue #8460, though this check does not establish the internal cause. A theme-coloured outline distinguishes the dialog on the light host. The no-action-bar application theme removes the spurious title bar. Robolectric's full-window captures do not show a dimmed background; real-device window dimming remains unverified.
- Kept isolated 1x and 2x configurations and measured text-growth assertions. At 2x the sign-in tests scroll to and click both create-account and developer sign-in actions and assert their callbacks. The 24 native-rendered PNGs cover 411×891 and 840×1000 dp, light/dark, sign-in/loading/placeholder/dialog, with 2x sign-in and placeholder. Inspected the matrix as contact sheets and all four final dialog images at original size; no title bar or clipped dialog content observed.
- Kept the Habits JSON string/boolean and non-primitive schedule regressions, with expected values generated from JavaScript by `node android-native/tools/golden.mjs` in round 1. This is not exhaustive JavaScript coercion coverage.
- Targeted `cd android-native && ./gradlew testProdDebugUnitTest --tests '*SignInUiTest.actionsAndDialog' --no-daemon`: exit 0, 1 test, 0 failures/errors/skips. An initial run after the sizing fix reached the reopened dialog but failed because the paused test clock had not advanced; adding that advance produced the passing run.
- `npm test`: exit 1, 104 tests, 103 passed, 1 failed, 0 skipped; the permitted time-dependent `test/habits.test.js:222` countdown baseline expected 10 and got 11.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug recordRoborazziProdDebug --no-daemon`: exit 0, BUILD SUCCESSFUL; 62 tests, 0 failures/errors/skips, including 24 screenshot cases, 3 Compose interaction cases, 23 golden tests and 12 other tests. Lint and debug assembly passed; unfiltered record generated 24 PNGs.
- `cd android-native && ./gradlew verifyRoborazziProdDebug --no-daemon`: exit 0, BUILD SUCCESSFUL; 62 tests, 0 failures/errors/skips, unfiltered verification. `git diff --check` passed. No instrumented or physical-device test was run. Stop for independent review; no merge, push or deployment.

### Phase 2b accepted and merged overnight (reviewer, 2026-10-30)
- PASS after two fix rounds, documented in `docs/native-android-review-2b.md`; worker implementation `af8a72c`, log `4fe80d7`. Merged into `native/night` with `--no-ff` as `98b2002`. `main` remains at `e97f6ca`; no push, deployment or remote migration.
- Reviewer regenerated all logic goldens from JS with no changes. Independent phase checks passed: 62 tests, 0 failures/errors/skips; lint and debug APK assembly; full Roborazzi verification of all 24 PNGs. Reviewer inspected all 24 images. A temporary corrupted expected PNG made verification fail; original bytes were restored, then full verification passed.
- Post-merge check in the native/night worktree: `./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug verifyRoborazziProdDebug` — BUILD SUCCESSFUL (34s), 62 tests, 0 failures/errors/skips. Android source is identical to the reviewed phase. `npm test` remains the permitted 103/104 baseline.
- Counts by type: 23 golden test methods; 11 ordinary JVM methods; 1 existing Robolectric ViewModel method; 3 Compose interaction methods; 24 screenshot methods/PNGs; 0 instrumented tests (introduced in Phase 3). Full-window Robolectric dialog dimming remains a physical-device check.

### Phase 3 split — task 1 in progress (2026-10-30)
- Added a session-keyed snapshot repository with cached startup, serialised refresh and optimistic mutations, server-error reporting and an optional full reload. The existing injectable Clock supplies sync time. The activity starts collection after token loading; session changes clear the in-memory snapshot. The cache is keyed by a hash of the session token to prevent reuse by another session.
- `npm test`: exit 1, 104 tests, 103 passed, 1 failed, 0 skipped: the permitted time-dependent countdown assertion in `test/habits.test.js:222`, expected 10 and got 11.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug --no-daemon`: exit 0, 65 tests, 0 failures/errors/skips; lint and assembly passed. The first combined attempt failed 26 tests because Application startup eagerly accessed Android Keystore in Robolectric; startup moved to the activity, and the subsequent full check passed.
- **Incomplete:** Task 1 still requires stronger atomic-write failure testing and review of auth and mutation races; scope and background tasks, shell, search, screenshots and the instrumented/end-to-end harness remain. No device or offline UI was verified. No review or report was edited.

### Phase 3 split — task 2 (2026-10-30)
- Task 1 implementation commit `9d49090`. Task 2 adds a DataStore Count filter using the existing Groups helpers, persistence, calendar reconciliation and an `AppData` filtering seam for subsequent screens. The selected IDs are stored as a set, so their display order is sorted rather than the web's selection order; no UI is built in this split.
- `npm test`: exit 1, 104 tests, 103 passed, 1 failed, 0 skipped; permitted `test/habits.test.js:222` countdown assertion expected 10 and got 11.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug --no-daemon`: exit 0; 66 tests, 0 failures/errors/skips, lint passed. No device or offline UI was verified.

### Phase 3 split — task 6 (2026-10-30)
- Task 2 commit `c069268`. Added unique periodic WorkManager refresh, 60 minutes and connected network; signed-out work exits without fetching. Successful refresh updates the repository snapshot and last-synced flows, the seam for the actual Phase 6 widget. No widget is invented ahead of Phase 6. Scheduling begins when the app activity first starts.
- `npm test`: exit 1, 104 tests, 103 passed, 1 failed, 0 skipped; permitted `test/habits.test.js:222` countdown assertion expected 10 and got 11.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug --no-daemon`: exit 0; 67 tests, 0 failures/errors/skips, lint and assembly passed. The request test checks interval and network constraint; background execution on a device is unverified.

### Phase 3 split — data, scope and background hardening (2026-10-30)
- Task commits: repository `9d49090`, scope `c069268`, periodic refresh `c2746b8`; this entry and the following hardening are committed together. Token initialization now completes before token writes; a 401 clears only the token sent with that request. A session change clears the visible snapshot before waiting for a running operation. A worker starting a fresh process also starts session observation; its initial observation may cause an additional serialized refresh.
- Added real temporary-file coverage for a failed rename after writing the temporary snapshot: the previous file and snapshot survive, the temporary file is removed, and a restarted repository reads the old data. A concurrent refresh waits for an optimistic mutation; delayed MockWebServer 401 from an old token leaves the new token intact. The scope persistence test reopens a DataStore from disk. Existing tests also cover cache before delayed network, server message and rollback, full reload, no-calendar events, all-selected normalization, reconciliation, and the WorkManager request constraints.
- `npm test`: exit 0, 104 tests, 104 passed, 0 failed, 0 skipped. The time-dependent countdown assertion did not fail at this run's time.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug --no-daemon`: exit 0; 72 tests, 0 failures/errors/skips; lint and assembly passed.
- `cd android-native && ./gradlew verifyRoborazziProdDebug --no-daemon`: exit 0; 72 tests, 0 failures/errors/skips, including 24 existing screenshot cases. `git diff --check` passed.
- Decisions and limits: cache filenames use the session-token hash, so a newly issued session starts with an empty cache even for the same account. Scope IDs are stored as a set and exposed in sorted order. The cache records successful full refreshes; a single-row server-applied mutation is held in memory until the next refresh, so an offline restart before that refresh may show the previous snapshot. The snapshot and last-synced flows are the Phase 6 widget seam; no widget has been built here. Real-device background scheduling, offline UI and launcher widget remain **Unverified**.
- Remaining Phase 3 work is the separately assigned shell, navigation, search, screenshots for new screens, and managed-device/end-to-end harness. This split does not claim the entire phase ready. No review or report was edited.

### Phase 3 split — shell, navigation and search (incomplete, 2026-10-30)
- Uncommitted work adds a pure internal-link parser and one JVM test for the requested routes and rejection of malformed or authentication links. It is not yet connected to the activity or manifest and does not preserve pending destinations.
- `cd android-native && ./gradlew testProdDebugUnitTest --tests '*InternalLinkTest' --no-daemon`: exit 0, 1 test, 0 failures/errors/skips. `git diff --check`: exit 0. No full checks were run in this split and no task commit was made.
- **Incomplete / Unverified:** tasks 3, 4 and 5 remain unfinished: shell, repository and scope UI, navigation, intent handling, shortcuts, search, offline UI regression, Compose tests, screenshots, unfiltered recording and verification, lint and assembly. The managed-device and local-server harness remains for the next split. No review or night report was edited.

### Phase 3 task 3 — shell and navigation (2026-10-30)
- Replaced the authenticated placeholder with a lifecycle-collected, session-associated snapshot shell, phone navigation, scrollable wide navigation, Count chips, pull refresh and server-error snackbar. Lists display read-only snapshot data; detailed screen features remain Phase 4. An atomic owner/snapshot flow prevents a previous session's cached data from being rendered on a token change. Cached data remains visible during a delayed refresh, covered by a real repository and MockWebServer Compose test.
- At font scales above 1.3, phone navigation keeps all five icons and accessible labels but hides the visible text to prevent words being clipped; wide navigation grows and scrolls. Uses the injected Clock for dates and updates the current time once per minute. The search button and external links are the following task commits.
- `npm test`: exit 0, 104 passed, 0 failed/skipped. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug recordRoborazziProdDebug --no-daemon`: exit 0, 153 tests, 0 failures/errors/skips, lint and assembly passed, unfiltered record. Counts: 23 golden, 22 other JVM (including the still-uncommitted internal-link test), 1 Robolectric ViewModel, 19 Compose interaction/integration, 88 screenshot cases. `cd android-native && ./gradlew verifyRoborazziProdDebug --no-daemon`: exit 0, same 153 tests, unfiltered verification. All 88 PNGs were inspected as contact sheets in both themes and sizes; 200% title growth is measured, phone navigation and wide Settings are clicked at that scale. An earlier run failed two font assertions because a content row matched the title; semantic tagging fixed the selection. Another run failed two tablet tests because Settings was ambiguous; targeting the rail item fixed it. Initial images exposed clipped large-text navigation and default purple Material colors; the layout and web palette were corrected and re-recorded.
- **Unverified:** device rendering, real sign-in and 401 lifecycle on a device; Phase 3 internal links, shortcuts, search and their additional tests are the next assigned tasks. The managed-device and local-server harness is the following split. No review or night report was edited.

### Phase 3 task 4 — internal links and shortcuts (2026-10-30)
- Task 3 commit `ebf369e`. Added manifest filters and four static shortcuts, validated internal-route parsing, a saved pending destination and a session-scoped navigation history. A link remains pending through sign-in, recreation and an unsuccessful refresh; successful refresh consumes it. The reserved authentication scheme is still handled separately. The session/course and new-homework routes show read-only snapshot content; adding and editing are Phase 4, so the Add homework route does not yet create an item.
- Sign-out now clears the local token before the network request and supplies the captured bearer token to `/auth/logout`; delayed revocation cannot keep the old shell visible or clear a subsequently signed-in session. Revocation remains best effort if the network is unavailable.
- `npm test`: exit 0, 104 passed, 0 failed/skipped. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug recordRoborazziProdDebug --no-daemon`: exit 0, 172 tests, 0 failures/errors/skips, lint and assembly passed; 23 golden, 23 other JVM, 2 other Robolectric, 1 Robolectric ViewModel, 23 Compose interaction/integration, 100 screenshot cases. `cd android-native && ./gradlew verifyRoborazziProdDebug --no-daemon`: exit 0, 172 tests, unfiltered verification. The 12 new route PNGs were inspected as a contact sheet; the previously inspected 88 were re-recorded and verified. Manifest resolution and shortcut XML are tested on Robolectric; the delayed logout test confirms bearer header and session isolation.
- **Unverified:** launcher behavior, activity recreation and authentication callbacks on a physical device; the pending-state test exercises SavedStateHandle and the Compose delivery test, not an instrumented activity. Search remains task 5; the managed-device/local-server harness remains the next split. No review or night report was edited.

### Phase 3 task 5 — global search (2026-10-30)
- Task 4 commit `74dd6c4`. Added a responsive search sheet reached from each app bar, matching the web's accent folding, prefix/word/substring/initial matching, section order and per-section limits. It searches scoped snapshot courses, sessions, homework, active habits and countdowns and opens their read-only routes; navigation, sign-out and new-item route entries are included. Search and Count actions are covered with real Compose interactions. The sheet is captured from the actual shell and its font growth is measured at 200%.
- Pending-link hardening: a new link now starts a fresh session validation and remains pending if that refresh fails or returns 401, even if an earlier refresh had succeeded. A later replacement link cannot be cleared by the older result; explicit sign-out clears the pending link. This may cause an additional refresh after a link. The saved-state and Compose tests cover the decisions separately; an end-to-end 401/sign-in/activity recreation is not yet verified.
- **Deviation / incomplete palette parity:** the web's recent searches, natural-language fallback, calendar upload/sync actions, bulk attendance, habit check-off and focus timer actions are not implemented. Add homework, New habit and New countdown currently open read-only destination shells and do not create items; editing and timer implementations are later phases. The current sheet searches all snapshot sessions rather than the web's next tracked session per summary. These are limitations for review, not claims that those actions work.
- `npm test`: exit 0, 104 passed, 0 failed/skipped. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug recordRoborazziProdDebug --no-daemon`: exit 0, 218 tests, 0 failures/errors/skips, lint and assembly passed; 23 golden, 25 other JVM, 2 other Robolectric, 1 Robolectric ViewModel, 31 Compose interaction/integration, 136 screenshot cases. `cd android-native && ./gradlew verifyRoborazziProdDebug --no-daemon`: exit 0, 218 tests, unfiltered verification. The 136 light/dark phone/tablet PNGs were inspected as contact sheets, with the 16 final search/scope captures re-inspected after palette and outline changes. Robolectric emitted HWUI ripple warnings but the checks passed. The separate dialog window initially ignored the injected font scale and two paused-clock assertions ran before relayout; propagating density and advancing the test clock resolved them. Earlier command attempts from the repository root using `./gradlew` exited 127; the successful commands above run in `android-native`.
- **Unverified:** real-device dialog dimming, keyboard and launcher behavior, external authentication and 401 through activity recreation. No physical device or instrumented test was run. The managed-device and local-server harness remains the next separate split. No review or night report was edited.

- Task 5 implementation commit `cd51b84`. This split is stopped for parent review with the palette parity limitations above; it does not claim full Phase 3 completion. The remaining separate managed-device/local-server harness has not been started.
- Log-only commit prechecks: `npm test` exit 0, 104 passed; `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug --no-daemon` exit 0, 218 tests passed, lint passed.

### Phase 3 split — local harness and managed device (incomplete, 2026-10-30)
- Based on shell commits `ebf369e`, `74dd6c4`, `cd51b84`, `960d3f3` and data commits `9d49090`, `c069268`, `c2746b8`, `4ecfaf6`. Added a Pixel 6 API 35 `aosp-atd` managed device and a local-only isolated server/seed smoke harness. The harness creates a temporary project from `.dev.vars.example`, starts `npm run dev`, executes seed SQL with `wrangler d1 execute DB --local`, and deletes its temporary state after stopping its own server process group. It does not use the existing `.wrangler` state or real `.dev.vars`. The normal command deliberately exits nonzero even if Gradle succeeds because instrumented coverage has not been written. See `android-native/tools/E2E-COVERAGE.md` for the phase-by-phase extension checklist.
- **Incomplete:** no real instrumented tests were implemented; sign-in/PKCE, all destinations, scope, search, deep links and offline restart remain to be implemented. The seed has two group-labelled courses and sessions but does not yet establish a representative semester or actual group-split semantics. API smoke checks courses, homework, habits and countdowns only, not session sources, habit logs or focus records. The APK assembly has no Phase 3 test sources, and is not proof of instrumented coverage. Do not accept this split as complete.
- `npm test`: exit 0, 104 passed, 0 failed/skipped.
- `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug verifyRoborazziProdDebug assembleProdDebugAndroidTest --no-daemon`: exit 0, 218 tests, 0 failures/errors/skips; lint, app assembly, unfiltered screenshot verification and empty AndroidTest APK assembly passed. This is a combined invocation, not a device test.
- `android-native/tools/e2e.sh --smoke`: local HTTP `/api/data` returned seeded courses, homework, habits and countdowns; local D1 execution reported 13 successful commands. The script printed its smoke success and no listener remained on port 8787. The surrounding zsh reporting command exited 1 because it assigned the read-only variable `status` after the script completed; the earlier smoke invocation independently exited 0. No instrumented tests ran.
- `cd android-native && ./gradlew pixel6Api35ProdDebugAndroidTest -PbaseUrl=http://10.0.2.2:8787 --no-daemon`: interrupted while provisioning, no test count. Gradle located SDK manager in `/Users/khanh.dang/.bubblewrap/android_sdk/tools/bin/sdkmanager` and reported `Observed package id 'cmdline-tools;12.0' in inconsistent location ... (Expected .../cmdline-tools/12.0)` then `Preparing "Install Android Emulator v.37.2.13"`; it made no further visible progress for several minutes. This is an interruption, not proof that provisioning is impossible.
- `android-native/tools/e2e.sh`: local acquisition passed, then the same managed-device setup reached `Preparing "Install Android Emulator v.37.2.13"` and was interrupted; 0 device tests executed. After interruption port 8787 was free and no owned Wrangler process remained. **Unverified:** device provisioning, emulator startup, device tests and full e2e. The app's existing palette/UI parity limitations remain for whole-phase review. No review or night report was edited.

### Phase 3 split — managed-device and local-server harness completed (2026-10-30)
- Continues partial harness commit `d201d03` on `native/phase-3`, preserving Phase 3 data commits `9d49090`, `c069268`, `c2746b8`, `4ecfaf6` and shell/search commits `ebf369e`, `74dd6c4`, `cd51b84`, `960d3f3`. Added AndroidX runner, Compose test, Espresso intents and Android Test Orchestrator dependencies. `LocalServerTest` checks a real developer PKCE challenge, the local server's redirect and the app's actual code exchange/callback; seeded Today, Attendance, Homework, Focus, Upcoming, Sessions, Courses, Calendar, Breaks, Habits, Countdowns and Settings; Count scope, search, and Today/Focus/Homework/session/course deep links. Espresso intercepts only the external browser launch: the test fetches the real redirect and sends its actual callback to the exported activity. Real browser/custom-tab integration remains **Unverified**.
- The two test methods run in separate app processes via Orchestrator, with app data retained; the second asserts a different PID, verifies the local server connection is unavailable, and opens cached Today and Homework. A loopback pass-through proxy on 8787 forwards real HTTP to `npm run dev` on 8788 until the first test asks it to close connections. This simulates an unreachable server; it does not simulate all network failures or device radio behaviour. Both ports are checked before startup; the server and proxy run in an owned process group, and cleanup removes only the temporary directory and stops that group.
- `seed-local.mjs` resets only the deterministic `e2e@example.test` account in the temporary local D1 database. It imports two 16-week group calendars via `/api/calendars`, with 48 unique sessions including shared lectures and `GR1`/`GR2` split-course names, selects Group 1 for attendance, and seeds homework, habit/log, countdown, focus and break rows through local D1. The local `/api/data` response is checked for both group memberships, the shared event in both calendars, both split courses and every seeded data category. State and `.dev.vars` are isolated in a temporary project; `.dev.vars` derives from `.dev.vars.example` with `DEV_LOGIN=1`, and the existing project `.wrangler` and real `.dev.vars` are not read or copied. No migrations were edited.
- `npm test`: exit 0, 104 passed, 0 failed/skipped. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug verifyRoborazziProdDebug assembleProdDebugAndroidTest --no-daemon`: exit 0; unit XML 218 tests, 0 failures/errors/skips; lint, debug assembly, unfiltered Roborazzi verification (136 PNGs) and AndroidTest APK assembly passed. The final combined run reused up-to-date unit and verification outputs; the earlier unfiltered combined command in this worker session executed both tasks and passed with the same counts. These checks are worker results, not an independent review.
- `android-native/tools/e2e.sh --smoke`: exit 0; real local `/api/data` verified 48 events and the seeded data and relationships above. Smoke is not a device or full-e2e pass. `android-native/tools/e2e.sh`: exit 0; it invoked `cd android-native && ./gradlew pixel6Api35ProdDebugAndroidTest -PbaseUrl=http://10.0.2.2:8787 --rerun-tasks --no-daemon`. Managed-device XML reports 2 tests, 0 failures/errors/skips: `a_signInAndLiveShell` and `b_cachedOfflineProcessRestart`; a second successful run preceded the final forced run. XML was inspected, not just discovery output. The forced final run executed 69 Gradle tasks and the script verified fresh passing XML. Both 8787 and 8788 had no listeners after completion. An occupied-port check returned exit 1 and left its pre-existing test listener running; failed test and stale-result runs also cleaned up the local server.
- Provisioning succeeded after finding SDK manager under `.bubblewrap/android_sdk/tools/bin`; Gradle installed the emulator and API 35 AOSP ATD ARM image and launched a Pixel 6 with `-no-window`. Initial independent task failed 2/2 when a concurrent default-URL build overwrote the shared APK; subsequent Gradle runs were sequential and a class-level local-URL guard prevents that run from executing the flow. Other retries exposed and fixed ActivityScenario intent tracking, a seeded untracked group, an ambiguous search selector, and the emulator's host route remaining reachable after Wi-Fi was disabled. A diagnostic `uiautomator` dump conflicted with the test accessibility service; it was not used in passing runs. A repeat initially returned nonzero when Gradle marked device tests UP-TO-DATE; the harness now uses `--rerun-tasks` and requires fresh passing testcase XML.
- Extension checklist: Phase 4 adds attendance marking and homework creation/completion; Phase 5 adds focus start, alarm, notification actions and offline outbox replay once; Phase 6 adds widget snapshot; Phase 7 adds class/habit reminders and actions. No such future behaviour is claimed here. **Unverified:** real Google/passkey login, real browser or launcher behaviour, physical-device offline/radio behaviour, and later-phase features. Existing Phase 3 palette/UI parity limitations remain for whole-phase review. No review or night report was edited; stop for independent review.

### Review 3 fixes — round 1 (2026-10-30)
- On `native/phase-3` after `cb8444d`: replaced the manual shell history with Navigation Compose registered destinations and a restored NavController back stack. A displayed pending link is cancelled on Back or another navigation; an unconsumed link stays in the saved pending state through failed validation and reauthentication. The device case now starts with a rejected app token and a session link, signs in via the real local PKCE exchange, and checks delivery. A separate Compose saved-state test covers cached link, Back, and recreation.
- Expanded palette navigation and actions, matching/recents, tracked-session next-or-last selection, and habit/countdown parser fallbacks. Bulk attendance, habit check-off, calendar sync and parsed habit/countdown creation use the API and repository; session fallback filters the tracked session list. MockWebServer verifies the bulk and habit request paths and snapshot update. Feed copying uses the system clipboard. Phase 4 must consume the saved `PaletteAction.OpenForm` payload from the destination entry's `savedStateHandle` (`paletteAction`) for homework add/edit, habit/countdown forms, calendar add/upload, calendar view/planning, and break planning; Phase 5 must consume `PaletteAction.Focus` including homework ID or task text, replace the unavailable-action notice, and select commands according to live timer state. These form and timer actions are not executed in this phase. The new-item deep links likewise show an unavailable notice until Phase 4. Phase 11 still needs durable mutation caching/outbox as previously logged.
- Shell dates use localized date/time formatting and date-only values are parsed as calendar dates, without a time-zone conversion. Lists carry entity IDs as keys and omit the desktop Q/F hints. The date test uses French locale and Honolulu time; the palette test covers recents, parser payloads, session selection and serialized form/focus parameters. **Unverified:** a dedicated two-account switch/restoration test and an actual form/controller consuming those payloads; physical-device launcher, external browser, passkey and Google flows remain unverified. The device reauthentication case uses a rejected local app token and does not prove physical-device identity providers.
- `npm test`: exit 0, 104 passed, 0 failed/skipped. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: exit 0 after the final app changes; lint, both APK assemblies and unfiltered screenshot verification passed. A subsequent `./gradlew testProdDebugUnitTest lintProdDebug --no-daemon` after the payload round-trip test passed; XML reports 225 tests, 0 failures/errors/skips. `recordRoborazziProdDebug` re-rendered screenshots with the existing real 2.0 font-scale harness; 84 changed PNGs were inspected on contact sheets, including the 200% captures and the 12 new-item route captures. HWUI emitted ripple warnings.
- `android-native/tools/e2e.sh --smoke`: exit 0; local HTTP seed verified 48 events and the related seeded categories. Final `android-native/tools/e2e.sh`: exit 0; forced `pixel6Api35ProdDebugAndroidTest` executed 69 tasks, and the script verified fresh XML with 2 tests, 0 failures/errors/skips. The local pass-through proxy and real D1 server remain in use. The SDK manager emitted the existing inconsistent-location warning but the managed device ran. This is a worker result for independent review, not a PASS verdict.

### Review 3 fixes — round 2 (2026-10-30)
- On `native/phase-3` from `b2b1ff3`: timetable clipboard dispatch uses the current Count scope after removing duplicates and invalid calendar IDs and normalising an all-calendar selection to the plain feed URL. The Compose clipboard regression covers a subset, All, invalid IDs and an empty calendar list. The Settings feed-group picker remains Phase 4.
- Registered child routes now select their owning phone tab or tablet rail item, including selected accessibility semantics. Phone and tablet tests cover session, course, homework, habit and countdown detail/new routes, saved-state restoration and Back. Navigation remains a NavHost. Recorded and inspected all 32 changed child-route PNGs in light/dark phone/tablet contact sheets, with an original-size tablet spot check; the unfiltered verification also covers the unchanged 2x main-screen captures.
- Replaced composable SharedPreferences access with a separate Preferences DataStore and lifecycle-collected ShellViewModel state. It stores at most five recent IDs, no session tokens, and derives visible results only from currently available palette items. Tests select a real item through AppNav, replace the ViewModel, reopen Search and check the Recent section; a separate store test checks bounded persistence and unavailable-ID filtering.
- The AppNav integration test loads account A through the real repository, selects a typed Focus action, switches through signed-out state to account B, restores saved UI state, checks B's snapshot and Today route, then opens Focus and checks that A's action was not replayed. The existing genuinely undelivered external-link and 401/sign-in tests remain in place. Phase 4 must consume the saved `PaletteAction.OpenForm` payload for homework add/edit, habit/countdown forms, calendar add/upload/view/planning and break planning; Phase 5 must consume `PaletteAction.Focus` including homework ID or task text and replace the unavailable notice. Phase 11 retains the durable mutation/outbox work described above. No Phase 4/5 form or timer execution is claimed here.
- `npm test`: exit 0, 104 passed, 0 failed/skipped. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: exit 0 after the final test change; XML reports 244 tests, 0 failures/errors/skips, lint, both APK assemblies and unfiltered Roborazzi verification passed (136 screenshot methods). The preceding unfiltered `recordRoborazziProdDebug` passed. Gradle emitted HWUI ripple warnings. Focused runs initially exposed an ambiguous Today selector and a Robolectric paused-main-looper timeout; the selector and test looper handling were corrected and the final suite passed.
- `cd android-native && tools/e2e.sh --smoke`: exit 0; real local `/api/data` verified 48 events, shared/group-specific memberships, homework, habit logs, countdown, focus and break. `cd android-native && tools/e2e.sh`: exit 0; forced local-URL managed-device run executed all 69 tasks; fresh XML reports 2 tests, 0 failures/errors/skips (`a_signInAndLiveShell`, `b_cachedOfflineProcessRestart`). Both local ports 8787 and 8788 were free afterwards. The final strengthened account test changed only JVM test source while the device run was in progress; production source was unchanged. The SDK manager emitted the existing inconsistent-location warning.
- **Unverified / Needs a device:** real Google and passkey providers, external browser/Custom Tab and physical launcher or radio behaviour; later-phase form and timer controllers. The review and night report were not edited. Stop for independent review.


### Reviewer acceptance — Phase 3 (2026-10-30)
- PASS after two fix rounds, accepted6947dcd and merged539dc00f26deea9a59f9bb5c8ddf1f5740402c68 into native/night. Independent npm104/104; forced244 Android tests0 failures/errors/skips, lint/app+test assemblies/unfilteredverify; all136 PNGs inspected, fresh local smoke and2/2 managed-device e2e. Postmerge244 tests and all83 Gradle tasks passed in1m17s. Review3 and night report carry precise logs and feature boundaries. Main unchanged, no push/deploy.
- Next initial split4a implements Today/Attendance/Course detail/Courses/Sessions; wholePhase4 review occurs after every split is implemented. Phase4/5 saved-action form/controller wiring, Phase6 widget publication and Phase11 durable generalized outbox remain mandatory, as logged above.

### Phase 4.1 Today — incomplete working draft (2026-10-30)
- On `native/phase-4` from accepted `native/night` `1ae01e4`, uncommitted. Added a Today screen and ViewModel using scoped shell data, minute-rounded injected clock/zone, Default-dispatcher derivation and existing Plan, Habits, Countdowns and FocusStats ports. It displays the headline, ranked homework and fitting/planned blocks, today's sessions, tomorrow's first session, weekly clashes/load, focus totals, due habits and pinned countdowns. Homework completion and habit checks use repository mutations with optimistic rollback and API error reporting. Navigation uses the existing registered routes.
- **Incomplete:** Today session rows currently only navigate to the existing read-only session route. The web's inline attendance/tag/note/catch-up actions and automatic catch-up behavior have not been implemented. The Focus card has a summary and a link but does not yet offer the web's start/pause control or active timer state; Phase 5 must connect the real controller and its `PaletteAction.Focus` requests. Homework, habit and countdown detail destinations remain their later assigned screens. Do not treat this draft as complete Phase 4.1 or review-ready, and do not commit it as completed.
- Added pinned-clock derived/state tests, Compose habit/navigation coverage, repository-backed mutation rollback checks, and production-shell Today screenshots at phone/tablet sizes, light/dark, 1x/2x, including scrolled captures. The 40 changed/new captures were inspected as contact sheets, with an original-size 2x phone spot check. Screenshot fixture data is synthetic test data, not local-server acquisition. The actual session editing controls and their tests/screenshots remain outstanding.
- `npm test`: 104 passed, 0 failed/skipped. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest recordRoborazziProdDebug --no-daemon`: passed before the final added state test. After that test, `./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon` passed; fresh JVM XML reports 264 tests, 0 failures/errors/skips; lint, both APK assemblies and unfiltered verification passed. Initial attempts failed on obsolete list selectors and missing screenshot-test imports; those were corrected. Gradle emitted HWUI ripple warnings.
- `cd android-native && tools/e2e.sh --smoke`: passed, verifying 48 locally imported events and related local data. `tools/e2e.sh`: passed, forced 69 tasks and fresh XML with 2 tests, 0 failures/errors/skips; the local server/proxy were cleaned up and ports 8787/8788 had no listeners. These existing cases do not test Today mutations. Attendance marking remains for the Attendance run, homework creation/completion for Homework, as recorded in `tools/E2E-COVERAGE.md`.
- **Unverified:** Today session-row actions, live timer behavior and physical-device rendering. No commit, merge, review, night report, migration or server change was made in this draft.

### Phase 4.1 — incremental Today slice (2026-10-30)
- The owner explicitly authorised committing the staged Today slice with the dependencies below; this supersedes the preceding draft entry's instruction not to commit, without changing its historical evidence. This is initial Phase 4 implementation, not a fix round or a whole-phase PASS. `TodayScreen` now gives planned rows keys containing their homework ID and start, and free gaps separate start/end keys. A production Compose render/scroll regression displays two distinct homework items with the same `planned_at`; no JavaScript or Kotlin planning maths changed.
- **Required before whole Phase 4 review/PASS:** Sessions 4.5 must build the shared attendance, tag, note and catch-up controls, including the web's automatic catch-up behavior, and integrate them into Today session rows. Homework, Habit and Countdown detail/forms remain their assigned Phase 4 runs. The next implementation run is Attendance 4.2. **Phase 5:** integrate live timer state and real controller actions into Today, and consume the saved `PaletteAction.Focus` requests. None of these dependencies is waived or claimed complete; no whole Phase 4 merge is authorised by this slice.
- After the key fix: `npm test` passed 104/104, 0 skipped. Focused `./gradlew testProdDebugUnitTest --tests '*TodayUiTest.overlappingPlannedItemsBothRenderAndScroll' --no-daemon` passed. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon` passed; fresh JVM XML reports 265 tests, 0 failures/errors/skips, both APKs assembled, lint and unfiltered Roborazzi verification passed. The previously recorded 40 changed/new PNGs remain unchanged by the key fix; all were previously inspected in contact sheets and original-size 2x phone/tablet captures were additionally inspected in this run. The fixtures are local test fixtures, not acquired records.
- `cd android-native && tools/e2e.sh --smoke` passed against isolated local D1/HTTP, verifying 48 events and related seeded data. Full `tools/e2e.sh` passed with all 69 Gradle tasks executed; fresh managed-device XML contains `a_signInAndLiveShell` and `b_cachedOfflineProcessRestart`, 2 tests, 0 failures/errors/skips. Ports 8787/8788 had no listeners after cleanup. The SDK inconsistent-location and HWUI ripple warnings appeared but did not fail the checks. These device cases do not claim attendance marking or homework creation/completion; those belong to their assigned runs.

### Phase 4.2 — incomplete work in progress (2026-10-30)
- On `native/phase-4` at `dd548ff`, added an explicit `TodayScreen` empty-data Compose test and changed Today end screenshots to scroll to the final weekly sentence. The two changed tablet 1x PNGs were inspected individually; the whole sentence is visible. The synthetic screenshot fixture is not acquired data. The existing overlapping planned-task regression was retained.
- `cd android-native && ./gradlew testProdDebugUnitTest --tests '*TodayUiTest' --no-daemon`: passed (3 tests). `./gradlew recordRoborazziProdDebug --tests '*today_end*' --no-daemon`: passed; only the two tablet 1x images changed. An earlier combined filtered command failed because its `--tests` filter selected no tests; no passing result is claimed for it.
- **Incomplete:** Attendance 4.2 screen, its tests and local-server marking coverage have not been implemented. Sessions 4.5 still must integrate its shared full session row into both Attendance and Today before whole Phase 4 PASS. No commit or review is claimed; required full checks and e2e were not run in this work.

### Phase 4.2 Attendance — initial implementation (2026-10-30)
- On `native/phase-4` from `dd548ff`, implemented the Attendance overview using the ported statistics and group logic, scoped events and the injected minute-rounded shell clock/zone. It shows overall projection and risk wording, unmarked count and real bulk action, Today/Next and homework navigation/toggle, group prompt, and risk-ordered course cards with group/tag chips, threshold marker and detailed counts. Calendar chips use the unscoped event memberships even when Count filters the statistics. The server remains authoritative: bulk actions use the existing optimistic repository mutation, error rollback and reload. Course/session navigation uses registered staged destinations. The optional-tag bulk eligibility follows `unmarkedEvents`, which differs from the statistics' optional counting. No JS maths or goldens were changed.
- The explicit empty Today Compose test and scrolled final weekly-sentence captures from the preceding draft are retained, as is the overlapping planned-task regression. Attendance tests cover empty/populated state, filtering/scope, risk ordering, chosen/unpicked groups, hours, threshold marker, pinned-clock ViewModel state, and the production screen -> ShellViewModel -> DataRepository -> MockWebServer success/400 rollback and exact snackbar. The previous PaletteDispatchTest race was fixed by advancing the test scheduler while waiting for the actual queued request; assertions were retained. The phone bottom navigation now has stable route tags for unambiguous device selection.
- Screenshot capture uses synthetic fixtures, not acquired records. Inspected all 42 changed/new PNGs on contact sheets and original-size phone 2x light/dark action images; the 12 action captures were re-recorded with three-digit counts and inspected again. They include phone/tablet, light/dark, 1x/2x, empty, scrolled course content, group prompt and bulk action. The tablet Today footer images show the full weekly sentence. `./gradlew recordRoborazziProdDebug --tests '*ScreenshotTest.*attendance*' --no-daemon` and the later `--tests '*ScreenshotTest.*attendance_action*'` passed; unfiltered verification below passed against the final images.
- `npm test` (final run): 103/104 passed, 1 failed, 0 skipped; the sole failure is the documented time-of-day countdown assertion at `test/habits.test.js:222` (expected 10, actual 11). An earlier run passed 104/104. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: passed after final source and screenshot changes, 83 tasks (5 executed, 78 up-to-date); fresh JVM XML totals 300 tests, 0 failures/errors/skips. By class-name grouping: 180 screenshot, 23 golden, 60 Compose/UI/navigation/dispatch, 37 other unit/repository tests; group labels are descriptive and may include Robolectric in the latter category. Lint, both APKs and unfiltered screenshot verification passed. HWUI ripple warnings appeared.
- `cd android-native && tools/e2e.sh --smoke`: passed isolated local D1/HTTP seed acquisition, 48 events and shared/group-specific memberships. `tools/e2e.sh`: passed, 69 Gradle tasks executed; fresh managed-device XML has 2 tests, 0 failures/errors/skips (`a_signInAndLiveShell`, `b_cachedOfflineProcessRestart`). The live case marks attendance through the actual screen, waits for the serialized mutation and refresh, compares every selected and nonselected event status against local `/api/data`, and checks the refreshed snapshot. The offline case uses a separate process after the proxy cutoff. Ports 8787 and 8788 were free after cleanup. The earlier full device attempt reached the persistence checks but failed on an ambiguous Homework navigation label, and its dependent offline case failed for lack of the restart marker; route tags corrected it before the passing run. The SDK inconsistent-location warning appeared.
- **Required before whole Phase 4 review/PASS:** Sessions 4.5 owns the shared full session row, attendance/tag/note/catch-up controls and automatic catch-up, integrated into Today, Attendance and Course detail. Homework 4.7 owns the rich shared homework row/forms/actions in Today and Attendance, including notes, course, due time, reminders, sync, focused minutes, recording, edit and delete. Phase 5 owns live Focus state and controller actions, including Today and saved Focus payloads. Later screens and saved `PaletteAction.OpenForm` consumers remain in plan order; Course detail 4.3 is the next assigned slice. No whole Phase 4 PASS or merge is claimed. **Needs a device:** physical-device rendering, real Google/passkey providers, external browser, launcher and radio behaviour.

### Phase 4.2 — equal-snapshot stabilization (2026-10-30)
- Initial implementation follow-up to `75fa0cf`, not a formal Phase 4 fix round. A production `Shell`/Attendance Compose regression re-emits equal `AppData.copy()` snapshots while toggling loading with a fixed clock, then checks a changed event snapshot and null/new-account replacement. Before the fix, `cd android-native && ./gradlew testProdDebugUnitTest --tests '*AttendanceTest.shellRecomposesEqualRefreshAndClearsForAccountReset' --no-daemon` failed 1/1 at the course-stat visibility assertion immediately after the first equal loading snapshot. After changing Attendance screen state matching and ViewModel memo/clearing comparisons to structural equality, the same command passed 1/1. The generation guard remains in place; changed snapshots still re-derive and null clears the prior account.
- `npm test`: 103/104 passed, 1 failed, 0 skipped; the only failure was the documented time-of-day countdown assertion at `test/habits.test.js:222` (expected 10, actual 11). `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: passed, 83 tasks (12 executed, 71 up-to-date), fresh JVM XML 301 tests, 0 failures/errors/skips; lint, both APK assemblies and unfiltered screenshot verification passed. No PNG changed, so no new visual inspection was required. HWUI ripple warnings appeared.
- `cd android-native && tools/e2e.sh --smoke`: passed isolated local HTTP/D1 acquisition with 48 events and seeded relationships. `tools/e2e.sh`: passed, 69 tasks executed; fresh managed-device XML has 2 tests, 0 failures/errors/skips (`a_signInAndLiveShell`, `b_cachedOfflineProcessRestart`), including actual Attendance bulk marking, local API status comparison and refreshed snapshot, then separate-process offline cache restart. Ports 8787/8788 were free after cleanup; the SDK inconsistent-location warning appeared.
- Required owners remain unchanged: Sessions 4.5 integrates the shared full session row and actions into Today, Attendance and Course detail; Homework 4.7 integrates rich shared homework rows/forms/actions into Today and Attendance; Phase 5 integrates live Focus controller behavior. All other Phase 4/8.3 screens and saved form consumers remain outstanding. No whole Phase 4 PASS or merge is claimed; physical-device/provider limits remain as previously recorded.

### Phase 4.3 Course detail — initial implementation (2026-10-31)
- On `native/phase-4` from `167c21bbadc38bb3aec07fdbd8167150dae1639c`, the existing `course/{id}` production destination now derives the selected course's statistics and all-time scoped sessions on `Dispatchers.Default` using the injected minute clock and zone. It displays threshold, projected rate, skip/absence limit, missed mandatory/exam, untracked and no-attendance contexts; full calendar memberships supply group labels separately from scoped statistical events. Missing IDs show a back path. Attendance cards, palette and links continue through the same route; session rows navigate to the existing session destination.
- Added course tag and tracking PATCH controls, family selection POST with all sibling IDs, and recording add/edit/remove plus external ACTION_VIEW. Repository mutations optimistically merge the local patch, roll back with server error on failure and reload canonical server data after success. Group selection reloads all rows. Screen state uses structural equality and a generation guard; the screen is keyed to the session for account replacement. The responsive recording dialog uses the existing explicit-width pattern and passes 200% font density to its content. Group choices scroll. No JS mathematics or golden values changed.
- Focused tests cover the actual Course ViewModel state with pinned minute/zone, missing/untracked/scoped data and mandatory misses; production Shell recomposition on equal and changed snapshots, null and replacement account; controls and session navigation; actual repository + MockWebServer tracked rollback/message and success, tag PATCH, group family IDs/selection/not-in-course and unaffected rows, recording add/edit/remove canonical refresh and invalid URL rollback; and ACTION_VIEW intent. Real local device coverage opens the course deep link, changes its tag, waits for serialized refresh and checks local `/api/data`. This is an initial slice, not a formal fix round or whole Phase 4 PASS.
- `npm test`: 103 passed, 1 failed, 0 skipped; sole failure is the documented countdown time-of-day assertion at `test/habits.test.js:222` (expected 10, actual 11). `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: exit 0, 83 tasks (15 executed, 68 up-to-date); fresh unit XML 344 tests, 0 failures/errors/skips. Lint, both APK assemblies and unfiltered screenshot verification passed. HWUI ripple and existing SDK location warnings appeared. The pre-final full attempt failed 1/344 on an asynchronous existing Shell Attendance selector; a wait for the same assertion fixed the race. An earlier full recording run failed on the obsolete course placeholder assertion and popup screenshot roots; the assertion was updated for the real destination and selectors now use responsive dialogs. The original recording form hit Robolectric `AppNotIdleException` repeatedly; the explicit dialog width fixed the measurement loop, and the edit/save/remove targeted test passed. A first focused repository extension timed out waiting for the main looper; advancing the looper while waiting fixed it. These failures are not passing evidence.
- `./gradlew recordRoborazziProdDebug --tests '*CourseScreenshotTest*' --no-daemon`: passed after final production changes. Inspected all 40 new/changed course captures as contact sheets and original 2x phone recording and group dialog; synthetic fixtures show phone/tablet, light/dark, main and form 2x, long labels, empty and missing states, recording link and tag/group selectors. No acquired user data is represented by those fixtures.
- `cd android-native && tools/e2e.sh --smoke`: exit 0; isolated local HTTP/D1 verified 48 events and seeded memberships and related data. `tools/e2e.sh`: exit 0; 69 Gradle tasks executed; fresh managed-device XML reports 2 tests, 0 failures/errors/skips (`a_signInAndLiveShell`, `b_cachedOfflineProcessRestart`). Existing PKCE, Attendance and offline process checks remain in those cases. No listener remained on 8787 or 8788 afterwards. Actual browser/physical device, real identity providers, and physical radio behaviour remain **Unverified**.
- **Remaining owners before whole Phase 4 review/PASS:** Sessions 4.5 integrates shared full session rows and attendance/tag/note/catch-up actions into Course detail, Today and Attendance; Homework 4.7 integrates rich homework rows/forms into Today and Attendance; Phase 5 integrates the live Focus controller. Courses 4.4, the rest of Phase 4/8.3 and saved form consumers remain assigned later. No merge, deployment, remote migration or review/night-report edit was performed.
- Final pre-commit sequence after the device run: `npm test` again reported 103/104 with only the known countdown failure and 0 skipped; `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug --no-daemon` passed (38 tasks, 13 executed), fresh unit XML 344 tests, 0 failures/errors/skips.

### Phase 4.4 Courses — initial implementation (2026-10-31)
- Implemented Courses on the existing `native/phase-4` branch from `53399ce`. Its dedicated ViewModel derives every course, scoped counts, hours and projections on `Dispatchers.Default` using the shell's injected minute clock and zone. Structural equality and generation guards retain equal refreshes and prevent stale derivations; session-keyed screens clear on account replacement. Course detail uses structural screen matching and invalidates stale derivations. Full-event calendar memberships remain separate from scoped statistics. No web maths or golden fixtures changed.
- Reused Course detail tracking, tag and recording controls and extracted the family selector for both screens; existing `CourseField` and `CourseGroups` actions perform optimistic changes, server-error rollback, and canonical refresh. Families submit all sibling IDs and preserve other families. Rows remain present when untracked and distinguish calendar attendance from tracking. The empty list uses the closest `renderEmpty` timetable sentence: the web intercepts Courses before `renderCourses` when no scoped events exist. The requested `public/groups.js` does not exist in this checkout; group membership/scope helpers are in `public/app.js`, and group family splitting/statistics in `public/stats.js`.
- Pinned-clock StateFlow and production Compose tests cover scope, natural group order, selected/unselected/several, no-attendance/untracked, equal snapshots and account clearing, 200% action reachability, and real repository + MockWebServer tracked/tag/group/recording success, rollback, request bodies and canonical reload; existing Course detail ACTION_VIEW intent tests remain. Updated the old shell captures to wait for derived screen content. Initial full verification failed 5/376: four older Course shell screenshots captured before the derived screen and one existing Course repository test scrolled during rederivation after a recording refresh. The captures and test now wait for actual content; the later full run passed.
- `npm test`: exit 0, 104 tests, 104 passed, 0 failed/skipped. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: exit 0, 83 tasks; fresh JVM XML 381 tests, 0 failures/errors/skips, lint, both APKs and unfiltered screenshot verification passed. Earlier focused Course and Courses repository/state tests passed; an initial screenshot record failed to compile due to a test selector and was corrected.
- Recorded and inspected all 68 new/changed PNGs as contact sheets, with original-size phone 200% and recording form checks. They cover light/dark phone/tablet Courses, empty, group/tag/recording dialogs, long names, 123-session rows and 200% main/row; Course detail shared-control captures were refreshed. Screenshot data is synthetic, not acquired records. Robolectric full-window captures do not capture the system dim layer; real-device dimming and physical rendering remain **Unverified**.
- `cd android-native && tools/e2e.sh --smoke`: exit 0, isolated local HTTP/D1 verified 48 events and seeded memberships. `cd android-native && tools/e2e.sh`: exit 0, 69 tasks executed; fresh managed-device XML has 2 tests, 0 failures/errors/skips. Live local-server case changes and restores tracking from Courses, waits for optimistic publication and serialized `repository.refresh`, checks every changed/unchanged course against `/api/data`, and retains PKCE, Attendance and Course detail tag checks; offline case starts a fresh process. No listeners remained on ports 8787/8788. SDK location and HWUI ripple warnings appeared.
- Remaining owners are not waived: Sessions 4.5 must build the shared full attendance/tag/note/catch-up/automatic catch-up row for Today, Attendance and Course detail; Homework 4.7 must integrate rich shared rows/forms/actions into Today and Attendance; Phase 5 owns the live Focus controller. All later Phase 4/8.3 screens and Phases 5/6/7/8/10/11/12/13 remain required. This is an initial task slice, not a formal fix round or whole Phase 4 PASS; no merge, deployment, reviewer/night-report edit or remote migration was performed.
- Final pre-commit checks after strengthening exact request-body assertions and the complete untouched-course device comparison: `npm test` exit 0, 104/104, 0 skipped; `cd android-native && tools/e2e.sh` exit 0, fresh managed-device XML 2/2, 0 failures/errors/skips; `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon` exit 0, 83 tasks (17 executed, 66 up-to-date), 381 JVM tests in the preceding fresh XML with 0 failures/errors/skips. No JS fixture or server changes.

### Phase 4.5 Sessions — initial implementation and stabilization (2026-10-31)
- Implemented Sessions on the existing `native/phase-4` branch from `73b55a7`: minute/zone-derived scoped filtering, course statistics, group banner, search and account-owned saved filter state. Added one shared production session row to Sessions, Today, Attendance and Course detail, and a real session detail dialog with tag, note, due-homework and catch-up controls. Rows offer status toggles, accessible selection and swipe actions; swiping right toggles present and left toggles absent. Bulk actions operate on shown or selected IDs, prune selection after filtering and reload canonical data. The web's shown-list eligibility and overview bulk eligibility deliberately remain distinct.
- Session deep links resolve the signed-in account's full event set even when Count excludes the event; lists and statistics remain scoped. Account ownership is captured for every queued dispatch and checked under the repository mutex. SessionSearch is consumed once from its NavHost entry; filters survive navigation/restoration and clear for a replacement account. Status/tag JSON distinguishes explicit null from omitted fields.
- Preserved the source-derived `public/app.js` sequencing: for a past absent request, a rejected attendance PATCH rolls the event back and exposes the real server error, but still attempts the automatic catch-up. The server supplies the canonical catch-up; future skips do not create one, changing away removes only untouched unfinished catch-ups, explicit removal uses the homework DELETE, and bulk does not trigger automatic catch-ups. Deferred errors retain delivery order and account ownership.
- Tests cover pinned-clock filtering, ordering, group/banner and unit cases; production shared-row dispatch, both swipes, live selection filtering and exact bulk IDs, sheet actions and note length, restored navigation and scoped deep links, account replacement, MockWebServer success/rollback/errors and catch-up cases. JS golden generation remained unchanged. Screenshots use synthetic fixtures, not acquired records. Inspected all 134 changed/new PNGs across phone/tablet and light/dark as contact sheets, including the eight newly recorded 200% session sheet/scrolled captures; the latter hashes are in `/tmp/epita-native-night/sessions-final-2x-hashes.txt`. Physical rendering and system dimming remain **Unverified**.
- Earlier attempts: initial screenshot recording had 4/24 popup-root failures, corrected by the responsive dialog; an initial full Android run had 2/408 obsolete detail/selector failures, corrected for the real dialog; one unfiltered record was interrupted when a test blocked the main dispatcher and was fixed. Earlier local device attempts failed 2/2 on an ambiguous Group 1 selector, then 2/2 when the untracked group was hidden by the new default; the dependent offline test in each lacked its restart marker. Both cases subsequently passed. The independent reviewer forced run reported 10/441 failures, 0 errors/skips: eight missing 200% baselines and two asynchronous loading captures. Recorded the eight and made Shell captures await derived Sessions/Attendance content; neither loading image was recorded as a baseline.
- Final `npm test`: 103/104, 1 failure, 0 skipped; only the documented time-dependent countdown assertion at `test/habits.test.js:222` failed (expected 10, actual 11). `cd android-native && ./gradlew recordRoborazziProdDebug --tests '*SessionsScreenshotTest.lightPastSheet2' --tests '*SessionsScreenshotTest.darkPastSheet2' --tests '*SessionsScreenshotTest.lightPastSheetScrolled2' --tests '*SessionsScreenshotTest.darkPastSheetScrolled2' --no-daemon`: passed, 8 captures. `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --rerun-tasks --no-daemon`: exit 0, 83 tasks executed; fresh XML 441 tests, 0 failures/errors/skips. Lint, both APKs and unfiltered verification passed. HWUI ripple and SDK location warnings appeared.
- `cd android-native && tools/e2e.sh --smoke`: exit 0; isolated local HTTP/D1 verified 48 events and memberships. `cd android-native && tools/e2e.sh`: exit 0, 69 tasks executed; fresh managed-device XML 2 tests, 0 failures/errors/skips (`a_signInAndLiveShell`, `b_cachedOfflineProcessRestart`). The first case retains developer PKCE, overview bulk, Courses tracking restore, Course tag, scope and pending-link checks, and exercises the actual session status/catch-up/tag/note path against the local API after serialized refresh. The second uses a fresh process after the local proxy cutoff. Real external identity providers and physical-device behaviour remain **Unverified**.
- This is initial task 4.5 stabilization, not a formal fix round or whole Phase 4 PASS. Homework 4.7 still owns rich shared rows/forms/actions in Today and Attendance, and Phase 5 owns live Focus hooks. Upcoming 4.6 awaits assignment; every other Phase 4/8.3 screen and all later planned phases remain required. No merge, push, deployment, remote migration, reviewer or night-report edit was performed.

### Phase 4, task 4.6 — Upcoming (implementation; review pending)

- Replaced the shell placeholder with a Monday-based Upcoming week. The account-keyed Shell retains its nonnegative offset across navigation, recreation and transient null data, and resets for a different account. The per-account screen model derives snapshots on `Dispatchers.Default`, using an injected clock and zone, invalidating stale generations and retaining equal snapshots. Local `LocalDate.atStartOfDay(zone)` boundaries include midnight homework and exclude the next Monday through both DST transitions. Tracked scoped sessions retain chronological order, including no-attendance courses; unfinished homework follows each day's sessions. The banner uses the web's global tracked-group predicate. Day-strip counts expose the full count and cap visible dots at four; past dates are disabled and selecting a day scrolls to its keyed section. Same-month and cross-month/year labels use the device locale.
- Reused the actual shared `SessionRow`, Shell dispatch and `SessionDetail`, including status, note, tag and catch-up actions. Homework rows show existing due, note, reminder, course, sync, planned and focus metadata, open catch-up recording links, dispatch the existing done mutation, and navigate to the existing homework detail route. The rich shared homework detail/forms/actions are still task 4.7's responsibility and must be integrated back into Upcoming; live focus controls remain Phase 5. The existing homework detail destination is a placeholder until 4.7. Stable list keys include entity types so overlapping numeric IDs remain distinct.
- Added pinned-clock real-model tests for 23/25-hour DST days, midnight, next-Monday exclusion, ordering, tracked/no-attendance filtering, empty days, year rollover and equal/changed/null/new-owner snapshots; production Compose tests cover empty/populated views, week controls, day jump, global banner, accessible full count, 200% controls, Shell navigation/restoration/account replacement, actual recording URL handling and homework completion dispatch. A production Shell -> real ShellViewModel/repository -> MockWebServer test observes the attendance PATCH. The local managed-device live case now advances and returns a week and jumps to the current day against seeded local data; all prior assertions and the fresh-process offline case remain.
- Recorded and inspected all 20 new or changed Upcoming PNGs, including the four older Shell captures, light/dark phone 411×891 and tablet 840×1000, 200% main views, empty views, a homework row and a later week. The strip scrolls horizontally at 200% and the phone controls wrap. Physical-device rendering and external identity providers remain **Unverified**.
- `npm test`: final exit 0, 104 tests, 104 passed, 0 failed, 0 skipped. An earlier run had 103/104, 1 failure, 0 skipped: only the known time-dependent `test/habits.test.js:222` countdown assertion, actual `[ 'Exam', 11, true ]`, expected `[ 'Exam', 10, true ]`.
- `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --rerun-tasks --no-daemon`: final exit 0, 83 tasks executed in 1m 57s; fresh unit XML 466 tests, 0 failures, 0 errors, 0 skips. Lint, both APKs and unfiltered Roborazzi verification passed. An earlier run failed instrumented Kotlin compilation on a test-only `getOrNull` selector; fixed by waiting for the next Monday and asserting the Previous control. The next full run had 464/465 tests passing: the new Shell navigation test had an ambiguous duplicate session title; fixed by selecting its stable session ID, then reran successfully. SDK location and HWUI ripple warnings appeared.
- `cd android-native && tools/e2e.sh --smoke`: exit 0; isolated local HTTP/D1 verified 48 seeded events and memberships. `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk tools/e2e.sh`: exit 0, 69 tasks executed in 2m 15s; fresh managed-device XML 2 tests, 0 failures, 0 errors, 0 skips (`a_signInAndLiveShell`, `b_cachedOfflineProcessRestart`). Primary production Gradle and the local-URL APK build ran separately.
- Initial task 4.6 implementation only; no whole-Phase-4 review, merge, push, deployment, remote migration, reviewer or night-report edit. Remaining Phase 4/8.3 screens and later phases remain required.

### Phase 4.6 Upcoming — initial stabilization (independent review pending)

- Kept the production `LazyListState` alive while a null or changed derived snapshot shows loading; a separate offset effect resets the agenda to the top on a week change. The existing account-owned week state, day jumps and Prev/Next/Today behavior remain. The bottom Next week button has a test tag for its real scrolled-to action.
- Before the fix, the new production Compose/real UpcomingViewModel/repository regression jumped past eight earlier rows to Friday, forced and observed a null model state, restored its snapshot, and failed `assertIsDisplayed` for `Friday lecture` at `UpcomingRepositoryUiTest.kt:110`: `Assert failed: The component is not displayed!` (1 test, 1 failure; `/tmp/epita-native-night/upcoming-scroll-before.log`). After the fix the same regression passes through that null state, a real ShellViewModel attendance PATCH to MockWebServer, and a changed canonical `/api/data` refresh, asserting Friday stays displayed and the canonical note appears. A second production Compose test checks that the actual bottom Next week action and offset changes reset the list to its header, and exercises Previous and Today. The two targeted tests passed. While constructing the harness, an expression-bodied test initially was not discovered because it did not return Unit, and blocked-main-looper waits timed out; the corrected harness returns Unit and idles Robolectric's main looper while awaiting real derivations. One initial after-fix run also timed out awaiting the mutation for that same looper reason; the final unchanged visibility assertions passed.
- No screenshots were regenerated or spinner baselines recorded. Unfiltered Roborazzi verification passed, and all 312 PNG SHA-256 values still match `/tmp/epita-native-night/upcoming-root-current-hashes.json`, with no missing or additional PNGs. The independent reviewer's 20 Upcoming captures and image-review manifest therefore remain applicable. Physical-device rendering remains **Unverified**.
- `npm test`: exit 0, 104 tests, 104 passed, 0 failed, 0 skipped. `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --rerun-tasks --no-daemon`: exit 0, 83 tasks executed in 2m 13s; fresh XML 468 tests, 0 failures, 0 errors, 0 skips. Lint, both APKs and unfiltered screenshot verification passed. HWUI and SDK location warnings appeared.
- `cd android-native && tools/e2e.sh --smoke`: exit 0; local HTTP/D1 verified 48 seeded events. `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk tools/e2e.sh`: exit 0, 69 tasks executed in 2m 21s; fresh managed-device XML 2 tests, 0 failures, 0 errors, 0 skips (`a_signInAndLiveShell`, `b_cachedOfflineProcessRestart`). The existing E2E coverage description remains accurate; the live case retains local API week/day navigation and the second case retains its fresh-process offline proof. Production Gradle and the local-URL APK build ran separately.
- Homework 4.7 must replace the temporary UpcomingHomework helper with the real rich shared homework row across Homework, Today, Attendance and Upcoming, including fractional focused minutes and web `hrs` formatting, edit/delete/Focus and planned hooks; Phase 5 and Calendar 4.12 own their corresponding live integrations. The current helper does not establish rich-row parity. This is initial 4.6 stabilization, not a formal phase fix round or whole-Phase-4 review; no next screen, merge, push, deployment, reviewer or night-report edit was performed.

### Phase 4.7 Homework — incomplete work in progress (2026-11-01)
- On `native/phase-4` from `6d4d5be2cbd14e10a642eb055733e79693123bcd`, added an unconnected bucket derivation matching the web's done-first and overdue boundaries, rounded local-midnight difference across DST, preserved open-item input order and reverse due order for Done. Missing or invalid canonical dates currently fall into Later; this defensive choice still needs review against actual server data. It is not yet used by a screen or ViewModel.
- `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest --tests '*HomeworkBucketsTest' --no-daemon`: exit 0, 1 test, 0 failures/errors/skips. It exercises exact due instant, next-seven-day cutoff, Done precedence/order, and spring/autumn DST boundaries. This focused result is not the required full check.
- **Incomplete:** the actual Homework ViewModel, screen, forms, shared row, navigation, account-owned drafts and undo deletion, API integration, screenshots, full checks and local managed-device proof have not been implemented or verified. No task completion, commit, review or Phase 4 PASS is claimed. The current changes are uncommitted for a subsequent worker; the normal pre-commit checks were not run.

### Phase 4.7 Homework — finalization check blocked (2026-11-02)

- The implementation following the preceding incomplete entry now includes the Homework buckets, shared rows across Homework/Today/Attendance/Upcoming, add/edit forms and canonical API mutations, account-owned drafts and navigation, saved-action handling, native date/time pickers, sync warnings, and confirmation followed by a five-second deferred delete with Undo. The done toggle sends only `done`, matching the web; date-form writes send the device time zone and explicit nulls for cleared optional fields. Pending Focus homework IDs and Calendar planned timestamps remain for Phase 5 and task 4.12 to consume. The delayed delete is not a durable offline outbox; Phase 11 owns that work.
- Previous worker evidence for the same app/test source: `/tmp/epita-native-night/homework-final-forced7.log` passed the forced production unit/lint/both APKs/unfiltered Roborazzi command, 83 tasks executed, with fresh XML 544 tests, 0 failures/errors/skips (2026-11-01 22:07:57–22:09:22 UTC); 23 golden and 364 screenshot methods. `/tmp/epita-native-night/homework-final-smoke.log` passed local HTTP/D1 acquisition of 48 events, and `/tmp/epita-native-night/homework-final-device2.log` passed both managed-device cases, 0 skipped, with real native homework create/complete, canonical and untouched-row comparisons and the fresh-process offline case. An earlier device attempt failed a course-text wait outside the viewport; the corrected test scrolls to the course and back while retaining assertions. Worker-reported visual inspection covered 140 new/changed PNGs; independent review of current hashes is still pending. Physical-device behaviour and external identity providers remain **Unverified / Needs a device**.
- Regression evidence: `/tmp/epita-native-night/homework-prefill-before.log` failed `matchingSavedFormActionIsConsumedOnceWithoutClearingOtherOwners`; `/tmp/epita-native-night/homework-prefill-after.log` passed after the fix. An earlier unfiltered recording run failed the ambiguous duplicate Today `Prepare report` selector; selecting the first matching navigation target retained the route assertion, and the historical forced run above passed. No assertion was skipped.
- Fresh pre-commit `npm test`: exit 0, 104 tests, 104 passed, 0 failed, 0 skipped. Fresh `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest lintProdDebug --no-daemon`: exit 1, 544 tests, 2 failures, 0 skipped; lint did not reach its final task. `ShellUiTest.scopeNavigationAndSignOut` at line 169 matched two nodes containing `First` (the session row and `scope-1` checkbox), so touch injection failed. `PaletteTest.searchesEachEntityAndRoutesToItsItem` at line 14 failed assertion `amphi`. Full output: `/tmp/epita-native-night/homework-finalization-gradle.log`; fresh XML contains the assertion details. These results supersede the older passing proof for the pre-commit decision.
- Stopped without changing app/test code or committing; reviewer must assign any repair and rerun required checks. This is not a whole Phase 4 PASS or formal fix round. Remaining Phase 4/8.3 work and later phases remain required; Phase 9 stays skipped for this run.

### Phase 4.7 Homework — initial test stabilization and final checks (2026-11-02)

- Repaired the two failures in the preceding entry without changing production code or assertions: `ShellUiTest.scopeNavigationAndSignOut` selects the existing `scope-1` tag instead of ambiguous text, and `PaletteTest.searchesEachEntityAndRoutesToItsItem` supplies UTC and a time immediately before fixture session 1 starts, preserving the `amphi`/`session/1` and all other route and fallback assertions. Focused command `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest --tests '*ShellUiTest.scopeNavigationAndSignOut' --tests '*PaletteTest.searchesEachEntityAndRoutesToItsItem' --no-daemon`: exit 0, 2 tests, 0 failures/errors/skips (`/tmp/epita-native-night/homework-finalizer-focused-repair.log`).
- The next forced run failed 1/544: `ShellUiTest.refreshAndServerError` attempted its swipe before asynchronous `today-list` appeared. Added a bounded content wait while keeping the swipe, refresh count and error assertions; focused `--tests '*ShellUiTest.refreshAndServerError'` passed 1/1 (`homework-finalizer-refresh-focused.log`). The following forced run failed 1/544: `SessionSearchNavigationTest.equalLoadingSnapshotRetainsFilterAndAccountChangeClearsIt` asserted the query while Sessions was re-deriving its filter. Added a bounded wait for the same query before the existing assertion, preserving the account-reset check; focused `--tests '*SessionSearchNavigationTest.equalLoadingSnapshotRetainsFilterAndAccountChangeClearsIt'` passed 1/1 (`homework-finalizer-session-focused.log`). The two failed forced logs are `/tmp/epita-native-night/homework-finalizer-repair-full.log` and `/tmp/epita-native-night/homework-finalizer-repair2-full.log` respectively.
- Final `npm test`: exit 0, 104 passed, 0 failed/skipped. Final `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --rerun-tasks --no-daemon`: exit 0, 83 tasks executed in 2m 46s; fresh XML 544 tests, 0 failures/errors/skips, timestamps 2026-11-02 10:43:44–10:45:20 UTC. Both APK builds, lint and unfiltered screenshot verification passed; HWUI ripple warnings appeared. Logs: `/tmp/epita-native-night/homework-finalizer-repair3-npm.log` and `/tmp/epita-native-night/homework-finalizer-repair3-full.log`.
- All 368 PNGs retained the same aggregate SHA-256 `312004eab4d0053fedeb2a4535d58e41c7b334e105a0099bcd8f187bd2d57de9` before and after these test repairs. The prior 2026-11-01 22:12:19 UTC managed-device proof and local 48-event smoke in the preceding entry remain historical evidence for unchanged production and instrumented sources, not a new device run. Independent review of 84 current screenshot hashes remains outstanding; physical-device behaviour and external identity providers remain **Unverified / Needs a device**.
- This is initial task stabilization, not a formal Phase 4 fix round or whole-phase PASS. Phase 5 must implement live Focus for the retained homework ID, and Calendar 4.12 must execute the retained planned timestamp. Phase 11 owns durable offline outbox work. Remaining Phase 4/8.3 and later phases remain required; no merge, push or deployment.

### Phase 4.7 Homework — initial repeat-entry interaction stabilization (2026-11-02)

- The web `openForm` focuses the title and successful create keeps the quick-add form open, retains its course and refocuses the title. A new 200% production Shell + real ShellViewModel/DataRepository + MockWebServer regression types a title and notes, chooses a course, leaves Notes focused, scrolls to the real Add button, checks a rejected POST retains both fields, then accepts canonical IDs 88 and 89 from two POSTs and checks their complete request bodies and remembered course. Before the app change, the first successful create cleared and displayed the title but failed `assertIsFocused` (`/tmp/epita-native-night/homework-repeat-before.log`, 1 test / 1 failure); an earlier construction attempt used `assertTextEquals` on a labelled text field and was corrected to assert its editable value. After the change the expanded regression also checks initial new-form focus, and passes 1/1 (`homework-repeat-final-focused.log`).
- New forms request title focus and the software keyboard initially and after a successful create, and bring the title into view. IME padding keeps the scrolled actions reachable; edits still return on success, error drafts and account-owned stored values are not reset, and create retains the course. No server, fixture, golden or migration change. Real hardware and keyboard variations remain **Unverified / Needs a device**.
- Recorded and inspected 16 changed focus-related PNGs: Homework add, add at 200%, scrolled add at 200%, and Shell new-form captures, light/dark on phone/tablet. They show the title cursor and retain the content; no spinner image was recorded. The 368 existing PNG set otherwise remains; the independent root review of 84 previously current bytes and the newly changed bytes is still pending. This worker does not claim independent review of all 368 images. The initial full verification found precisely eight additional changed focused/scrolled and Shell captures (545 tests, 8 screenshot failures); these were inspected and recorded, not timing or loading captures.
- `npm test`: final exit 0, 104 passed, 0 failed/skipped (`/tmp/epita-native-night/homework-repeat-precommit-npm.log`). `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --rerun-tasks --no-daemon`: final exit 0, 83 tasks executed, fresh XML 545 tests, 0 failures/errors/skips, lint, both APKs and unfiltered verification passed (`homework-repeat-precommit-full.log`). The first full attempt encountered a test-only unresolved `getOrNull`, corrected to the Compose focused matcher; no screenshots were recorded for that compile failure.
- `cd android-native && tools/e2e.sh --smoke`: exit 0, local HTTP/D1 verified 48 events and memberships (`homework-repeat-smoke.log`). `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk tools/e2e.sh`: final exit 0, 69 tasks executed, fresh managed-device XML 2 tests, 0 failures/errors/skips (`homework-repeat-device2.log`). The existing live case keeps its PKCE, attendance, courses, sessions, upcoming, canonical create/complete, untouched-row and other assertions, and now checks visible real IME, reachable Add and a second canonical create without leaving the form; the offline case remains a fresh process. The first device run failed before Homework because the time-dependent Today lecture had scrolled below the lazy viewport, and its dependent offline marker was absent; the same lecture assertions now scroll to the actual row before waiting. Production and local-URL Gradle builds ran sequentially.
- Initial task stabilization only, not a formal Phase 4 verdict. Root final current-byte QA and checks remain pending; Phase 5 must integrate the retained Focus homework ID and Calendar 4.12 the planned timestamp. No next screen, merge, push, deployment, reviewer or report edit.

### Phase 4.8 Focus — stats, history and settings (2026-11-02)

Codex drafted this screen and left it uncommitted; Claude finished it, checked it and committed it in this session.

- **Built:** `ui/focus/FocusScreen.kt` follows `renderFocus` in `public/focus-ui.js`. It has the Today summary (focused today, pomodoros out of the goal, streak, this week compared with last week), the week chart against the goal, "Where it went" for Week / 30 days / Year (by course, by tag, and by task with "Focus on it again" for open homework and own tasks), the 12-month heatmap (levels relative to the daily goal, scrolled to the current week, "best day"), the last 8 sessions with delete and confirmation, and the settings form (lengths, long break after, daily goal, the four switches, own tasks and tags). The form is saved with `PUT /api/focus/settings`, and tasks and tags are saved straight away, as on the web. The draft survives navigation and is cleared when the account changes.
- **Data:** `DataRepository.ensureFocusHistory` fetches `GET /api/focus?since=` for the year and merges it like the web's `ensureHistory` (keeps unsent sessions and fetches again after each `/api/data` reload). It also has `saveFocusSettings` and `deleteFocus`, and a generation counter so a reply that arrives after an account switch is ignored. `FocusSessionDto.id` is now nullable (a session that hasn't been sent yet has no id), and `FocusDataDto.since` defaults to `Long.MAX_VALUE` (no history known yet).
- **Deviations:** (1) The big timer, Start/Pause/Skip/Stop, ±time, presets, target picker and tags on the timer are left to Phase 5's `FocusController`, as the Phase 3 acceptance and the night report decided. The screen says "Timer controls are coming in the next phase." and palette focus actions are kept for it. (2) The empty Recent text drops the web's "Press F to start one." because phones have no F key. The task and tag empty texts are shortened the same way. (3) "Notify me when the page is in the background" becomes "Notify me when a phase ends".
- **Claude's changes to the draft:** added "best day" to the 12-month heading, as on the web; made tasks and tags removable chips with × (content description "Remove …") instead of "Remove …" text buttons; changed the Recent empty text as in (2). Fixed `AppNavAccountTest.switchingAccountsDiscardsSavedNavigationAndAction`, which failed at line 94 because opening Focus now sends `GET /api/focus` and that request took the MockWebServer response queued for account B's refresh. A `QueueDispatcher` now answers `/api/focus` on its own, and every assertion is unchanged.
- **Tests:** `FocusRepositoryTest` (history merge, failure, account switch, settings/delete errors), `FocusModelTest` (heat levels), `FocusUiTest` (empty and history failure, populated week semantics and ranges, settings validation and delete at 200% font, draft kept per account and cleared on replacement), and `FocusScreenshotTest` (main, year, settings, bottom, delete, empty, error; light and dark; phone and tablet). Updated the Shell and Homework navigation tests and `LocalServerTest` (Focus deep link: year loaded, real delete and settings save checked against the server).
- **Checks:** `npm test`: 104 tests, 104 passed, 0 failed or skipped. `cd android-native && ANDROID_HOME=… ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: exit 0, 594 tests, 0 failures, errors or skips; lint, both APKs and screenshot verification passed. Recorded with `recordRoborazziProdDebug --tests '*Focus*' --tests '*ShellScreenshotTest*'`: 40 new and 8 changed Focus PNGs. Claude looked at the phone light main, year and bottom screenshots and the dark settings one.
- **Device:** `cd android-native && ANDROID_HOME=… tools/e2e.sh`: exit 0. The local `/api/data` smoke check verified 48 events. `pixel6Api35` ran 2 tests, 0 failed or skipped, including the Focus year load, a real delete and a settings save checked against the local server.
- **Unverified:** the timer itself (Phase 5), and behaviour on a real device.

### Phase 4.9 Breaks (2026-11-02)

Built by Claude in this session, following `public/breaks-ui.js`.

- **Built:** `ui/breaks/BreaksScreen.kt`. A month calendar coloured by what missing each day costs (`startDayGrid(m, 1)`), with labels from `DAY_VERDICT`, blockers, holidays and saved breaks. It has previous/Today/next limited to today through the end of the calendar, the legend, and the "No spare absences left in …" note. The first/last day pills keep the web's `clickDay` and `keepEnd` rules (`BreakPick.tap`). The verdict panel shows the verdict, Clear, facts, "Can't miss:", and per course the skips, before → after and projected %. It has the name field, Save break (red when the break fails, with the web's confirmation) and the hint. Suggestions use `LENGTHS` chips and `findBreakWindows(limit = 4)`, or "No safe … left" with the longest window. Saved breaks can be shown on the calendar, and removing one asks the web's question first.
- **Data:** `ShellViewModel.saveBreak` / `removeBreak` call `POST /api/breaks` (with the device `tz`) and `DELETE /api/breaks/{id}`, reload afterwards (many-row mutation), and show the web's messages ("Break saved: N sessions marked as planned absences.", "Break removed, N sessions restored."). The model and day grid are worked out on `Dispatchers.Default` in `BreaksViewModel` (plan 5.3), only when the data or the minute changes. Length and range changes reuse that base, and the last result stays on screen while the next one is worked out. The pick is saved per account.
- **Deviations:** "Click" becomes "Tap" in the step hints. On the web, hovering previews the range; there is no hover on a phone. The calendar and panel stack in one column on every width. The tablet side panel is left to Phase 8's list-detail work.
- **Tests:** `BreaksModelTest` (tap rules, use/clear, derive and state compared with the ported maths on a seven-week fixture), `BreaksUiTest` (empty calendar, pick → Safe → save → cleared, remove with confirmation, a failing break asks first, length chips and a suggestion fill the range, month limits and the saved-break label at 200%), `BreaksActionsTest` (MockWebServer: exact POST body, DELETE path, reload after each, messages, server error kept, no request for another account), `BreaksScreenshotTest` (main, picked, fail, saved, empty; light and dark; phone and tablet; main at 200%). `ShellUiTest.breaks` and `ShellScreenshotTest` now wait for and scroll to the Breaks content, because it is worked out off the main thread. `LocalServerTest` scrolls to the seeded break.
- **Checks:** `npm test`: 104 passed, 0 failed or skipped. `./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: exit 0, 628 tests, 0 failures or skips. The first full run had 2 failures: `TabletShellScreenshotTest.light_breaks` captured the spinner (fixed by the wait above), and `AppNavAccountTest` hit a 60 s "Compose did not get idle" timeout under load. It passed alone and in the next full run, so it may be flaky; this is noted, not hidden. Screenshots: 24 new and 4 changed Breaks PNGs. Claude looked at the phone light main (100% and 200%) and dark fail screenshots. `tools/e2e.sh`: exit 0, smoke check verified 48 events, `pixel6Api35` ran 2 tests with 0 failed or skipped.
- **Unverified:** behaviour on a real device.

### Phase 4.11 Settings (2026-11-02)

Built by Claude in this session, following `renderSettings` in `public/app.js`, `calendars-ui.js`, `tokens-ui.js`, `notify-ui.js` and `integrationsPanel` in `homework-ui.js`.

- **Built:** `ui/settings/SettingsScreen.kt`, with the sections in the web's order:
  - **Account:** avatar via Coil, falling back to the initial, plus name, email and Sign out.
  - **Attendance rules:** minimum % and unit, using `PATCH /api/settings`.
  - **Notifications:** classes with lead time, habits and focus, saved on change with `PUT /api/notify/settings` plus the device `tz` (8.5).
  - **Calendars:** rows with site or file, sessions, "attendance not counted" and status or error. Update now (links), upload a newer version (files), inline edit (name, first day for links, Count attendance), and remove with the web's shared/only-session question. The add box takes a link with name and first day (plus the Zeus guide link) or a file from the system picker. Files over 5 MB are refused before sending (5.4). A file with the same name updates that calendar, as on the web. "Recent updates" shows the sync log.
  - **Sign-in methods:** Google, and passkeys with "synced", added and last-used dates, remove, and add.
  - **Claude Code access:** tokens list, create (shown once, in memory only, Copy) and revoke.
  - **Subscribe in your calendar app:** timetable and homework links with the group chips, Copy, Share and Add to Google Calendar, plus Reset links.
  - **Google Calendar sync:** connect, reconnect, Sync now (loops while `remaining`) and disconnect.
  - **Danger zone and legal:** Delete all my data (then Today), Delete my account (type DELETE), and the privacy and terms links.

  Messages and confirmation questions are the web's.
- **Actions:** `ui/settings/SettingsActions.kt` holds `AccountSettingsActions(container, owner)`. Every call is dropped once `owner` is no longer the signed-in session. Many-row changes (calendars, feed, Google Calendar, passkeys, delete data) reload, as the web does. The palette's "Update <calendar>" now goes through it, so it reports the same message.
- **Google Calendar connect (plan 2.4):** `POST /api/app/web-link` with `next=/auth/google/calendar?app=1`, opened in a Custom Tab. `MainActivity` now handles `com.knhdixh.attendance80:/gcal`: it opens Settings (`PendingRouteViewModel.go`), then shows "Google Calendar connected. Syncing…" and runs a quiet resync, or the web's error message.
- **Fixed on the way:**
  1. Adding a passkey while signed in would have failed: `AuthInterceptor` adds the bearer only to `/api/*`, so the server saw `/auth/passkey/register/*` as a new-account sign-up ("Enter your name."). `Api.passkeyRegisterOptions/Verify` and `AuthRepository.registerPasskey` now take the bearer explicitly, and a signed-in add never replaces the stored token.
  2. A failed calendar update first showed the server's error, then reloaded. The reload clears messages, so it now reloads first and shows the error after.
  3. `InputStream.readNBytes` needs API 33 (lint NewApi; minSdk is 26), so it is replaced by `readAtMost`.
- **Deviations:**
  - **Notifications:** the panel has no device on/off, test or permission controls, because those are Web Push. The hint says the app will post them itself in a coming update (Phase 7).
  - **Feeds:** "Apple Calendar / Outlook" (`webcal:`) is replaced by Share.
  - **Delete account:** the web's `prompt()` became an inline field in the Delete data section, because an `AlertDialog` with a text field never went idle in Robolectric (OutOfMemoryError in `ShadowTrace` after about 34 s). The cause was not found; the inline version passes.
  - **Layout:** sections stack in one column on tablets.
  - **Keyboard hints:** "Press N/T" hints are not used.
- **Tests:**
  - `SettingsActionsTest` (MockWebServer): exact bodies and paths for rules, notify with `tz`, link/file/edit/remove/sync, the sync error kept after its reload, the resync loop and its message, the web-link URL, feed rotate, token create/revoke/error, delete data, delete account, and logout clearing the token. Also: no request for another account, the passkey bearer on `/auth/passkey/register/*` (and none when signing up), `readAtMost`, and `importSummary`/`gcalErrorMessage`/`timetableFeedUrl`.
  - `SettingsUiTest` (8 Robolectric tests): add box with no calendar, rules validation and save, notifications, sign out, calendar sync/edit/remove and log, tokens shown once, feed group links, Google Calendar error and resync, delete data and the account check, passkeys at 200%, and the Shell route.
  - `SettingsScreenshotTest`: top, calendars, add, access, feeds, danger; light and dark; phone and tablet; top and danger at 200%.
  - `LocalServerTest` now changes the minimum to 75% on the real local server, checks it, and restores it.
- **Checks:**
  - `npm test`: 104 passed, 0 failed or skipped.
  - `./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: exit 0, 672 tests, 0 failures or skips. The first run failed lint (NewApi above).
  - Screenshots: 30 new and 4 changed PNGs. Claude looked at phone light top, dark calendars, light feeds and light danger at 200%.
  - `tools/e2e.sh`: exit 0, smoke check verified 48 events, `pixel6Api35` ran 2 tests with 0 failed or skipped.
- **Unverified / Needs a device:** adding a passkey with a real provider, Google Calendar connect in a real Custom Tab and its return link, the system file picker with real `.ics` files, and clipboard and share sheet.
- **Open question:** account deletion and sign-out leave that session's `data.json` cache on disk (existing behaviour, also on sign-out). Should Phase 8 delete it?

### Phase 4.12 Calendar (2026-11-02)

Built by Claude in this session, following `public/calendar-ui.js` and `public/calendar.js` (ported earlier as `CalendarMath`).

- **Built:** `ui/calendar/CalendarModel.kt` holds the pure parts: `collect`/`dayItems` (sessions of tracked courses, planned blocks, proposals as ghosts, deadlines, focus time, countdown occurrences, breaks, all filtered by the layers), `unplannedTodos` (overdue first, then latest start), `autoPlanNow` (busy times, existing blocks, two weeks ahead, eight days of room, as the web), `hourRange` (7:00–22:00, stretched), `clashes`, and the plan/keep JSON bodies. `ui/calendar/CalendarScreen.kt` has:
  - **Bar:** previous/Today/next; the title opens a date picker; Day, 3 days, Week and Month; Plan my week.
  - **Day headers:** pinned with week number, today, and "N h class · N planned" in the day view. An all-day row has breaks, all-day sessions, all-day deadlines and countdowns.
  - **Grid:** sessions in course colours (`ui/theme/CoursePalette.kt`, the web's `--c-*` light and dark) with attendance state, exam and clash borders, overlapping items side by side (`CalendarMath.layout`), timed deadlines (several at one time share a line), focus time, a now line, and narrow empty weekend days in the week view.
  - **Month:** dots, exams, deadlines, planned items, countdowns, a break tint and "N more"; tap opens the day.
  - **Swipe:** left and right between periods.
  - **"To plan" list:** under the grid on phones, beside it on tablets, with the layer switches.
  - **Panel at the bottom:**
    - Session: ✓/✗/E, overlaps, due homework, and links to the session and course.
    - Planned block: done, Focus now, length, Move, Unplan, Edit details.
    - Empty slot: new to-do, planned or as a deadline, or "plan one here".
  - **Planning:** tap a to-do, then a time, to plan it. Long-press and drag a block to move it (also across days); long-press its bottom edge to resize. Both snap to 15 minutes. Plan my week shows proposals with Keep/Discard, and tapping a proposal leaves it out.
  - **Palette requests:** `calendar-plan`, `calendar-view` and `calendar-show` (the Homework "planned" chip), which 4.7 left pending, are now acted on.
- **Writes:** `ShellViewModel.calendarActions(owner)` handles plan/move/resize/unplan (`PATCH /api/homework/{id}` with `planned_at`/`planned_minutes`, optimistic and put back with the server's message), Keep (`POST /api/homework/plan`) and new to-dos (`saveHomework`). It reports the web's messages and nothing for another account.
- **Deviations:**
  1. **Popover:** a panel docked at the bottom on every width (the web's phone layout), not a popover beside the item. Opening one scrolls the item above it.
  2. **Drag from "To plan":** not built; tap then tap a time, which the web's phone version also uses. Moving and resizing planned blocks is by long-press drag.
  3. **Keyboard shortcuts:** not used (Android).
  4. **Persistence:** the view and layers are kept per account for the session (`rememberSaveable`), not in storage.
  5. **TalkBack:** has no equivalent of tapping an empty slot.
- **Found and fixed:**
  1. **Move landed on resize:** Compose widens touch targets to 48dp, so the 12dp resize grip on a one-hour block covered the whole block, and a long-press move resized instead. Move and resize are now one gesture, chosen by where the long press starts.
  2. **Planned blocks had no click action:** they used a raw tap detector, so TalkBack couldn't open them; the device test caught it. They are now `clickable`.
  3. **Bar scrolled away:** the bar scrolled off when jumping to now. Only the grid scrolls now, and the headers are pinned.
- **Test note:** touches on the bottom panel are lost in Robolectric's legacy graphics mode. Its top-rounded clip is hit-tested with `Path.contains`, which legacy mode doesn't implement; `ModalBottomSheet` failed the same way. Confirmed with logging and a replica: four equal corners worked, and the same code works in native mode. `CalendarUiTest` therefore runs with `@GraphicsMode(NATIVE)`. No product change.
- **Tests:**
  - `CalendarModelTest` (6): collect/dayItems, layers, ghosts, "To plan" order, Plan my week against the ported maths and its two messages, hours/titles/bodies.
  - `CalendarUiTest` (10, plus 1 tablet):
    - Views and navigation.
    - A session marked from its panel.
    - Empty slot → planned to-do and → deadline, with exact bodies.
    - Tap-to-place.
    - Block length/Focus/Unplan/Move.
    - Long-press move and edge resize.
    - Plan my week → leave one out → Keep.
    - Layers and palette requests.
    - The nothing-to-plan message.
    - The Shell route.
    - Tablet week with the side tray.
  - `CalendarActionsTest` (MockWebServer): PATCH bodies, revert with message, the Keep body, another account.
  - `CalendarScreenshotTest`: main, month, day, proposal, tray, session panel, new panel; light/dark; phone/tablet; main at 200%.
  - `HomeworkNavigationUiTest.focusAndCalendarRequestsRemainPendingAcrossRestoration` now checks that the Calendar opens on the planned day and clears the request (it used to check that the request stayed pending).
  - `ShellUiTest.calendar` uses the Calendar's empty text.
  - `LocalServerTest` plans an open to-do tomorrow morning on the real server, checks `planned_at` on the server and the day, then unplans it.
- **Checks:**
  - `npm test`: 104 passed.
  - `./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: exit 0, 712 tests, 0 failures or skips.
  - Screenshots: 22 new and 4 changed PNGs. Claude looked at the phone light main, month and session panel, the phone dark new-to-do panel and the tablet dark proposal.
  - `tools/e2e.sh`: first run exit 1. The planned block had no OnClick, and the dependent offline test failed with it. After the fix: exit 0, 2/2 passed.
- **Unverified / Needs a device:** drag feel and swipe thresholds on real touch hardware, and the date picker.

### Phase 4.13 Habits (2026-11-02)

Built by Claude in this session, following `public/habits-ui.js` (maths from the ported `Habits`).

- **Built:** `ui/habits/HabitsScreen.kt`.
  - **Habits page:** List/Grid views; "Longest streak" line; the empty sentences; Today with the date, "Nothing due today.", Not due today and Archived (expandable, Restore).
  - **Rows:** the round check button (a ring that fills to the target; tap adds one, long press takes one off), name, meta (schedule, × a day, streak with flame, N/M this week/month, "last N days ago", "not due today"), the last-7-days strip (tap a day to fill it in), and Edit.
  - **Grid view:** cards with 26 weeks of cells.
  - **Quick add:** typed sentence → `parseHabit` chips, Add, More options, Cancel.
  - **Form:** emoji, name, repeat (5 types), days, how often, each day + unit, reminder time (picker), colour swatches, Add/Save, Cancel, Archive/Restore, Delete with the web's question, and Move up/down to reorder.
  - **Habit page** (`habits/{id}`): back, the check with − and N/target, current/best streak, 30-day rate, days done (and check-ins), the 12-month heatmap (tap to change a day), days per month, by weekday, and Edit/Archive/Delete. The "Stats cover the last 13 months" note shows when the server's window starts after the habit.
  - **Milestones:** the toast at 7, 14, 30 … days.
  - **Routes:** `habits/new` and the palette's "New habit" open the add row.
- **Writes:** `ShellViewModel.habitActions(owner)` calls `PUT /api/habits/{id}/log` (optimistic, put back on error), `POST/PATCH/DELETE /api/habits` and `POST /api/habits/order`, with the web's messages ("Added “X” · schedule.", "Habit saved.", "Archived …", "Restored …", "Deleted …").
- **Deviations:**
  - **Gestures:** right-click/shift-click to take one off became a long press.
  - **Reorder:** the web has `move()` but no control for it; here it is Move up/down in the edit form.
  - **Grid tooltips:** replaced by content descriptions.
  - **Keyboard shortcuts:** not used.
- **Tests:**
  - `HabitsModelTest`: tap counts, day toggles, `countsOf`, the exact habit body, stats.
  - `HabitsUiTest` (10):
    - Due/rest/archived and restore.
    - Check-in and long-press counts, plus the 7-day milestone message.
    - A forgotten day in the strip.
    - Quick add with the exact schedule.
    - More options keeps what was typed.
    - Reorder and delete from the form.
    - Grid view and the empty state.
    - Habit page −, the sections and delete.
    - Habit page edit, which keeps `remind_at`.
    - The `habits/new` route.
  - `HabitActionsTest` (MockWebServer): create message, log body, revert with message, archive, order body, delete removes the logs too, nothing for another account.
  - `HabitsScreenshotTest`: list, grid, quick add, form, detail, empty; light/dark; phone/tablet; list and detail at 200%.
  - `ShellUiTest.habitDetail/newHabit` expect the real pages' text.
  - `LocalServerTest` checks in on the real server and takes it off with a long press, verified by the server's count.
- **Checks:**
  - `npm test`: 104 passed.
  - `./gradlew testProdDebugUnitTest`: 748 tests, 0 failures.
  - `lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug`: exit 0. The first combined run stopped at compiling the instrumented test (a duplicate `todayKey`), which was fixed.
  - Screenshots: 24 new and 12 changed PNGs. Claude looked at phone light list and quick add, and phone dark detail.
  - `tools/e2e.sh`: exit 0, 2/2 passed.
- **Unverified / Needs a device:** reminder posting (Phase 7), and the long-press feel on real hardware.

### Phase 4.14 Countdowns (2026-11-02)

Built by Claude in this session, following `public/countdowns-ui.js` (maths from the ported `Countdowns`).

- **Built:** `ui/countdowns/CountdownsScreen.kt`.
  - **Page:** Coming up / Counting up / Past (expandable), the empty sentences and "Nothing coming up.".
  - **Rows:** colour dot, title with the pin mark, when (with the year when it isn't this year), repeat, units, the linked session's room, note, progress bar, the figure with bold numbers and "to go/since/ago", pin/unpin, and delete with the web's question.
  - **Live clocks:** tick every second, only while one is shown.
  - **Quick add:** `parseCountdown` chips with the web's hints; Add, More options and Cancel; the web's errors for no date or no name.
  - **Editor:** title, date and time pickers (All day), repeats, show as, once it's reached, progress bar from, colour, pin, note, a live preview, Save/Add, Cancel and Delete.
  - **"From your timetable":** exams in the next six months, one per course and day, added pinned and red with `event_id`, or dismissed.
  - **Routes:** `countdowns/{id}` (Today's pinned countdowns) opens that countdown's editor; `countdowns/new` and the palette's "New countdown" open the add row.
- **Writes:** `ShellViewModel.countdownActions(owner)` calls `POST/PATCH/DELETE /api/countdowns`. PATCH is optimistic and put back with the server's message, as on the web. Messages: "Added “X”: … to go.", "Countdown saved.", "Pinned “X” to Today and the sidebar.", "Unpinned.", "Deleted “X”.".
- **Deviations:**
  - **Dismissed exams:** kept per account for the session (`rememberSaveable`), not in storage as on the web.
  - **Keyboard shortcuts:** ⌘↵ and N are not used.
  - **No sidebar:** pinned countdowns show on Today, as built in 4.1.
- **Tests:**
  - `CountdownsModelTest`: suggestions (dismissed, already linked), the exact body, figure/direction/when.
  - `CountdownsUiTest` (8):
    - Sections, figures and Past.
    - Quick add errors and the parsed body.
    - The editor preview and save body (units, progress start, pin, note).
    - Pin and delete.
    - An exam from the timetable, and dismissing one.
    - A link from Today opens the editor.
    - Empty state.
    - The `countdowns/new` route.
  - `CountdownActionsTest` (MockWebServer): create, PATCH body, revert with message, applied row, delete, nothing for another account.
  - `CountdownsScreenshotTest`: list, quick add, editor, empty; light/dark; phone/tablet; list and editor at 200%.
  - `ShellUiTest.newCountdown` expects the add row.
  - `LocalServerTest` pins and unpins the seeded countdown on the real server.
- **Checks:**
  - `npm test`: 104 passed.
  - First full Gradle run: 774 tests, 1 failure in `CoursesRepositoryUiTest.trackedControlRollsBackAndPersists` (4.4, not touched here): "could not find courses-list" mid-scroll. It passed 3 times alone with `--rerun-tasks`, and the next full run had exit 0, 774 tests and 0 failures. It looks like the same flash-to-spinner race fixed for Breaks in 4.9 and should be checked in the Phase 4 review.
  - Screenshots: 16 new and 12 changed PNGs. Claude looked at the phone light list and the phone dark editor.
  - `tools/e2e.sh`: exit 0, 2/2 passed.

### Phase 4 — all screens implemented; ready for the whole-phase review (2026-11-02)

- 4.1–4.7 by Codex; 4.8 finished and 4.9, 4.11, 4.12, 4.13 and 4.14 built by Claude, all on `native/phase-4`. 4.10 (Assistant) was removed from the product.
- Owed to later phases, as planned: the live focus timer (Phase 5), local notifications for classes, habits, homework and check-ins (Phase 7), list-detail tablet layouts and a design pass (Phase 8), and the durable offline outbox (Phase 11).
- To look at in the review: `CoursesRepositoryUiTest` flakiness (above), the Robolectric native-graphics note for bottom panels (4.12), the Settings dialog OOM that led to the inline account deletion (4.11), and the open question about cached `data.json` after sign-out or account deletion (4.11).

### Phase 5: focus timer, natively (2026-11-02)

Built by Claude on `native/phase-5`, from `4c569de`. The timer lives in the app now: one controller, one alarm, one notification, and nothing talks to Chrome.

- **Tasks and commits** (the plan's tasks 2 and 3 are one commit, because the alarm and the notification are changed together):
  - **1. `FocusController` and `FocusStore`**, `4147a6a`. `focus/FocusStore.kt` keeps the timer, the last settings, the outbox and pending tasks in a DataStore. `focus/FocusController.kt` has `start`, `pause`, `resume`, `toggle`, `skip`, `stop`, `adjust`, `setLength` and `setTarget`, plus `focusOn` ("focus on this"), `phaseEnded`, `restore` and `adopt`.
    - Every change goes through one mutex, stamps `updatedAt`, saves, reschedules the alarm and redraws the notification and tile. It is built on `logic/FocusTimer.kt` and never computes time itself.
    - The alarm, the notifier, the redraw hook and the outbox request are interfaces, so the tests use a fake clock and a fake alarm.
    - `AppContainer.focusController`, `focusStore` and `focusOutbox` default to `null` in the interface, so the existing tests' anonymous containers still compile.
  - **2 and 3. Phase end and notifications**, `24041a3`.
    - `AndroidPhaseAlarm` uses `setExactAndAllowWhileIdle` at `endsAt`, with an inexact fallback if exact alarms aren't allowed.
    - `FocusReceiver` handles Pause/Resume, Skip, Stop, the alarm, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET` and `TIMEZONE_CHANGED`. The last four call `restore()`: finish what is due, otherwise re-arm.
    - `FocusNotifications` posts the ongoing countdown (chronometer counting down to `endsAt`, a progress bar, public on the lock screen, with Pause/Resume, Take a break/Skip break and Stop). On Android 16+, `LiveUpdate` builds it with `Notification.ProgressStyle` and requests promotion, and falls back silently to the normal one if anything throws.
    - "Phase done" is on a high-priority channel with `res/raw/chime.wav`, honouring `notify` (nothing is posted) and `sound`.
  - **4. Outbox**, `d9c5ce4`. `focus/Outbox.kt` sends sessions oldest first to `POST /api/focus`, and removes one only after a 2xx or a 400 (a 400 is logged and dropped). On anything else (offline, 401, 5xx) it stops and keeps order.
    - `OutboxWorker` runs it with network required and exponential backoff from 30 s, as a unique work item, and asks again on every new log, on app start, and from the hourly `RefreshWorker`.
    - A recorded session is merged into the snapshot by `client_id` (`DataRepository.recordFocus`).
  - **5. Tile and quick-focus sheet**, `4f98d34`. `FocusTileService` starts, pauses and resumes, and shows "Start 25 min" / "Until 14:30" / "Paused · 20:00". `QuickFocusActivity` is opened by the tile's long-press (`QS_TILE_PREFERENCES`), in its own task.
    - The sheet and the Focus screen share one `TimerPanel`: phase, clock, progress, ±1/±5 and length presets as on the web, the "Working on" picker (own tasks, open homework, tracked courses, "+ New task…"), tags (at most 5), Start/Pause/Resume, Take a break/Skip break, Stop.
    - A task typed there is kept on the device and sent to `PUT /api/focus/settings` by the outbox worker, so it works offline.
    - The notification permission is asked once, in context, the first time a timer starts (Focus screen or sheet). It is never asked at launch.
  - **6. Settings**, in `d84c1e8`. Settings come from `focus.settings` in the snapshot: `DefaultAppContainer.startData` pushes every snapshot's settings into the controller, which also stores them, so the alarm receiver has them before the snapshot loads. Edits already went through `PUT /api/focus/settings` and updated the snapshot (Phase 4.8); that now reaches the controller. "Changes apply from the next phase" holds, because a running phase keeps its length.
    - `d84c1e8` also puts the timer panel at the top of the Focus screen and wires the palette's Start, Pause, Resume, Skip, Stop, "Focus on: …" and "New focus task" to it. They run once and clear themselves. Focus on a task stops a focus running on something else first, as the web does.
  - **7. Migration from the TWA**, `7a9cdaa`. `LegacyFocusMigration` reads the old `SharedPreferences("focus")`: a running timer is adopted and its alarm set (or finished at once if it ended meanwhile), unsent sessions go to the outbox (deduplicated by `client_id`, bad ones skipped), pending tasks go to the pending list, then the old file is cleared. It runs once, before the first `restore()`.
  - **Screenshots and lint**: `19c5e49` (24 new screenshots), `78e0b86` (38 re-recorded; see Deviations), `2c28a67` (lint).
- **Tests added: 73** (24 of them screenshot tests).
  - `FocusControllerTest` (17, fake clock, alarm and notifier):
    - start schedules the alarm at `endsAt`;
    - pause cancels it and resume moves it by the gap;
    - toggle, adjust and set length;
    - finish with auto breaks logs and starts a break; without them it goes idle and cancels the alarm;
    - a stale finish doesn't start the next phase;
    - an early alarm only reschedules;
    - `notify` off posts nothing;
    - stop and skip log a stopped session, and under a minute isn't logged;
    - restart and `restore`, a phase that ended while off, `adopt`, settings surviving a restart, and the outbox keeping one entry per `client_id`.
  - `FocusSettingsMappingTest` (1) covers the account settings reaching the controller.
  - `FocusNotificationsTest` (4, Robolectric): the ongoing countdown with three actions, paused text and Resume, idle cancels, and the chime channel versus the silent one.
  - `OutboxTest` (6, MockWebServer): order and body, 500 keeps and stops, 400 drops and logs, offline and 401 keep, the same session twice is safe, and empty sends nothing.
  - `TimerPanelUiTest` (7), `QuickFocusSheetTest` (3, through the real controller), `FocusScreenTimerUiTest` (5, palette commands), `LegacyFocusMigrationTest` (6, Robolectric).
  - `TimerScreenshotTest`: idle, running, paused, break, sheet; light and dark; phone and tablet; running and sheet also at 200%.
- **Checks run** (all from this session):
  - `npm test`: 104 passed, 0 failed.
  - `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: exit 0, BUILD SUCCESSFUL. 847 unit tests, 0 failures, 0 skipped.
    - The first two runs of this command failed, honestly: (1) the Focus and Shell screenshots changed because I removed the placeholder lines, plus a race in my own sheet test (it asserted the outbox request before the coroutine made it); (2) lint's `StateFlowValueCalledInComposition` error in a test of mine. Both fixed (`78e0b86`, `2c28a67`) and the third run is the one above.
  - `tools/e2e.sh`: exit 0, 2/2 `LocalServerTest` tests passed ("Both local-server instrumented tests passed.").
  - Claude looked at the phone light running timer and the phone dark quick-focus sheet screenshots.
- **Deviations from the plan:**
  - **Live Update:** `Notification.Builder.setRequestPromotedOngoing` is not in the compileSdk 36 stubs, so `LiveUpdate` sets the extra `android.requestPromotedOngoing` by key and uses the platform `ProgressStyle`.
  - **Progress bar:** it is drawn when the notification is posted (start, pause, resume, adjust). It does not advance by itself between those; only the chronometer does. Nothing is kept awake to update it.
  - **Exact-alarm permission:** `SCHEDULE_EXACT_ALARM` now has `maxSdkVersion="32"`, as the TWA's manifest had it; `USE_EXACT_ALARM` covers 33+.
  - **Chime:** `res/raw/chime.wav` is a two-note chime I synthesised (22 kHz, mono, 50 KB). Replace it if you want a different sound.
  - **Silent channel:** because a channel's sound belongs to the device and "Play a sound" belongs to the account, there is a second, silent channel for when the setting is off.
  - **Sheet applies at once:** the quick-focus sheet applies each change immediately (like the Focus screen), where the old sheet applied them on Save. It closes after Start, Pause, Skip and Stop.
  - **Not ported** from `focus-ui.js`: typing a time into the clock, the "Next class" / "starts before this focus ends" note on the timer card, and "Finished “X”? Mark done". None is in the plan's Phase 5 list. The ported `parseClock` and `setRemaining` are ready if you want them.
  - **Permission in context:** the notification permission is asked in context here (plan Phase 8 asks for that), because the timer is useless without it on Android 13+.
  - **38 screenshots re-recorded:** the Focus page and the Shell's focus route had a line saying the timer was coming in the next phase, and the settings had one about notifications. Both lines are gone, so those Phase 4 screenshots changed. These are intended UI changes, not logic goldens.
  - **Tasks 2 and 3** are one commit; the outbox (4) was committed before the tile (5).
- **Not mine:**
  - `OfflineShellTest.cachedShellStaysVisibleWhileRefreshIsDelayed` failed twice in partial, heavily parallel runs of the Focus and Shell tests (it asserts under a 2-second `MockWebServer` body delay). It passed alone, in a Shell-only run, and in the full run. It looks load-dependent; worth a look in the Phase 4 review next to `CoursesRepositoryUiTest`.
  - `android-native/tools/run-phase.sh` was modified in the working tree by someone else during this session (budget and wording changes). I did not touch it and did not commit it.
- **Unverified / Needs a device** (everything below is only tested with fakes or Robolectric):
  - the exact alarm firing in Doze, and being re-armed after a real reboot, an app update, and a time or time-zone change;
  - the lock-screen countdown ticking by itself, the notification actions, and the Live Update being promoted on Android 16 (and the fallback);
  - the chime, and the silent channel really being silent;
  - the tile (label, subtitle, toggling) and the long-press opening the sheet; the sheet's translucent window over the launcher;
  - the notification permission dialog appearing once;
  - the migration over a real TWA install with a running timer, unsent sessions and tasks;
  - `WorkManager` backoff timing and the retry after a real offline period.
- **Open questions:**
  1. Outbox entries carry no account. If you sign out and into another account with sessions still queued, they would be sent to the new account. The server would accept them. The fix is an `owner` field on the entry (a hash of the token, as the snapshot cache does) and a purge on sign-out; I did not change the session JSON the plan fixes.
  2. The widget (Phase 6) needs to open `QuickFocusActivity` and read `FocusController.timer`; both are ready (`FocusSurfaces.refresh()` is where the widget redraw goes).

### Review 4 fixes (2026-11-02)

All four blocking findings of `docs/native-android-review-4.md`, on `native/phase-5`.

1. **Cached account data (`1b449fc`).** `DataRepository.start()` now deletes every `data-*.json` and `snapshot-*.tmp` that doesn't belong to the current token, so sign-out, a 401, a new sign-in and an old install's orphans all clean up. `deleteAccount()` calls `repo.start()` after `logout()`. A refresh that finishes after the token changed deletes the file it just wrote. The cache moved to `filesDir/snapshots/`, because backup exclude paths take no wildcards; it is excluded from `backup_rules.xml` and from both `data_extraction_rules.xml` sections, and the container deletes the old root-level `data-*.json` once. After "Delete all my data" the reload rewrites the cache (asserted).
   - Tests: `DataRepositoryTest` (sign out leaves no file; one file per sign-in; reload after delete rewrites it; stale-file cleanup that keeps unrelated files), and `SettingsActionsTest.deleteAccount` through MockWebServer asserts the file is gone.
2. **No flash to a spinner (`88a5472`).** Today, Attendance, Courses, Sessions and Course keep the last derived state on screen while the next one is worked out (the spinner is only for the first result); their view models no longer reset the state when `data` changes (Course still resets for a different id); each `LazyListState` is hoisted above the loading branch.
   - Tests: new `SessionsNoFlashTest` (production Shell, real repository, MockWebServer; scrolls to row 30 of 40, marks it, then advances a minute, asserting the list and its scroll offset stay). It fails on the old screens (checked by stashing `app/src/main`). `CoursesRepositoryUiTest` now waits for `courses-list` after each refresh instead of relying on the repository; no retries added.
   - `SessionsScreenshotTest` (Past) waited on `sessions-list`, which is now present before the filter lands. It waits for `session-row-901` instead. PNGs unchanged.
3. **Breaks (`58b4242`).** The verdict, the Save button and the failing-break confirmation use the derived state only when its `len`, `from` and `to` equal the pick; otherwise "Working it out…" (tag `break-pending`) shows. The result column carries a state description with the range. `BreaksUiTest` and `BreaksScreenshotTest` wait for the verdict of the exact range. New `savingAJustChangedFailingRangeStillAsksAndNeverShowsTheOldVerdict`. The fixed clock is unchanged, and no `breaks_*` PNG changed (`verifyRoborazziProdDebug` passed).
   - Audit of the other screenshot tests: only the Sessions "Past" wait was in the same class (fixed above). The rest wait for a list tag on first display only.
4. **Palette requests for Settings (`656c55f`).** Settings takes the saved `OpenForm` request: "Upload .ics file" opens the system picker; "Add calendar" opens the add box, scrolls to it and focuses the link field. Both clear the request first. New `SettingsPaletteRequestTest` goes through the real palette and Shell route, and checks the request is cleared and not replayed after state restoration; both tests fail without the fix.

### Review 5 fixes (2026-11-02)

Both blocking findings of `docs/native-android-review-5.md`. Their code was written together and the first commit holds the shared store and controller changes (including the migration hook); `ffb9044` adds the finding 2 tests.

1. **Account ownership (`077c603`).**
   - The store keeps the account (email, lower case, from the snapshot) and stamps each queued session and pending task with it (`OutboxItem`, `PendingTask`). The owner is storage only; `toRequest()` still sends the session alone (asserted on the wire).
   - `Outbox.flush()` sends only the signed-in account's entries and reads the account again for every entry. `syncPendingTasks` merges only that account's tasks, and only into a snapshot whose `user.email` matches.
   - `FocusController.signedOut()`, called from the token collector for every sign-out (button, 401, deletion), stops a running timer (recorded for the account that started it), clears the target and forgets the account. Queued items stay with their account, and re-signing in as the same account (any letter case, new token) sends them. Account deletion purges that account's queue.
   - Old unowned items (older store format, TWA migration) are taken over by the first account that signs in.
   - Tests: `FocusAccountTest` (7): A→B with sessions and tasks (MockWebServer sees no request), re-sign-in to A, a timer across sign-out, signed-out sessions, purge, unowned takeover, and tasks against the wrong snapshot. Existing focus tests now set an account in their set-up.
   - **Deviation:** a session finished while nobody is signed in (tile, notification) is **not queued**, because there is no account to attribute it to and sending it to the next account is the bug. The timer still works. Needs your decision if you would rather keep those for the next sign-in.
   - Not changed (deferred): the two-step save in `change()` (Phase 11 item).
2. **TWA migration entry points (`077c603` code, `ffb9044` tests).** `LegacyFocusMigration` writes straight into the store and no longer needs the controller. `FocusController` runs it through a `beforeFirstLoad` hook inside `ensureLoaded` (under the controller mutex, once), so `restore()` from the receiver, the tile and the UI all pass through it first. `toggle()` finishes and logs a phase that is already due before applying the toggle.
   - Tests: `FocusMigrationEntryPointsTest`: `restore()` with a running legacy timer arms the alarm and shows the notification; a cold tile toggle pauses the adopted timer instead of starting a new one; a due legacy timer is finished and logged first; concurrent entry points migrate once. `LegacyFocusMigrationTest` updated for the new constructor.
3. **`OfflineShellTest.cachedShellStaysVisibleWhileRefreshIsDelayed`:** left deferred, as the review says (non-blocking, Phase 8 at the latest). The other deferred items stay deferred.

### Checks for Reviews 4 and 5 (2026-11-02, after the last code commit `ffb9044`)

- `npm test`: 104 tests, **103 pass, 1 fail**. The failure is the known time-of-day assertion in `test/habits.test.js:222` (countdown `days` is 11, expected 10; plan 8.2 notes it). It fails in every time zone I tried (UTC, Europe/Paris, Pacific/Kiritimati, America/Los_Angeles) at 19:29 CET, and this branch has no change under `src/`, `public/`, `test/` or `migrations/`. I did not touch it.
- `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: BUILD SUCCESSFUL. 864 unit tests, 0 failures, 0 errors, 0 skipped (from the result XML). Roborazzi verified with no PNG re-recorded or changed (`git status` clean).
- `tools/e2e.sh`: BUILD SUCCESSFUL, 2/2 instrumented tests passed ("Both local-server instrumented tests passed.").
- **Unverified:** the Settings picker and focus on a real device and keyboard; the exact-alarm, notification and tile behaviour of Phase 5 (still Needs a device); backup exclusion of `snapshots/` on a real backup/restore; the migration over a real TWA install. The new `SessionsNoFlashTest` shows no spinner in Robolectric, not on a slow device.

### Blocked: `npm test` cannot pass in this session (2026-11-02)

- `npm test` gives 104 tests, 103 pass, 1 fail, again on a second run at 19:36 CET. The failing test is "habits and countdowns API…" at `test/habits.test.js:222`: the countdown `days` is 11, expected 10. The test reads the real clock, so its result depends on the hour. Plan 8.2 lists it as "Known, not yours to fix".
- It fails with `TZ` set to UTC, Europe/Paris, Pacific/Kiritimati and America/Los_Angeles, so changing the zone does not get around it. This branch has no diff against `main` under `src/`, `public/`, `test/` or `migrations/`, so the Android fixes cannot have caused it.
- I did not edit the test or fake the clock. That would change a server test outside this task, and plan section B forbids weakening a test to make it pass. **Needs the owner:** fix the test to use a fixed clock, or rerun `npm test` at an hour when it passes.
- Everything else is done and logged above: the Gradle check (864 unit tests, 0 failures) and `tools/e2e.sh` (2/2) passed.

### Review 5 fixes, round 2 (2026-11-02)

The one remaining item of the re-review in `docs/native-android-review-5.md`: the TWA timer's own session was dropped in a real upgrade.

1. **Adopted TWA run's session with no account (`813c5e9`).**
   - `LegacyFocusMigration` records the adopted timer's `run` id in the store (`twa_run`). `FocusController.change()` now queues a log when an account is signed in, or when its `client_id` is that adopted run. With no account the entry is stored unowned, like `addLegacy` items, so the first account to sign in claims it and no later account does. New native runs finished while signed out are still not queued.
   - Tests (`FocusMigrationEntryPointsTest`, all start with no account): a migrated timer finishing via `restore()` then `phaseEnded()`; a cold tile toggle on an already-ended legacy timer; each followed by `setAccount(A)`, which holds exactly `old-run-123`, then `setAccount(B)`, which holds nothing. A third test checks that a new native run finished while signed out is still not queued. The existing account-set cases are unchanged. With `app/src/main` stashed, the three new tests fail; with the fix, all 7 pass.
   - The wire body is unchanged. No `outbox.requestFlush()` change was needed, because an unowned entry is not sent until claimed.
   - The sending side is covered at store level (`store.outbox()` is what `Outbox.flush()` sends); I did not add a MockWebServer case for this item.

#### Checks (after `813c5e9`)

- `npm test`: 104 tests, **104 pass, 0 fail** (the habits clock test was fixed in `ed19faf`).
- `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: BUILD SUCCESSFUL. 867 unit tests, 0 failures, 0 errors, 0 skipped (result XML). Roborazzi verified; no PNG changed (`git status` clean).
- `tools/e2e.sh`: BUILD SUCCESSFUL, "Both local-server instrumented tests passed" (2/2).
- **Unverified:** the migration and exact alarm over a real TWA install with a running timer (needs a device); the deferred items in the review are unchanged.

## Phase 6: widget (2026-11-03)

Branch `native/phase-6`. Server untouched.

### What I did

1. **`WidgetSnapshot` (`677eaf2`).** `widget/WidgetSnapshot.kt`: plain data (signed in, up to 8 upcoming sessions, top 3 *Do next*, the timer). `buildWidgetPlan` uses the same rules as `buildPlan` (tracked courses, no excused or future-absent sessions, no all-day items; *Do next* is `buildPlan(...).todo.take(3)`); `buildWidgetTimer` from `FocusTimer` + settings; `nextWidgetRefresh` for the next redraw. `WidgetStore` writes `filesDir/snapshots/widget.json` (the folder already excluded from backup) atomically; the plan part and the timer part are replaced separately. 14 tests in `WidgetSnapshotTest` (fixture data, in-progress class, hidden sessions, store, refresh time).
2. **Glance widget and wiring (`d3367d3`; tasks 2 and 3 are one commit because the surfaces call the redraw).**
   - `FocusGlanceWidget` (Glance 1.1.1, `GlanceTheme` = dynamic colour on Android 12+), `FocusWidgetReceiver`, `focus_widget_info.xml` (4×2 default, resizable to 2×2), manifest receiver. Responsive: 2×2 shows next class and timer; 4×2 and up adds *Do next*. Never touches the network.
   - Timer: Start / Pause / Resume button runs `FocusController.toggle()` (same as tile and notification). The running countdown is a `Chronometer` through `AndroidRemoteViews`, so the system ticks it with no wake-ups.
   - Taps: class → `a80://session/{id}`, to-do → `a80://homework/{id}`, timer text → `a80://focus` (existing deep links).
   - Writers: `FocusController` calls new `FocusSurfaces.timerChanged` on every render (default no-op, so existing fakes are unchanged); `AndroidFocusSurfaces` writes the timer part and redraws. `AppContainer.startData` collects `DataRepository.snapshot` and writes the plan part (skips the empty snapshot before the cache is read).
   - Redraw: one inexact alarm to `FocusReceiver` (`ACTION_WIDGET_REFRESH`) at the next class boundary, midnight or due time; also the 30 min `updatePeriodMillis`.
   - Tests: `WidgetTextTest` (6, wording and states), `WidgetRenderTest` (3, real Glance content rendered through Robolectric and read back: 2×2 has no *Do next*, 4×2 has it, signed out), `WidgetTimerWiringTest` (controller → store).

### Checks (after `d3367d3`)

- `npm test`: 104 tests, 104 pass, 0 fail.
- `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: BUILD SUCCESSFUL. 891 unit tests, 0 failures, 0 errors, 0 skipped (result XML). No PNG changed (`git status` clean).
- `tools/e2e.sh`: BUILD SUCCESSFUL, "Both local-server instrumented tests passed" (2/2).
- An earlier full run, on a machine with load average above 15, failed 3 tests (`ShellUiTest.today`, `SessionUiTest.selectionUsesVisibleIdsAndClearsWhenFilterChanges`, `OfflineShellTest.cachedShellStaysVisibleWhileRefreshIsDelayed`). Run alone they passed, and the full run then passed. I did not change them; they look load-dependent (the last is the known deferred one).

### Deviations

- The plan part is written by a collector on `DataRepository.snapshot` in `AppContainer`, not inside `DataRepository`, so the repository is unchanged.
- The timer opens Focus (plan wording), not the quick-focus sheet the old widget opened.
- `FocusSurfaces` got `timerChanged` (default no-op) rather than a changed `refresh()`.
- No Roborazzi screenshot of the widget (the render test reads text instead).

### Unverified (needs a real device)

- Placing the widget, the 2×2 / 4×2 layouts and look at real sizes, dynamic colour on Android 12+.
- The chronometer ticking, and the button toggling the timer from a cold process.
- The redraw alarm at class boundaries and midnight, and the redraw after boot or a time-zone change.
- Taps opening the session, to-do and Focus screens from the launcher.
- The old TWA widget being replaced on an update (it is not removed or migrated).

### Open questions

None.

## Review 6 fixes (2026-11-03)

Fixes for `docs/native-android-review-6.md`, all on `native/phase-6`.

1. **Widget redraw while a Glance session is alive (`1fa7161`).**
   - `WidgetStore` has a `revision` StateFlow that goes up on every write and on `touch()`. `FocusGlanceWidget.provideGlance` now reads nothing: its content is `WidgetHost(store)`, which collects `revision` and, through `WidgetFromStore`, reads the store and the clock at composition time. The redraw alarm is set from what that composition drew (`SideEffect`).
   - `redrawWidget` is now `suspend` (touches the store, then `updateAll`). `FocusReceiver` awaits it after every action, before `pending.finish()`. The controller's surfaces use a non-suspending `requestWidgetRedraw`, which touches the store at once.
   - Tests (`WidgetRenderTest`): start then pause written to a real store and drawn through `WidgetFromStore`, and the second state (Resume) is what is drawn; the alarm comes from the drawn data; every write and `touch` moves the revision.
   - Deviation: `GlanceRemoteViews.compose` hung when the composition collected a flow (Robolectric main thread), so the collecting part (`WidgetHost`, three lines) is separate from the store-reading part that the test drives. **Unverified:** that a live session really recomposes on a revision change (needs a device).
   - Device checklist for Phase 9 (there is no checklist file in the repo, so it is recorded here): place the widget, tap Start, then tap Pause within 10 s and check it shows Resume; open the app and start a timer within 45 s of a widget redraw and check the widget follows.
2. **TWA provider name (`0202cd9`).** The receiver is now `com.knhdixh.attendance80.FocusWidget` (`FocusWidget.kt`, a `GlanceAppWidgetReceiver`), declared as `.FocusWidget`. `WidgetProviderNameTest` (Robolectric) resolves that component as a receiver with `android.appwidget.provider` metadata. **Unverified:** "update over a TWA with the widget placed" on a device (keep in the Phase 9 checklist).
3. **Null snapshot at cold start (`24f5fc0`).** New `WidgetPlanWriter(tokenStore, store, clock, redraw)`, called by the `AppContainer` collector. It awaits the token store before acting on a null snapshot. Null with no token clears the plan (signed out). Null with a token keeps the plan if it belongs to that account (`owner`, a SHA-256 of the token, stored in `widget.json`), else writes an empty signed-in plan, so account A's items never stay while B's data is pending or offline. `WidgetPlanWriterTest` (3, token store that loads late): cold start with a saved token never writes signed-out; sign-out clears; A→B leaves none of A's titles in the file.
4. **Class window (`1739bb1`).** The stored list is now the TWA's window: ending after now, starting within 8 days, up to 24. Test with 60 sessions (6 a day): 24 kept, reaching beyond the first day and a half; none starts past 8 days.
5. **Intent commands (`1f60e62`).** `FocusController.startIfIdle`, `pauseIfRunning`, `resumeIfPaused` finish a due phase first (like `toggle()`, which now shares that step), then act only if the state fits. `TimerActions` has `start`, `pause`, `resume` (controller-backed in `ControllerActions`; the quick-focus sheet and `AskingActions` ask for the notification permission on start). The palette calls them. The widget button passes `TimerCommand` (`start`/`pause`/`resume`, from `timerCommand(drawn timer)`) in `ActionParameters`. Tests: each command against a controller in the opposite state (no-ops), a phase that ended is finished first, `timerCommand`. `FocusScreenTimerUiTest.paletteCommandsRunOnceAndClear` now expects `pause`/`start`/`resume` calls instead of `toggle` (the "start does nothing while running" check moved to the controller tests). Note: after a due focus phase is finished, the command applies to what follows (an auto-started break), as `toggle()` always did.
6. **Flaky shell tests (`036655c`).** `ShellUiTest.today` retries `performScrollToNode(Mathématiques)` until the new list holds it (the "Do next" heading is drawn in the empty state too, and the populated data has no to-dos at that time, so the empty-text wait I tried first never passed); the assertion is unchanged. `OfflineShellTest.cachedShellStaysVisibleWhileRefreshIsDelayed` holds the second request on a `Dispatcher` latch, waits 60 s for it to arrive, asserts the cached text while it is held, then releases it.
7. Lint fix for the host test (`8c32af0`): `StateFlow.value` was read inside a composable lambda.

Deferred items in the review are unchanged (updatePeriodMillis, overrun countdown, time zone of *Do next*).

### Checks (after `8c32af0`)

- `npm test`: 104 tests, 104 pass, 0 fail.
- `cd android-native && ANDROID_HOME=/Users/khanh.dang/.bubblewrap/android_sdk ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --no-daemon`: BUILD SUCCESSFUL. 904 unit tests, 0 failures, 0 errors, 0 skipped (result XML). Roborazzi verified; no PNG changed (`git status` clean before the last commit's test edit).
- `tools/e2e.sh`: BUILD SUCCESSFUL, "Both local-server instrumented tests passed" (2/2).
- **Unverified (needs a device):** live Glance session redraw, update over a TWA with its widget placed, everything in the Phase 6 Unverified list.
