# Review 1: Phases 1 and 2 (commits 77e6ab1..39c8e19)

Reviewed on 2026-10-28. Fix everything under "Must fix" before starting Phase 3, one commit per item, and record
each in `docs/native-android-progress.md` under a new "Review 1 fixes" section with the check you ran.

Process note: the plan says to stop for review at the end of Phase 1. Phase 2 was started without that stop.
Stop at the next checkpoint (end of Phase 5) even if everything passes.

Reruns by the reviewer: `npm test` 82/82 pass; `./gradlew :app:testProdDebugUnitTest :app:lintProdDebug
:app:assembleProdDebug` pass (25 tests). But `TZ=UTC ./gradlew :app:testProdDebugUnitTest` **fails**
`FocusStatsGoldenTest` (item 2).

## Must fix

1. **DTOs don't match the real `/api/data`** (`net/Models.kt`). As written, decoding a real response throws.
   - `IntegrationsDto.timetableFeedUrl` is required but the server never sends it. Remove it (the app builds the
     timetable feed URL itself from `feedUrl`, as `timetableFeedUrl()` in `public/homework-ui.js` does).
   - `HomeworkDto.remind_minutes` must be `Int?` (NULL = no reminder); `effort_minutes`, `due_time`, `course_id`,
     `event_id`, `gcal_event_id`, `sync_error` nullable too. `FocusSessionDto.label`, `homework_id`,
     `course_id` nullable.
   - `FocusSettingsDto` field names are the server's: `focus, short, long, longEvery, goal, autoBreaks,
     autoFocus, sound, notify, tasks, tags` (use `@SerialName`), with defaults equal to `DEFAULT_SETTINGS` in
     `public/focus.js` (`autoBreaks = true`).
   - `assistant` is flat: `{ used, limit, owner, byok }` (see `usage()` in `src/assistant.js`), nullable.
   - `integrations.gcal` is `{ email, calendarId, error }`.
   - Go through every DTO against the SQL in `src/worker.js` and the migrations; anything nullable in SQL is
     nullable in Kotlin.
   - Add a test that decodes a real response: generate `app/src/test/resources/api/data.json` from the local
     dev server (`npm run dev`, dev login, `GET /api/data` after importing a fixture `.ics` and adding homework
     without a reminder, a focus session without a label, a break) and assert it decodes and key fields are right.
     Also one with every optional section empty (new account).

2. **Golden tests depend on the machine's time zone.** `focus.js` uses the process's local zone (`dayStart`,
   `dayKey`, `weekStart`, `getHours`). In `golden.mjs` set `process.env.TZ = 'Europe/Paris'` before importing
   anything, write the zone into the golden file, and make the Kotlin tests use that zone instead of
   `ZoneId.systemDefault()`. Add a second run with `Europe/Helsinki`. Check: tests pass with `TZ=UTC`,
   `TZ=America/New_York` and `TZ=Europe/Helsinki` set when running Gradle.

3. **Regexes differ between JS and the JVM.** `HOLIDAY_RE`: JS `\b` is ASCII-only, Java's `IGNORE_CASE` is
   ASCII-only without `UNICODE_CASE` ("Jour férié", "CONGÉS", "Fériés" give different answers). `splitGroup`: JS
   `\s` matches non-breaking spaces, Java's doesn't. Write the Kotlin regexes so they behave like the JS ones
   (explicit character classes, not flags), and add golden cases for: "Jour férié", "JOUR FÉRIÉ", "Congés",
   "CONGÉS", "Fériés", "Holiday", "Bank holiday", "Vacances de Noël", and group names with a non-breaking space,
   an en dash, an em dash, "gr3", "G 12".

4. **Golden tests skip fields.** A golden test must compare the whole output. Replace the hand-picked field
   comparisons with a generic deep comparison: serialize the Kotlin result to a `JsonElement` and compare it
   recursively to the stored JS output (doubles within 1e-9, `null` in JS for `Infinity`). Fields the Kotlin
   model has no use for may be listed in an explicit, commented ignore list. Today these are unchecked:
   - Breaks: `weekOverview12`, `grid7` (generated, never compared); `to`, `holidayDays`, `blockers`, `holiday`
     in week overview and grid; simulation sessions/conflicts (only sizes), course ids, `before`, all of `after`
     except `remaining`; window `holidayDays`; `longest` when expected is null, and its `sessions`.
   - FocusStats: `range.hours`, `courses`, `homework`, `tags`; `focusSummary.last7.byTag`; `best` and
     `dailyTotals` only one way. Port `day()` (the heatmap needs it) and test it.
   - Stats: `statsByCourse` beyond `total/remaining/level`; `missedMustAttend` ids.
   - Timer: `cleanSettings` results (decoded, never asserted); `start` `startedAt/run/target`; all fields of
     finish/stop logs.

5. **Fixture gaps.** Add: sessions in March so the spring DST points have data; Helsinki DST cases for breaks and
   plan; a course with no room to skip (`noSkip` non-empty); timers that are a paused break, a stale break, and
   idle with `next` = short/long; settings with `autoFocus = true`, `autoBreaks = false`, `longEvery = 2`,
   custom lengths; out-of-range and junk settings for `cleanSettings`; focus sessions without a label and with
   tags.

6. **The PKCE verifier doesn't survive process death** (`MainActivity.kt:37` builds the view model with a fresh
   `SavedStateHandle()`). Create it with `viewModelFactory { initializer { SignInViewModel(..., 
   createSavedStateHandle()) } }`.

7. **The auth deep link is handled again on every `onCreate`** (rotation, reopening from recents), which
   exchanges a used code and shows an error. Handle it only when `savedInstanceState == null` and in
   `onNewIntent`, then clear the intent's data.

8. **Startup can sign the user out.** `TokenStore` loads the token asynchronously, so an `/api` call made before
   it's loaded goes out without a bearer, gets a 401, and `clearToken()` deletes the stored token. Load the
   token before the first request (await it in the repository, or block the interceptor until loaded), and only
   clear the token on a 401 for a request that actually carried it. Choose the start destination after the token
   is loaded (no flash of the sign-in screen). Add a test for this.

## Should fix

9. Server, `src/worker.js`:
   - `/auth/app/google`: validate the challenge with `/^[A-Za-z0-9_-]{43}$/`, not only its length (a `.` would
     break the state cookie split). Same check in `/auth/app/dev`.
   - Delete expired `app_codes` rows when inserting a new one (as `saveChallenge` does for `auth_challenges`).
   - The `calendar-app` callback redirects to `/` when there's no session; send it to
     `com.knhdixh.attendance80:/gcal?error=session` instead.
   - Tests are missing for the Google callback with purpose `app` (mock `fetch` as `test/gcal.test.js` does):
     a code is created with the challenge from the state cookie, and `domain`/`state` failures redirect to the app
     scheme. Also for `calendar-app`, and for passkey verify with `X-A80-App: 1` returning `token` and no cookie.
   - Remove the leftover `// Wait, …` line and unused `createHash` in the apk-key-hash test.
10. `allowBackup`: exclude the token DataStore from backup and device transfer (`dataExtractionRules` and
    `fullBackupContent`); the Keystore key doesn't travel with it.
11. `-PbaseUrl` must only apply to the debug build type.
12. `USE_EXACT_ALARM` is restricted on Play to alarm/calendar apps. Keep it (the timer needs exact alarms), but add
    a fallback to `SCHEDULE_EXACT_ALARM` + `canScheduleExactAlarms()` in Phase 5, and note it in the log.
13. Dev sign-in: URL-encode the email.
14. Unlisted libraries: `okhttp-logging` is fine for debug only (`debugImplementation`, never logs bodies of
    `/auth/*` or the `Authorization` header). Keep `material-icons-extended` only if R8 strips it in release;
    otherwise use the few icons needed as vector drawables. Record both in the deviations list. Remove
    `isLenient = true` from the JSON config.

## Nits (fix when you're in the file)

- `effortOf`, `nextLength`, `start`: JS `||` treats 0 as missing; mirror that.
- `FocusStats.kt:165`: use the JS truthiness (`label != null && label != ""`), not `isNotBlank()`.
- `groupFamilies`: sort with a `Collator` (`localeCompare`), not code points.
- `golden.mjs`: `atHourResults` is computed and unused; test it or remove it.
- `TokenCipherTest` tests a fake cipher. Fine for the JVM, but say so in the test name, and add an instrumented
  test for `AndroidKeystoreCipher` in Phase 8.
