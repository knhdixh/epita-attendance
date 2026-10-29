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
