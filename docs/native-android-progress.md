# Native Android Implementation Progress

## Phase 1: Server Auth + Project Skeleton

### What was done
- **Task 1: Server Auth Changes & Tests** (`77e6ab1`)
  - Added migration `migrations/0015_app_auth.sql` creating `app_codes` table with PKCE challenge and 2-minute expiration.
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
