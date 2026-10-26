# Native Android app: implementation plan

The goal is a native Kotlin app that replaces the Trusted Web Activity in `android/` and does everything the
website does, plus a few things only a native app can do. It keeps the same package (`com.knhdixh.attendance80`)
and signing key, so it installs over the TWA as an update.

Work through the phases in order. Each phase ends with checks that must pass before you start the next one.
**Read sections A, B and 0 before you write any code.**

---

## A. Context

- **The product.** Attendance80 helps students stay above their university's attendance minimum (usually 80%).
  It imports timetables (`.ics` links from EPITA's Zeus or Finnish universities' Sisu, or uploaded files), lets
  you mark each session, and adds homework, a break planner, a focus (Pomodoro) timer and an AI assistant. The
  README's feature table is the full list.
- **The stack today.** One Cloudflare Worker (`src/worker.js`) serves the REST API and the static frontend in
  `public/` (plain JavaScript, no build step), with a D1 (SQLite) database (`migrations/`). The maths shared by
  the browser and the Worker lives in `public/stats.js`, `breaks.js`, `plan.js` and `focus.js`. Tests are
  `node:test` suites in `test/` (`npm test`, Node 22+), with D1 shimmed by `test/d1-shim.js`.
- **The Android app today.** `android/` is a Trusted Web Activity (the website full screen in Chrome) plus native
  Java for the focus timer: lock-screen countdown, home-screen widget, quick-settings tile, all talking to the page
  over Chrome's postMessage (`Bridge.java`). See `android/README.md`. The native app replaces all of it.
- **Hosts.** Production `https://epita.knhdixh.com`, staging `https://epita-staging.knhdixh.com` (its own
  database; use it for testing on a device), local `npm run dev` on `http://localhost:8787` (`DEV_LOGIN=1` in
  `.dev.vars` gives a password-free test login; from the Android emulator the host is `10.0.2.2:8787`).
- **Style of the existing code and text.** Match it: short comments that say why, not what; plain JavaScript
  with no new dependencies on the server; British-leaning plain English in UI text and commit messages; commit
  subjects like `Focus: time adjustments; Android: quick-focus sheet from the widget and tile` (area, colon, what
  changed), with a body explaining anything non-obvious.

## B. Working with the reviewer

Your output is reviewed by another agent (Claude) and the owner before it is merged. Make that review easy:

1. **Branch.** Work on a new branch `native-android` from the current `HEAD`. Never push to `main`, never force
   push, never rewrite history that's already been reviewed.
2. **Commits.** At least one commit per numbered task in a phase, each building and passing tests on its own.
   Don't mix server changes and Android changes in one commit.
3. **Progress log.** Keep `docs/native-android-progress.md` up to date, one section per phase:
   - what you did, task by task, with the commit hashes;
   - the exact check commands you ran and their result (pass/fail counts, not "all good");
   - every place you deviated from this plan, and why;
   - anything you couldn't verify (for example, needs a real device), marked **Unverified**;
   - open questions.
   Never write that something works unless you ran the check that shows it.
4. **Stop for review** at the end of Phase 1 (server auth is security-sensitive), Phase 2 (the maths port),
   Phase 5 (the timer) and Phase 9. At a stop, finish the progress log, commit, and end your turn with a short
   summary pointing at it. Don't start the next phase until told to.
5. **Don't fake checks.** Don't weaken, skip, `@Ignore` or delete a test to make it pass, and don't regenerate
   golden files to match wrong Kotlin output. If a check fails and you can't fix it, stop and record it in the log.
6. **Ask instead of guessing** when the plan and the code genuinely conflict in a way rule 3 below doesn't settle:
   write the question in the log and stop.

### What you must never do

- Deploy anything: no `npm run deploy`, `npm run deploy:staging`, `wrangler deploy`, or
  `wrangler d1 migrations apply --remote`. The owner deploys after review. Local `npm run dev` is fine.
- Read, print, copy or commit secrets: `.dev.vars`, `android/keystore.properties`, `android/android.keystore`,
  `android/signing.txt`, anything in `~/.ssh/`. The build may *use* the keystore through Gradle; nothing else
  touches it.
- Connect to any server over SSH. Nothing in this plan needs one.
- Change the Google Cloud OAuth client, Cloudflare settings or DNS. If something seems to need that, write it in
  the log as a task for the owner.
- Follow the knhdixh cookbook (`~/.claude/knhdixh/`) for this app. See section 6.

---

## 0. Rules

1. **The server is the source of truth.** The app reads with `GET /api/data` and writes through the REST API in
   `src/worker.js`. It never computes server-side state on its own: no ICS parsing, no Google Calendar sync, no
   assistant logic.
2. **The maths is ported, not reinvented.** `public/stats.js`, `public/breaks.js`, `public/plan.js` and
   `public/focus.js` are pure functions. Port them to Kotlin line by line, keep the same names, and prove they
   match with the golden tests in Phase 2. If the Kotlin output differs from the JS output, the Kotlin is wrong.
3. **When this plan and the code disagree, the code wins.** Before building a screen, read the web module named
   for it (for example `public/breaks-ui.js` for Breaks) and copy its behaviour, wording and edge cases. This plan
   lists what each screen has, but the JS has the details.
4. **Don't touch `android/`** until Phase 9. Build the new app in `android-native/`.
5. **Never commit secrets.** `android/keystore.properties`, `android/android.keystore` and `android/signing.txt`
   stay where they are. The new build reads them by relative path.
6. **Server changes must keep the website working.** Run `npm test` after every server change. Every new server
   behaviour gets a test in `test/` using the D1 shim (`test/d1-shim.js`), in the style of the existing tests.
7. **Keep it simple.** Don't add a library this plan doesn't list. Prefer one obvious way to do things.
8. **Copy is plain and short.** Reuse the website's wording (labels, empty states, error messages) exactly
   wherever a screen has an equivalent. Sentence case. No emoji in UI text (except where the web already shows
   one, for example homework titles synced to Google Calendar).

---

## 1. Architecture

### Stack

(The look is described in section 6: the current website's style, not the knhdixh cookbook.)

| Concern | Choice |
|---|---|
| Language | Kotlin (latest stable), JDK 17 |
| Build | Gradle Kotlin DSL, version catalog `gradle/libs.versions.toml`, AGP latest stable |
| SDK | `compileSdk 36`, `targetSdk 36`, `minSdk 26` |
| UI | Jetpack Compose + Material 3 (Compose BOM), Navigation Compose |
| Networking | OkHttp + Retrofit + kotlinx.serialization converter |
| JSON | kotlinx.serialization (`ignoreUnknownKeys = true`, `explicitNulls = false`) |
| Local storage | DataStore (Preferences) for small values; a JSON file for the data snapshot (see below) |
| Secure storage | the session token encrypted with an Android Keystore AES key (see Phase 1) |
| Background | WorkManager (refresh, outbox flush), `AlarmManager` exact alarms (timer, reminders) |
| Widget | Jetpack Glance |
| Passkeys | `androidx.credentials` (Credential Manager) |
| Browser sign-in | `androidx.browser` Custom Tabs |
| Images | Coil (account avatar only) |
| DI | none: one hand-written `AppContainer` created in `Application.onCreate` |
| Tests | JUnit 4, kotlinx-coroutines-test, Turbine, MockWebServer, Robolectric for the few Android-bound tests |

Don't use Room or Hilt. The whole account's data is one `GET /api/data` response (a semester is a few
thousand sessions); holding it in memory and caching the raw JSON is simpler and fast enough.

### Data flow

```
GET /api/data ──► DataRepository ──► StateFlow<AppData?> ──► ViewModels ──► Compose screens
                     │   ▲                                         │
      raw JSON cache ◄┘   └──── mutation result / reload ◄── Api ◄──┘  (optimistic update first)
      (filesDir/data.json)
```

- `DataRepository` loads `filesDir/data.json` at startup (instant offline UI), then fetches `/api/data`, writes
  the file atomically (write to a temp file, then rename), and emits the parsed `AppData`.
- Mutations: update the in-memory `AppData` optimistically, call the API, then either apply the server's
  response or revert and show the error message from the response's `{ error }` body in a snackbar. After
  mutations that change many rows (calendar add/sync/remove, breaks, bulk attendance, assistant actions) do a
  full reload, as the web does.
- Derived values (scope-filtered sessions, per-course stats, today's plan, break model) are computed in the
  ViewModels from `AppData` with the ported maths, on `Dispatchers.Default`, and memoised per `AppData`
  instance plus `now` rounded to the minute.
- `now` and the time zone come from an injectable `Clock` (`fun now(): Long`, `fun zone(): ZoneId`), so tests
  can pin them.

### Package layout (`android-native/app/src/main/java/com/knhdixh/attendance80/`)

```
App.kt                    Application: creates AppContainer, notification channels, WorkManager setup
AppContainer.kt           builds Api, repositories, Clock, stores
MainActivity.kt           single activity, edge-to-edge, hosts NavHost; handles deep links and shortcuts
auth/                     AuthRepository, TokenStore, GoogleSignIn (Custom Tab), Passkeys (Credential Manager)
net/                      Api (Retrofit interface), models (@Serializable DTOs), ApiError, AuthInterceptor
data/                     DataRepository, AppData, Outbox (focus logs), Scope (calendar filter)
logic/                    Stats.kt, Breaks.kt, Plan.kt, FocusTimer.kt, FocusStats.kt, Groups.kt, Dates.kt
focus/                    FocusController, FocusStore, FocusNotifications, FocusReceiver, FocusTileService
widget/                   TodayWidget (Glance), WidgetSnapshot
reminders/                HomeworkReminders, SessionCheckIn
ui/theme/                 Theme.kt, Color.kt, Type.kt
ui/components/            shared rows, chips, status pills, empty states, bottom sheets
ui/<screen>/              one package per screen: Screen.kt + ViewModel.kt
```

### Build variants

Keep the existing two targets as product flavors of one dimension `host`:

| Flavor | Base URL | App name |
|---|---|---|
| `prod` | `https://epita.knhdixh.com` | Attendance80 |
| `staging` | `https://epita-staging.knhdixh.com` | Attendance80 (staging) |

Same `applicationId` for both (as today, staging replaces the app). The `debug` build type may also take
`-PbaseUrl=http://10.0.2.2:8787` for a local `npm run dev` server; allow cleartext only for `10.0.2.2` and only
in debug, through `res/xml/network_security_config.xml` in `src/debug/`.

Expose `BuildConfig.BASE_URL`. Never hard-code the host anywhere else.

---

## 2. Server changes (do these first, in Phase 1)

The website signs in with an HttpOnly cookie, which a native app can't get from a browser sign-in. Add
**app sessions**: a bearer token that is a row in the existing `sessions` table.

### 2.1 App session tokens

- An app session id is `a80s_` + `randomToken(32)`, stored as `sessions.id` like any other session.
- In `fetch` (`src/worker.js`), before the personal-token branch: if the `Authorization` header matches
  `^Bearer (a80s_[A-Za-z0-9_-]{20,})$`, look the session up (same query as `currentUser`), and if it's valid run
  `handleApi` with that user and **no `tokenAllows` restriction** (it's a full session). Invalid or expired:
  `401 { error: 'Not signed in.' }`. Personal tokens (`a80_…`) keep working exactly as now. `/api/me` is
  answered outside `handleApi` today, so the app branch must answer it too.
- Make `currentUser(request, env)` also accept that bearer header, so `/api/me` and the passkey endpoints work
  for the app.
- Sliding expiry: when an app session is used and has under 15 days left, push `expires_at` to now + 30 days
  (at most one write per day per session).
- `POST /auth/logout` with an app bearer deletes that session row and returns `{ ok: true }`.

### 2.2 Google sign-in for the app (authorization code + PKCE, RFC 8252)

New migration `migrations/0015_app_auth.sql`:

```sql
-- One-time codes that hand a sign-in finished in the browser to the Android app (PKCE, 2 minutes).
CREATE TABLE app_codes (
  code        TEXT PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  challenge   TEXT NOT NULL,      -- base64url SHA-256 of the app's verifier
  expires_at  INTEGER NOT NULL
);
```

Endpoints:

1. `GET /auth/app/google?challenge=<base64url, 43 chars>` starts the existing Google OAuth flow with state
   purpose `app` and keeps the challenge in the state cookie (`app.<state>.<challenge>`; adjust the split in the
   callback so the existing `login` and `calendar` purposes still parse).
2. The callback, for purpose `app`: does the same checks and `signIn` user upsert as `login`, but instead of
   starting a cookie session it inserts an `app_codes` row (code = `randomToken(24)`, 2 minutes) and redirects to
   `com.knhdixh.attendance80:/auth?code=<code>`. Failures redirect to
   `com.knhdixh.attendance80:/auth?error=<code>` (same error codes as the web: `domain`, `state`, `google`…).
   Refactor `signIn` so the user upsert can be called without creating a session.
3. `POST /auth/app/token` with `{ code, verifier }`: deletes the code row as it reads it (single use), checks it
   hasn't expired and that `base64url(sha256(verifier)) === challenge`, then creates an app session and returns
   `{ token }`. Any failure is `400 { error: 'Sign-in expired, please try again.' }`.
4. `GET /auth/app/dev?email=…&challenge=…` (only when `DEV_LOGIN` is on): same as 2 without Google, for
   emulator testing.

The Google Cloud OAuth client doesn't change: Google still redirects to the Worker's
`/auth/google/callback`; only the Worker's final redirect goes to the app.

### 2.3 Passkeys from the app

- In `/auth/passkey/register/verify` and `/auth/passkey/login/verify`: when the request has the header
  `X-A80-App: 1`, create an app session instead of a cookie session and add `token` to the JSON reply. Adding a
  passkey to an account the app is signed in to works through 2.1 (the bearer makes `currentUser` succeed).
- Accept the app's origin. Credential Manager sends `android:apk-key-hash:<base64url, no padding, of the signing
  certificate's SHA-256 bytes>`. Compute it from `env.ANDROID_CERT_SHA256` (hex with colons, comma-separated
  list) and pass `expectedOrigin: [url.origin, ...appOrigins]` to both `verify…Response` calls in
  `src/passkeys.js`.
- Add `delegate_permission/common.get_login_creds` to the relations in `assetLinks()` so Android lets the app
  use the site's passkeys. Keep the existing two relations (old TWA installs still need them).
- The challenge stays in the `pk_chal` cookie; the app keeps cookies for `/auth/*` calls in an in-memory
  `CookieJar`.

### 2.4 Open a signed-in web page from the app

Connecting Google Calendar is a browser OAuth flow tied to the cookie session. Add:

- `POST /api/app/web-link` (app session only, not personal tokens) with `{ next }`, where `next` is one of an
  allow-list (`/auth/google/calendar`). Returns `{ url }`: `/auth/app/web?code=…`, valid 60 seconds, single use
  (reuse `app_codes` with `challenge = 'web:' + next`).
- `GET /auth/app/web?code=…` starts a normal cookie session in the browser and redirects to `next`.
- When the Google Calendar callback finishes for a session that came from the app (mark it via the state
  purpose `calendar-app`, set when `/auth/google/calendar` is opened with `?app=1`), redirect to
  `com.knhdixh.attendance80:/gcal?result=connected` or `?error=<code>` instead of `/#settings`.

### 2.5 Tests (`test/app-auth.test.js`)

Cover: code exchange success; wrong verifier; reused code; expired code; bearer `a80s_` reaches `/api/data`;
bearer `a80s_` is not restricted by `tokenAllows`; expired session is 401; logout deletes the row; sliding expiry;
web-link allow-list and single use; `apk-key-hash` origin computed correctly from a known fingerprint (assert
against a value you compute independently in the test with `node:crypto`); assetlinks includes the new relation.

---

## 3. Phases

### Phase 1: server auth + project skeleton

1. Implement section 2 with its tests. `npm test` passes.
2. Create `android-native/` with the Gradle wrapper, version catalog, `app` module, both flavors, signing config
   reading `../android/keystore.properties` (only when it exists, as `android/app/build.gradle` does), R8 on for
   release.
3. `App`, `AppContainer`, `MainActivity`, theme, an empty `NavHost`.
4. `net/`: Retrofit `Api` with every endpoint from section 4, DTOs for every response, `ApiError(status,
   message)` parsed from `{ error }`, and an `AuthInterceptor` that adds `Authorization: Bearer <token>` to
   `/api/*` calls. A 401 on `/api/*` clears the token and sends the user to sign-in.
5. `auth/TokenStore`: stores the token encrypted with an AES-GCM key from the Android Keystore
   (`KeyGenParameterSpec`, alias `a80_session`), ciphertext in DataStore. Don't use the deprecated
   `EncryptedSharedPreferences`.
6. Sign-in screen (see 5.1) with all three methods working against staging or a local dev server.

**Checks:** `npm test`; `./gradlew :app:assembleProdDebug :app:testProdDebugUnitTest :app:lintProdDebug`;
MockWebServer tests for the interceptor (bearer added, 401 clears the token) and for the PKCE exchange
(verifier/challenge pair is valid base64url SHA-256).

### Phase 2: logic port with golden tests

1. Port to `logic/`:
   - `Stats.kt`: `TAGS`, `STATUSES`, `splitGroup`, `countsAttendance`, `effectiveTag`, `computeStats`,
     `statsByCourse`.
   - `Breaks.kt`: everything exported by `public/breaks.js` (`HOLIDAY_RE`, `breakModel`, `simulateBreak`,
     `findBreakWindows`, `longestSafe`, `weekOverview`, `startDayGrid`, date helpers).
   - `Plan.kt`: `localMidnight`, `effortOf`, `buildPlan` (DST-safe; use `java.time` with the given `ZoneId`
     rather than porting the `Intl` offset trick, but the results must match).
   - `FocusTimer.kt`: the timer half of `public/focus.js` (`cleanSettings`, `idle`, `start`, `setLength`,
     `adjust`, `pause`, `resume`, `remaining`, `length`, `progress`, `isDue`, `finish`, `stop`) as an immutable
     `data class` with the same JSON shape (`phase, next, custom, run, startedAt, endsAt, pausedAt, pausedMs, done,
     target, updatedAt`). `android/.../FocusTimer.java` is an existing Java port of the same code; use it as a
     second reference.
   - `FocusStats.kt`: `dailyTotals`, `minutesByHomework`, `focusStats`.
   - `Groups.kt`: the scope and group helpers from `public/app.js` (`groupFamilies`, `applyScope`,
     `groupLabel`, `scopeNote`, `toggleScope` rules).
   - Use `Double` where JS uses numbers that can be fractional (hours unit), `Long` for epoch ms. Keep the
     `1e-9` epsilons.
2. Golden tests. Write `android-native/tools/golden.mjs` (Node 22, run from the repo root:
   `node android-native/tools/golden.mjs`). It imports the real `public/*.js` modules, runs each exported function
   on the fixtures in `android-native/tools/fixtures/*.json`, and writes
   `android-native/app/src/test/resources/golden/<name>.json` with inputs and outputs. Fixtures must include:
   - a semester of EPITA-like sessions: several courses, group-split courses (`GR1 - …`, `GR2 - …`), a
     "Vacances" all-day holiday, exam and mandatory tags, optional sessions, excused, past and future absences;
   - both units (`sessions`, `hours`) and thresholds 0.8 and 0.7;
   - a Sisu calendar course with `attendance: 0`;
   - `now` values on both DST changes for `Europe/Paris` and `Europe/Helsinki`, plus a normal day;
   - focus timers in every phase, paused and running, stale and fresh, with `custom` set;
   - focus sessions across a year (for streaks, week vs last week, heatmap).
   Kotlin tests in `app/src/test/.../logic/` load each golden file and compare field by field (doubles with a
   1e-9 tolerance). Commit the generated golden files.

**Checks:** all golden tests pass; `npm test` still passes (the script must not change `public/`).

### Phase 3: data layer, shell and navigation

1. `DataRepository` as in section 1, with `refresh()`, `snapshot: StateFlow<AppData?>`, `lastSynced`, and a
   `loading/error` state. Pull-to-refresh on every list screen calls `refresh()`.
2. `Scope`: the calendar "Count" filter (`a80-scope` in the web). Stored in DataStore. Applied before every
   computation, exactly like `applyScope` (sessions with no calendars always count; a scope covering every
   calendar is the same as none).
3. Navigation. Phone: bottom bar with **Today, Attendance, Homework, Focus, More**. *More* lists Upcoming,
   Sessions, Courses, Breaks, Assistant, Settings. On widths ≥ 600dp use a navigation rail with every
   destination. Top app bar on each screen: title, the scope chips (only when there are 2+ calendars), search.
4. Deep links and routes: `a80://today`, `/focus`, `/homework`, `/homework/new`, `/session/{id}`,
   `/course/{id}` (internal routes; the custom scheme from section 2 is only for auth). App shortcuts
   (`res/xml/shortcuts.xml`): Focus, Today, Homework, Add homework.
5. A global search sheet (the web's "/" command palette, `paletteItems` in `public/app.js`,
   `public/focus-ui.js` and `public/calendars-ui.js`): search courses, sessions, homework and actions; an
   "Ask the assistant: …" item at the bottom.
6. A periodic WorkManager job (every 60 minutes, network required) refreshes the snapshot and updates the widget.

**Checks:** unit tests for the repository with MockWebServer (cache-then-network, atomic write, revert on a
failed mutation); the app opens offline from the cache.

### Phase 4: screens (parity with the website)

Build them in this order. For each, read the named web module first, and reuse its wording.

| # | Screen | Web source | Has |
|---|---|---|---|
| 4.1 | Today | `public/today-ui.js`, `plan.js` | headline (now/next class), current and next session, *Do next* list ranked by latest start with urgency, today's free blocks with the to-dos that fit, tomorrow's first class, overlapping sessions this week, week load (work due vs free hours), focus summary card (`todayPanel` in `focus-ui.js`) |
| 4.2 | Attendance | `renderOverview`, `courseCard` in `app.js` | overall line, per-course cards with projected %, absences used/allowed, skips left, level ok/risk/fail, group banner for unpicked groups, unmarked-past-sessions count |
| 4.3 | Course detail | `openCourse`, `renderCourses` in `app.js` | stats, session list, tag picker (normal/mandatory/exam/optional), tracked toggle, recording link, group picker for group-split families (`/api/courses/tracked`) |
| 4.4 | Courses | `renderCourses` | all courses, tracked switch, tag, attendance off for no-attendance calendars |
| 4.5 | Sessions | `renderSessions`, `sessionRow` | filters (unmarked/past/upcoming/course), mark ✓ / ✗ / E with one tap, swipe actions, multi-select bulk mark (`/api/attendance/bulk`), session sheet with tag, note, homework due at it, catch-up add/remove (`/api/catchups`) |
| 4.6 | Upcoming | `renderUpcoming` | week view with previous/next week, sessions per day, homework due per day |
| 4.7 | Homework | `public/homework-ui.js` | buckets Overdue/Today/Tomorrow/Next 7 days/Later, done toggle, add/edit sheet (title, course, due date, time or all day, "due at the next session of X", reminder `REMINDERS`, effort `EFFORTS`, notes), delete with undo snackbar, Google Calendar sync state per item, focus minutes per item, "Focus on this" |
| 4.8 | Focus | `public/focus-ui.js` | big timer with phase, Start/Pause/Skip/Stop, ±time adjust and presets (`PRESETS`), target picker (homework, course, own tasks), tags, stats for week/30 days/year, week chart, year heatmap, per course/task/tag breakdown, recent sessions with delete, settings form (lengths, long break every, daily goal, auto breaks, auto focus, sound, notifications, own tasks and tags) saved with `PUT /api/focus/settings` |
| 4.9 | Breaks | `public/breaks-ui.js` | length chips (`LENGTHS`), month calendar of start days coloured by verdict, tap two days for a custom range, verdict panel with per-course before/after, best-window suggestions, saved breaks with remove |
| 4.10 | Assistant | `public/assistant-ui.js` | chat with markdown replies, suggestion chips (`SUGGESTIONS`), daily usage, undo per action (types `homework_added`, `homework_updated`, `break_saved`, `attendance`), clear chat; history kept on device (DataStore, last 50 messages), sends the device time zone |
| 4.11 | Settings | `renderSettings` in `app.js`, `calendars-ui.js`, `homework-ui.js` (`integrationsPanel`), `tokens-ui.js`, `byok-ui.js` | account (name, email, avatar); minimum % and unit; calendars: add link (with Zeus help link `/ics-guide`), upload `.ics` via the system file picker, rename, attendance switch, first day, update now, replace file, remove, sync log; calendar feeds (copy/share timetable and homework links, pick groups, rotate link); Google Calendar connect (2.4)/resync/disconnect; personal tokens list/create (show once, copy)/revoke; assistant key (BYOK) add/remove; passkeys list/add/remove; sign out; delete all data; delete account (type DELETE); links to privacy, terms, `/byok-guide` |

Shared rules for all screens:

- Every list has an empty state with the web's sentence.
- Errors from the API show as a snackbar with the server's message.
- Dates and times use the device locale and time zone (`java.time`, `DateTimeFormatter.ofLocalizedDate`).
- Session status colours: present green, absent red, excused purple, planned skip orange, as the web's
  `--green`, `--red`, `--purple`, `--orange`.
- Long lists use `LazyColumn` with stable keys (`id`).

**Checks per screen:** a ViewModel unit test for its main state (fixture `AppData` + pinned `Clock`), and a
Compose UI test (Robolectric) that the screen renders its empty state and one populated state without crashing.

### Phase 5: focus timer, natively

The web keeps the timer in `localStorage`; the app keeps it in `FocusStore` (DataStore) and is the only timer on
the device. Port the behaviour of the existing native pieces in `android/app/src/main/java/.../`
(`FocusController`, `FocusNotifications`, `FocusReceiver`, `FocusTileService`, `QuickFocusActivity`) to Kotlin on
top of `logic/FocusTimer.kt`, dropping everything to do with `Bridge.java` and Chrome.

1. `FocusController` (singleton in `AppContainer`): `start(target)`, `pause()`, `resume()`, `skip()`,
   `stop()`, `adjust(min)`, `setLength(min)`. Each change stamps `updatedAt`, saves, reschedules the alarm,
   updates the notification, tile and widget.
2. Phase end: an exact alarm (`setExactAndAllowWhileIdle`, permission `USE_EXACT_ALARM`) at `endsAt` fires
   `FocusReceiver`, which calls `finish()`, records the log, and starts the next phase if auto-start applies.
   Reschedule after boot (`RECEIVE_BOOT_COMPLETED`) and after time or time-zone changes.
3. Ongoing notification while a phase runs: countdown chronometer (`setUsesChronometer(true)`,
   `setChronometerCountDown(true)`, `setWhen(endsAt)`), progress, actions Pause/Resume, Skip, Stop; visible on
   the lock screen. On Android 16+ request promotion as a Live Update (`setRequestPromotedOngoing(true)` with
   `ProgressStyle`) when available, and fall back silently. A separate high-priority channel for "phase ended"
   with the chime (`res/raw/`), honouring the `sound` and `notify` settings.
4. Recording: `Outbox` stores finished focus logs (same JSON as `entry()` in `focus.js`) and a WorkManager job
   posts them to `POST /api/focus` with network required and exponential backoff. The server ignores duplicates
   by `client_id`, so retries are safe. Remove an entry only after a 2xx, or a 400 (it will never succeed; log
   it).
5. Quick-settings tile (start/pause/resume) and the quick-focus sheet from the tile's long-press and the widget
   (`QuickFocusActivity`: pick a target and a length, then start).
6. Settings come from `focus.settings` in `/api/data`; edits go to `PUT /api/focus/settings` and update the
   snapshot.
7. **Migration from the TWA:** on first launch, read the old `SharedPreferences("focus")` keys (`timer`,
   `settings`, pending logs and tasks, see `FocusStore.java`), import the timer if it's running, move pending logs
   into the `Outbox`, then clear those prefs.

**Checks:** unit tests for the controller with a fake clock and fake alarm scheduler (start → alarm at
`endsAt`; pause cancels it; resume reschedules; finish with `autoBreaks` starts a break; stale finish doesn't);
Outbox test with MockWebServer (retry on 500, drop on 400, idempotent).

### Phase 6: widget

A Glance widget (replaces `FocusWidget.java`): the class you're in or the next one, the top 3 of *Do next*, and
the timer with Start/Pause. Sizes: 2×2 shows the timer and next class; 4×2 and up adds *Do next*. Dynamic colour
on Android 12+ (as the current widget). Data comes from a `WidgetSnapshot` written by `DataRepository` and
`FocusController`; the widget never makes network calls. Tapping a session opens it, tapping a to-do opens it,
tapping the timer opens Focus.

**Checks:** a unit test that `WidgetSnapshot` is built correctly from fixture data; manual check on a device.

### Phase 7: native extras

These are what the website can't do. Each has a switch in Settings → Notifications, all on by default except 7.2.

1. **Check-in after class.** When a tracked session that is unmarked ends, post a notification "Were you at
   <course>?" with actions Present / Absent / Excused, which call `PATCH /api/attendance/{id}` directly from a
   `BroadcastReceiver` (via WorkManager for the network call). Schedule with inexact alarms for today's and
   tomorrow's sessions whenever the snapshot changes. Absent on a past session also offers "Add catch-up"
   (as the web does).
2. **Homework reminders on the device**, from `remind_minutes`, using the same rule as `reminderMinutes()` in
   `src/homework.js` (all-day items remind at 18:00). Off by default when Google Calendar sync is connected, to
   avoid double reminders. Notification actions: Done, Focus on this.
3. **Open .ics files**: an intent filter for `text/calendar` and `*.ics` (VIEW and SEND) that opens Settings →
   Calendars with the add-file sheet prefilled.
4. **Share to Attendance80**: a SEND `text/plain` filter that opens the add-homework sheet with the shared text
   as the title (first line) and notes (the rest).
5. **Exam/mandatory warning**: a morning notification (08:00) on days with an exam or mandatory session.

**Checks:** unit tests for the schedulers (which sessions get a check-in, reminder times for timed and all-day
homework across a DST change).

### Phase 8: polish

- Dark theme, dynamic colour, large font scale (200%) and TalkBack labels on every icon button.
- Predictive back, edge-to-edge insets, tablet/foldable layout (list-detail on Sessions, Homework and Courses
  with `ListDetailPaneScaffold` when width ≥ 600dp).
- Notification permission asked in context (first time a timer starts or a reminder switch turns on), never at
  launch.
- Baseline profile for startup (`androidx.baselineprofile`), only if startup on a mid-range device is over 1 s.
- In-app "What's new" is not needed.

### Phase 9: release and replacing the TWA

1. Set `versionCode` above the TWA's (read it from `android/app/build.gradle`) and `versionName` to `2.0.0`.
2. Build the signed release for both flavors with the existing key; check the certificate SHA-256 matches
   `ANDROID_CERT_SHA256` in `wrangler.jsonc` (`apksigner verify --print-certs`).
3. Install over an existing TWA install on a device: the app updates in place, a running timer survives
   (Phase 5 migration), and passkey sign-in works.
4. Then, as one change: move `android/` to `android-twa-legacy/` (keep the keystore files where `build.gradle`
   expects them, or update the paths), rename `android-native/` to `android/`, rewrite `android/README.md` for
   the native app (build, flavors, signing, the golden-test script), and update the README's "Install it" row.
   Keep the `use_as_origin` relation and the web bridge code in `public/focus-ui.js`: old installs still use
   them, and they're harmless.

---

## 4. API reference (what the app calls)

All JSON. Errors are `{ error: string }` with a 4xx/5xx status. Auth: `Authorization: Bearer a80s_…`.

| Method | Path | Body → Response |
|---|---|---|
| GET | `/auth/config` | → `{ google, dev, passkeys, signup }` |
| GET | `/auth/app/google?challenge=` | browser only (Custom Tab) → redirect to `com.knhdixh.attendance80:/auth?code=` |
| POST | `/auth/app/token` | `{ code, verifier }` → `{ token }` |
| POST | `/auth/passkey/register/options` | `{ name? }` → WebAuthn creation options JSON |
| POST | `/auth/passkey/register/verify` | `{ response, deviceName }` + `X-A80-App: 1` → `{ ok, created, token? }` |
| POST | `/auth/passkey/login/options` | → WebAuthn request options JSON |
| POST | `/auth/passkey/login/verify` | `{ response }` + `X-A80-App: 1` → `{ ok, token }` |
| POST | `/auth/logout` | → `{ ok }` |
| GET | `/api/me` | → `{ user }` |
| GET | `/api/data` | → `{ user, account, courses, events, homework, breaks, integrations, assistant, calendars, calendarSyncs, focus }` |
| GET | `/api/plan?tz=` | → plan for scripts (the app computes its own; not needed) |
| GET | `/api/focus?since=` | → `{ sessions, since }` (Focus page loads a year) |
| POST | `/api/focus` | focus log → session row |
| DELETE | `/api/focus/{id}` | → `{ ok }` |
| PUT | `/api/focus/settings` | partial settings → `{ settings }` |
| PATCH | `/api/attendance/{id}` | `{ status?, tag?, note? }` → `{ ok }` |
| POST | `/api/attendance/bulk` | `{ ids, status }` → `{ updated }` |
| PATCH | `/api/courses/{id}` | `{ tracked?, tag?, recording_url? }` → `{ ok }` |
| POST | `/api/courses/tracked` | `{ updates: [{ id, tracked }] }` → `{ ok }` |
| POST | `/api/catchups` | `{ event_id, tz }` → homework row |
| POST | `/api/homework` | homework input → homework row |
| PATCH | `/api/homework/{id}` | partial input → homework row |
| DELETE | `/api/homework/{id}` | → `{ ok }` |
| POST | `/api/breaks` | `{ from, to, label, tz }` → `{ break, sessions }` |
| DELETE | `/api/breaks/{id}` | → `{ restored }` |
| POST | `/api/calendars` | `{ kind: 'link', url, from?, name?, attendance? }` or `{ kind: 'file', ics, fileName?, name?, attendance? }` → import summary |
| PATCH | `/api/calendars/{id}` | `{ name?, attendance?, from? }` → `{ calendar, …summary? }` |
| POST | `/api/calendars/{id}/sync` | → import summary |
| POST | `/api/calendars/{id}/file` | `{ ics, fileName? }` → import summary |
| DELETE | `/api/calendars/{id}` | → `{ removed }` |
| POST | `/api/gcal/resync` | → resync result |
| POST | `/api/gcal/disconnect` | → `{ ok }` |
| POST | `/api/feed/rotate` | → `{ ok }` |
| POST | `/api/app/web-link` | `{ next }` → `{ url }` |
| GET/POST | `/api/tokens` | `{ name, scope }` → token row with `token` (shown once) |
| DELETE | `/api/tokens/{id}` | → `{ ok }` |
| PUT/DELETE | `/api/ai-key` | `{ provider, apiKey, accountId?, model? }` (see `byok-ui.js`) → `{ key, usage }` / `{ usage }` |
| POST | `/api/assistant/chat` | `{ messages: [{ role, content }], tz }` → `{ reply, actions, usage }` |
| DELETE | `/api/passkeys/{id}` | → `{ ok }` |
| PATCH | `/api/settings` | `{ threshold, unit }` → `{ ok }` |
| DELETE | `/api/data` | → `{ ok }` |
| DELETE | `/api/account` | `{ confirm: 'DELETE' }` → `{ ok }` |

Field names and types: read the SQL in `src/worker.js` (`/api/data`), `HOMEWORK_COLUMNS` in
`src/homework-store.js`, `FOCUS_COLUMNS` in `src/focus.js`, `listCalendars` in `src/calendars.js` and the
migrations. Booleans stored as SQLite integers arrive as `0`/`1` (`tracked`, `done`, `completed`,
`attendance` on courses); write a serializer that accepts both numbers and booleans. Times are epoch ms; dates are
`YYYY-MM-DD` strings; `homework.due_time` is `HH:MM` or null.

---

## 5. Screen notes that aren't obvious from the web

### 5.1 Sign-in

- Read `/auth/config`. Show "Continue with Google" when `google`, "Sign in with a passkey" always, "Create an
  account with a passkey" (asks for a name) when `signup`, and a dev sign-in when `dev` (debug builds only).
- Google: generate a 32-byte random verifier, challenge = base64url(SHA-256(verifier)); open
  `/auth/app/google?challenge=` in a Custom Tab; receive `com.knhdixh.attendance80:/auth?code=` in
  `MainActivity` (intent filter on that scheme and path, `launchMode="singleTask"`); exchange at
  `/auth/app/token`. Keep the verifier in memory and in `SavedStateHandle` (the activity may be recreated).
- Passkeys: get options JSON from the server, pass it unchanged to `CredentialManager`
  (`CreatePublicKeyCredentialRequest` / `GetPublicKeyCredentialOption`), send the returned
  `registrationResponseJson` / `authenticationResponseJson` back as `response`. Map
  `NoCredentialException` to "No passkey for Attendance80 on this device." and user cancellation to nothing.

### 5.2 Homework "due at the next session of X"

The web offers the course's upcoming sessions as due options (`sessionOptions` in `homework-ui.js`); picking one
sets `due_date` and `due_time` to that session's start in the device time zone. Send `tz` as the device's IANA
zone (`ZoneId.systemDefault().id`) on every create and update.

### 5.3 Breaks calendar

`startDayGrid` is the expensive call (one simulation per day). Compute it off the main thread when the length or
data changes, never during composition.

### 5.4 Calendar file upload

Read the picked file with `ContentResolver`, refuse over 5 MB (`MAX_ICS_BYTES`) before sending, decode as UTF-8,
and send it as `ics`.

---

## 6. Design

**Match the current website, not the knhdixh cookbook.** The owner has other sites on knhdixh.com that follow a
"knhdixh cookbook" (black on white, Inter, no dark mode, no icons). This app is an explicit exception: ignore
the cookbook entirely, even if you find it referenced elsewhere. The source of truth for the look is
`public/styles.css` (its header comment: "a quiet, Todoist-like task UI") and the live site.

What that means on Android:

- **Colours:** use the website's tokens as a fixed Material 3 colour scheme, light and dark (follow the system
  setting). Don't use dynamic (wallpaper) colour in the app itself: the site has one accent colour and the app
  keeps it. The home-screen widget is the one exception and keeps following wallpaper colours on Android 12+, as
  the current widget does.

  | Token | Light | Dark | Material role / use |
  |---|---|---|---|
  | `--bg` | `#FFFFFF` | `#1E1E1E` | background, surface |
  | `--sidebar` | `#FCFAF8` | `#262626` | navigation bar / rail, bottom sheets |
  | `--raised` | `#FFFFFF` | `#2A2A2A` | dialogs, menus (surfaceContainerHigh) |
  | `--text` | `#202020` | `#E8E8E8` | onBackground, onSurface |
  | `--text-2` | `#666666` | `#A3A3A3` | secondary text (onSurfaceVariant) |
  | `--text-3` | `#999999` | `#737373` | meta, counts, placeholders |
  | `--line` | `#F0F0F0` | `#2E2E2E` | row hairlines (outlineVariant) |
  | `--line-2` | `#E5E5E5` | `#3A3A3A` | input borders (outline) |
  | `--accent` | `#2A6AE0` | `#5D8FF0` | primary |
  | `--accent-soft` | `#EEF3FD` | `#5D8FF0` at 14% | selected nav item, primaryContainer |
  | `--green` | `#058527` | `#4FB26B` | present, "safe" |
  | `--orange` | `#AD6200` | `#E3A03F` | planned skip, "at risk" |
  | `--red` | `#D1453B` | `#FF6F61` | absent, "below minimum", errors |
  | `--purple` | `#692FC2` | `#A882FF` | excused |

- **Type:** the system font (Roboto on Android). Body 14sp; secondary and meta 13sp; chips 11.5sp; page titles
  24sp bold; section headings 14sp bold; big stats 28sp bold with tabular numbers (`fontFeatureSettings = "tnum"`).
- **Shapes:** small radii: 4dp chips, 6dp buttons and segmented controls, 8dp cards and inputs, 12–14dp sheets and
  dialogs, fully round only for avatars and the focus timer ring.
- **Layout:** lists are flat hairline rows (1dp `--line` divider), not elevated cards. Panels are a bold 14sp
  heading over a hairline. Selected navigation item: `--accent-soft` background with `--accent` text, as the web
  sidebar. Few shadows (only menus and sheets).
- **Icons:** Material Symbols (outlined) matching the web's stroke icons in `public/index.html` (today, home,
  calendar, check circle, timer, sun for breaks, sparkle for assistant, grid for courses, gear).
- **Motion:** short, transform/opacity only, one easing curve (`CubicBezierEasing(0.22f, 1f, 0.36f, 1f)`), and
  none when the system's "remove animations" setting is on.
- Reuse the launcher icons (including the monochrome layer for themed icons) and the notification icon from
  `android/app/src/main/res/`. Keep every token in `ui/theme/` so the look can be changed in one place.

---

## 7. Definition of done

- Every feature in the README's feature table that runs in the browser works in the app (Phase 4 table).
- `npm test` and `./gradlew testProdDebugUnitTest lintProdDebug assembleProdRelease` pass.
- Golden tests cover stats, breaks, plan, focus timer and focus stats.
- Installing over the TWA keeps the user's timer and sign-in works with Google and with a passkey.
- No secrets in git; `git status` shows no keystore or properties files added.
