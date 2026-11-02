# Phase 4 review

Verdict: **PASS after round 1**, provided the coordinator's full check of `native/phase-5` passes. The fix round is `1b449fc`, `88a5472`, `58b4242` and `656c55f`, logged in `fa5040f`. The initial FIX review is kept below; the acceptance is at the end.

## Findings

1. **Delete the cached account data on sign-out and account deletion, and keep it out of backups (privacy).** `DataRepository.kt:54` writes `filesDir/data-<sha256(token)>.json` with the whole `/api/data` snapshot: timetable, attendance, homework with notes, habits, countdowns and focus history. Nothing ever deletes it. `AuthRepository.logout()` (`AuthRepository.kt:98`) only clears the token. `AccountSettingsActions.deleteAccount()` (`SettingsActions.kt`, end) calls `logout()`. When the token changes, `DataRepository.start()` only clears memory.
   - **How it fails:** sign out, or use Delete my account. The server deletes the account, but every byte of the account stays on the phone indefinitely.
   - **It gets worse over time:** each sign-in gets a new token, so each one leaves another orphan file.
   - **It reaches Google's servers:** `backup_rules.xml` and `data_extraction_rules.xml` exclude only `auth_prefs`, so these files go to cloud backup and device transfer. The token is not restored, so a restored file can never be read by the app. It is pure leakage.
   - **Answer to the 4.11 open question:** yes, and in this phase, not Phase 8. Phase 4 added Delete my account, and a user who deletes their account expects the data to be gone.
   - **What the fix must cover:**
     - On sign-out, account deletion and a 401 that drops the token, delete that session's cache file and its `.tmp`.
     - On `start()`, remove any `data-*.json` that doesn't belong to the current token. This cleans up existing installs.
     - Exclude `data-*.json` from both cloud backup and device transfer.
     - After Delete all my data, the reload overwrites the cache. Check that this still happens.
   - **Tests:** a repository test that signs in, refreshes, signs out and asserts the file is gone; the same through `deleteAccount` with MockWebServer; and a stale-file cleanup case. The palette recents DataStore holds only IDs, so it can stay.

2. **Stop the lists flashing to a spinner and jumping to the top on every mutation and every minute.** Several screens drop back to the spinner whenever their derived state doesn't match the current input:
   - `CoursesScreen.kt:73–75`, `SessionsScreen.kt:139–140`, `AttendanceScreen.kt:40–41`, `TodayScreen.kt:32–33` and `CourseScreen.kt:79` show `CircularProgressIndicator` and `return` whenever the derived state doesn't match the current `data` (and, for Courses and Sessions, the current minute).
   - Their `LazyColumn` uses its internal `rememberLazyListState()`, so leaving composition throws the scroll position away.
   - Every optimistic `publish` gives a new `AppData`, and `AppNav` ticks `now` every 60 s.
   - **How it fails:** on Sessions, scroll down and tap ✓ or swipe a row. The list blinks to a spinner and comes back at the top. The same happens on the minute tick, and when toggling Tracked on Courses or checking homework on Today. This is the core "mark with one tap" flow of 4.5.
   - **The `CoursesRepositoryUiTest` flake is this bug.** The log flagged it at `trackedControlRollsBackAndPersists`, which failed with "could not find courses-list". The test waits on `repo.snapshot` and then scrolls `courses-list` while the screen is still showing the spinner.
   - Upcoming (4.6) and Homework already fixed this for themselves by hoisting the list state, and Breaks by keeping the last result on screen.
   - **What the fix must cover:**
     - Keep showing the last derived state, marked stale, while the next one is worked out. Show the spinner only before the first result for an account.
     - Hoist each `LazyListState` above the loading branch.
   - **Tests:** a production Shell, real repository and MockWebServer regression that scrolls Sessions, marks a row far down and asserts the row is still displayed with no spinner node in between. Also cover a minute advance.
   - **Fix the cause, not the test:** fix `CoursesRepositoryUiTest` by waiting on screen content after each refresh. Do not loosen it with retries.

3. **Breaks shows and acts on the previous range's verdict, and its tests race it (coordinator's 1/847 failure).** `BreaksScreen.kt:123` matches the derived state only on `data` and `zone`, not on `len`, `from` or `to`. `SimulationPanel` (`:161`) therefore renders the old `sim` next to the new pick, and `onSubmit` decides whether to ask for confirmation from the old `sim.level`.
   - **How it fails:** tap 12 Nov, then 13 Nov. The pills read Thu 12 – Fri 13, but until the background derivation lands, the panel still says "Safe · 1 day · skips 2 sessions" with a blue Save button. Tapping Save in that window saves 12–13, which fails because of the Friday exam, without the web's failing-break confirmation.
   - **This is a race, not a wall-clock dependency.** Breaks reads only the injected `now`; the fixture pins `breaksNow` to 2026-11-02 08:00 Paris, and no Breaks path calls `System.currentTimeMillis`/`LocalDate.now`.
   - **What the compare image shows:** `phone411x891_light_breaks_fail_1_compare.png` shows exactly the single-day verdict for 12 Nov: no "Can't miss" exam, Algorithmique −1 / 97.1%. The committed reference shows the correct 12–13 result: "Not possible · 2 days · skips 3 sessions", −2 / 94.3%. The "2" box is the same today cell, just less scrolled.
   - **Why the test catches it only sometimes:** `BreaksScreenshotTest.kt:44` and `BreaksUiTest.kt:45,68` wait only for the `break-verdict` tag, which already exists after the first tap. Whether the capture or assertion sees the updated verdict depends on scheduling.
   - The same build folder also holds `tablet840x1000_light_breaks_font1_compare.png`, a Shell capture taken while Breaks still showed its spinner. That is the same class of race.
   - **What the fix must cover:**
     - Show the verdict, and enable Save, only when the state's `len`, `from` and `to` equal the pick. Otherwise show the panel as pending.
     - The tests must wait for the state matching the pick, such as the expected "2 days · skips 3 sessions" or a tag carrying the range, before asserting or capturing.
     - Keep the fixed clock in every Breaks test. Audit the other Phase 4 screenshot tests for waits on a tag that is present before the awaited state.
     - Do not re-record: the committed `breaks_fail` PNGs are the correct output. They must verify unchanged.
     - Add a unit or Compose test that Save on a just-changed failing range still asks for confirmation.

4. **Act on the saved Settings requests from the palette.** `Palette.kt:28–29` sends "Add calendar" and "Upload .ics file" to `settings` with `OpenForm("calendar")` and `OpenForm("calendar-upload")`. The Settings route (`Shell.kt:257`) ignores `paletteAction`, so the request is never consumed or cleared. Choosing "Upload .ics file" just lands on the top of Settings.
   - The Phase 3 acceptance made consuming saved `OpenForm` requests a Phase 4 requirement, not a waiver.
   - **What the fix must cover:** "Upload .ics file" opens the system picker, as the web's palette does. "Add calendar" scrolls to the add box and focuses the link field. Clear the request once it has been consumed.
   - **Test:** the palette selection through the Shell route, plus restoration (the request must not replay).

## Items the log asked about

- **`CoursesRepositoryUiTest` flakiness:** real. It is a symptom of finding 2, not a test-only race.
- **Robolectric native graphics for bottom panels (4.12):** accepted. `@GraphicsMode(NATIVE)` is a supported Robolectric mode, not a weakened test. The explanation (legacy `Path.contains` breaks hit-testing on the top-rounded clip, with a replica check) is plausible, and the assertions are unchanged. Real touch feel stays Needs a device.
- **Settings dialog OOM and inline account deletion (4.11):** accepted. The inline field keeps the web's wording, requires an exact `DELETE` and reports the web's message otherwise. The cause is probably the same `AlertDialog` intrinsic-measure loop that 4.3 fixed with an explicit width (`AppNotIdleException` on the recording dialog). Phase 8 may move it back into a dialog using that pattern. Not blocking.
- **Cached `data.json` after sign-out or deletion:** see finding 1. It must be fixed now.
- **4.10 Assistant:** removed by the owner. Not a finding.

## Deferred, non-blocking

- **Homework delete is lost if the app closes during the undo window (Phase 11).** The 5-second Undo delete lives in `viewModelScope`. Closing the app inside those 5 s drops the delete, and the item reappears. Nothing is lost, but it should go through the durable outbox in Phase 11.
- **Calendar and Countdowns keep some choices only for the session (Phase 8).** The Calendar's view and layers, and the dismissed exam suggestions on Countdowns, are kept with `rememberSaveable` instead of being stored as on the web.
- **Countdowns' "Added …" message reads the wall clock (Phase 8).** `CountdownsScreen.kt:134` uses `System.currentTimeMillis()` instead of the injected `now`. It only affects the wording of the message. Phase 8 cleanup.
- **Breaks and Settings stack into one column on tablets (Phase 8).** The list-detail layouts are Phase 8 work.
- **The Calendar uses tap-then-tap instead of dragging from "To plan" (accepted).** This matches the web's touch behaviour (`calendar-ui.js:683` ignores touch pointers).

## Scope

- No server source, existing migration, `android/`, secret or plan edits in the diff.
- No Gradle or npm was run by the reviewer for this review (the coordinator's run was in progress). Evidence for finding 3 is the coordinator's failing run and its Roborazzi compare images.
- Once the four fixes are in, rerun `npm test` and `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug --rerun-tasks`, plus `tools/e2e.sh`, and log them under "Review 4 fixes".


## Re-review — round 1 (PASS)

Reviewed `git show` for each fix commit and the "Review 4 fixes" log entry. I ran no Gradle or npm; the coordinator is running the full check.

1. **Fixed: cached account data (`1b449fc`).**
   - Snapshots now live in `filesDir/snapshots/`. `DataRepository.start()` purges every `data-*.json` and `snapshot-*.tmp` that doesn't belong to the current token.
   - `start()` runs on every token change through `startData()`'s `tokenStore.token.collectLatest`, which covers sign-out, a 401 and a new sign-in. `deleteAccount()` also calls it directly.
   - Legacy `filesDir/data-*.json` files are deleted when the container builds the repository. A refresh that finishes after the token changed deletes the file it just wrote. `refreshLocked` calls `mkdirs()` before writing, so the new directory is safe on first run.
   - `snapshots` is excluded from full backup, cloud backup and device transfer.
   - The tests are real file-system assertions: sign-out leaves no file, one file per sign-in, Delete all my data rewrites the file on reload, stale-file cleanup keeps unrelated files, and `deleteAccount` goes through MockWebServer and checks the file is gone.
2. **Fixed: lists flashing to a spinner (`88a5472`).**
   - The five screens keep the last derived state, hoist their `LazyListState`, and no longer null their view model on a data change. Course still resets for a different id.
   - `SessionsNoFlashTest` uses the production Shell, the real repository and MockWebServer. It scrolls to row 30 of 40, marks it, and asserts the list and row stay displayed, including after a minute advance.
   - The row-displayed assertion fails on the old screens, because recreating the list reset its scroll. The log reports confirming this by stashing the fix.
   - `CoursesRepositoryUiTest` now waits for the screen's list, not the repository, with no retries and no weakened assertions.
3. **Fixed: Breaks verdict race (`58b4242`).**
   - The verdict, Save and the failing-break confirmation render only when the derived `len`, `from` and `to` equal the pick. Otherwise the panel shows "Working it out…".
   - The screenshot and UI tests wait for the result node carrying the exact range.
   - The new test changes to a failing range through the calendar and checks that Save asks for confirmation and saves nothing.
   - `git diff a2f366a..HEAD --stat -- '*.png'` is empty: no PNGs were re-recorded, so the committed `breaks_fail` references must verify unchanged.
   - The Sessions "Past" screenshot wait was moved to the row only Past shows. That is the right audit result for the new keep-last-state behaviour.
4. **Fixed: palette requests for Settings (`656c55f`).**
   - "Upload .ics file" opens `ACTION_OPEN_DOCUMENT`. "Add calendar" opens the add box, scrolls to it and focuses the link field. Each request is cleared when consumed.
   - `SettingsPaletteRequestTest` drives the real palette and Shell route. It asserts the intent, the focus and the cleared saved state, and that nothing replays after state restoration.

### Deferred, non-blocking (Phase 8)

- **No owner guard on the kept list.** The derived list is now kept with no check against the account, so account B could see account A's previous list for one derivation. The risk is low: a sign-in always goes through the signed-out state, and the purged cache means B starts from `data == null`, which clears the models. Phase 8 should still tie the kept list to the owning session.
- **Sessions bulk "shown" actions can briefly use the previous filter's list.** After a filter change, the list for the previous filter stays on screen until the new one is worked out, and bulk "shown" actions use it. Phase 8 should disable bulk actions while the filter is pending.
- **The Breaks range label is tautological.** The `break-result` state description comes from `pick`, not from the derived state. Given the new guard it is equivalent, but taking it from `s.from`/`s.to` would make the test's invariant independent of the guard.
- **The "Add calendar" scroll uses a hard-coded index.** It scrolls to `4 + calendars.size`, which breaks silently if Settings sections are reordered. Scrolling by key would be sturdier.
- The earlier deferred items still stand: Undo delete durability (Phase 11), session-only calendar view/layers and dismissed exams, the Countdowns wall-clock message, and tablet layouts (Phase 8).
