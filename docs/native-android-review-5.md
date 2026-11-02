# Phase 5 review

Verdict: **FIX**. Two blocking findings. Reviewed `git diff native/phase-4...native/phase-5` without PNGs and without `824f41d`, which only changes `tools/run-phase.sh` and is outside this review. The controller, alarm, receiver, notifications, tile, sheet, outbox and migration are close to the plan. The controller tests are meaningful: they use a fake clock and alarm, and they cover the stale-finish and early-alarm guards. Both blocking problems are at the boundaries: which account a recorded session belongs to, and when the TWA migration runs.

## Findings

1. **Unsent sessions, offline tasks and the running timer are not tied to an account (medium, data integrity).**
   - **Where.** `android-native/app/src/main/java/com/knhdixh/attendance80/focus/FocusStore.kt:39–63` stores the outbox and pending tasks with no owner. `focus/Outbox.kt:44–61` sends every entry with whatever token is current. `focus/Outbox.kt:89–103` merges pending tasks into the current account's `PUT /api/focus/settings`. `auth/AuthRepository.kt:98` clears only the token, and the timer itself (`FocusController`) survives sign-out with its `target`.
   - **Failure scenario.**
     1. Signed in as A, finish a focus on A's homework while offline, or while A's token has expired (401 keeps the entry, `Outbox.kt:53`).
     2. Sign out and sign in as B.
     3. The worker posts A's session to B. The server accepts it, because `src/focus.js:39–42` drops a foreign `homework_id` but keeps the label.
     4. A's offline tasks are written into B's task list.
     5. A timer started under A is still running for B, and when it finishes it is also logged to B.

     The same happens to sessions recorded while signed out: the tile and notification still work then. The worker reports this itself as open question 1. Phase 3 made account boundaries a review requirement (review 3, round 1, finding 4).
   - **A fix must cover:**
     - Stamp each outbox entry and pending task with the account it was created under. Use a stable account identity, not the token hash: a re-sign-in to the same account rotates the token and must not orphan entries. `AppData.user.email` is available.
     - Send an entry, or merge a task, only while that same account is signed in.
     - Decide and implement what sign-out does with a running timer and with queued items, for example: flush first, then stop or clear the timer target, and keep or purge the queue.
     - Keep the session JSON on the wire unchanged. The owner is a storage field, not part of the POST body.
     - Tests: an A→B switch with queued sessions and tasks (MockWebServer asserts nothing of A's reaches B), a re-sign-in to A that still sends A's entries, and a timer running across sign-out.

2. **The TWA migration only runs from UI and worker start-up, so the receiver and the tile act on the store before it (medium, plan task 7).**
   - **Where.** `LegacyFocusMigration` is only called from `DefaultAppContainer.startData()` (`AppContainer.kt:90–93`), which only `MainActivity`, `QuickFocusActivity` and the two workers call. `FocusReceiver.kt:27–28` (`MY_PACKAGE_REPLACED`, `BOOT_COMPLETED`, time changes) and `FocusTileService.onClick` (`FocusTileService.kt:35–38`) use the controller directly.
   - **Failure scenario.** A Play update replaces the TWA while its timer is running.
     - `MY_PACKAGE_REPLACED` reaches the new receiver, and `restore()` finds the new, empty store. `render()` then cancels notification 41, which is the TWA's ongoing countdown (same ID, `android/.../FocusNotifications.java:22`). No alarm is set. The phase-end chime, the auto-break and the lock-screen countdown are all lost until the user happens to open the app.
     - If the user taps the tile before opening the app, `toggle()` starts a fresh timer. The later migration's `adopt()` (`FocusController.kt:187`) then refuses the TWA timer because one is running. That focus phase is never recorded.
   - **A fix must cover:**
     - Run the migration once before the controller's first load in any entry point, for example inside the controller's `ensureLoaded` or a shared guarded `ensureMigrated()` that `restore()`, the receiver and the tile all pass through. It must not run twice concurrently.
     - Tests: the receiver's `MY_PACKAGE_REPLACED`/`restore()` path with a running legacy timer (alarm armed, notification shown), and a tile toggle in a cold process before any activity (the legacy timer is adopted, or finished and logged, before the toggle is applied).

## OfflineShellTest.cachedShellStaysVisibleWhileRefreshIsDelayed

This is a test bug, not a product race. `DataRepository.start()` reads the cache synchronously under its mutex (`DataRepository.kt:68–80`) before `refresh()` is called, so the cached shell is always published before the delayed request. The test fails because of its own fixed wall-clock budgets:

- `server.takeRequest(2, SECONDS)` at `OfflineShellTest.kt:47` returns null when the `Dispatchers.IO` coroutine has not sent the request within 2 s under parallel Robolectric load.
- The 2 s `setBodyDelay` can expire before `assertFalse(pending.isCompleted)` or the first composition. In that case the test passes without proving that anything was delayed.

Fix it by holding the response on a latch-based `Dispatcher` (or `SocketPolicy` plus an explicit release), using a generous `takeRequest` timeout, asserting the cached text while the response is still held, then releasing it and awaiting `pending`. The test dates from Phase 3 (`ebf369e`). This is deferred and non-blocking: fix it in the next phase that touches the shell tests, at the latest in Phase 8.

## Deferred, non-blocking

- **Phase 8, Live Update.** `LiveUpdate.kt` asks for promotion with the extras key, but the manifest does not declare `android.permission.POST_PROMOTED_NOTIFICATIONS`. That permission is a documented Live Updates requirement, and declaring it is harmless when compileSdk lacks the constant. The code also never checks `NotificationManager.canPostPromotedNotifications()`, which is present in the API 36 stubs. Without the permission, promotion probably never happens, and the silent fallback hides it. Add both, and verify on an Android 16 device.
- **Phase 8, permission dialog from the quick-focus sheet.** In `QuickFocusActivity.kt:52–54`, Start asks for the permission and the same tap's `after()` calls `finish()`. The permission result and its `restore()` are then lost. Even if the user grants it, the first timer started from the sheet shows no countdown until its next state change, and the "asked" flag prevents a second ask. Keep the sheet open until the result arrives, or re-render from the controller when the permission becomes granted.
- **Phase 8, stale notifications.** The "phase done" notification (ID 42) stays after the user starts the next phase from the app or the tile. On Android 14+ the ongoing countdown can be swiped away and is not reposted until the next change (there is no `deleteIntent`). This is cosmetic, because the alarm still fires.
- **Phase 11, crash safety of a finish.** `FocusController.change()` (`FocusController.kt:193–198`) saves the next timer and the log in two separate DataStore edits, timer first. If the process dies between them, the finished session is lost. Writing the log first, or both in one `edit`, makes a re-finish idempotent by `client_id`. This is cheap to fold into finding 1, since that touches the same store.
- **Phase 11, a log added late in a worker run waits.** `WorkOutboxScheduler` uses `ExistingWorkPolicy.KEEP` (`Outbox.kt:85`). A log added while a worker is past its flush loop (in `syncPendingTasks`) is not re-enqueued. It waits for the next app start or the hourly `RefreshWorker`. `APPEND_OR_REPLACE` avoids this.
- **Phase 11, queued sessions are not shown.** The web keeps queued sessions visible in the Focus stats (`public/focus-ui.js:112–114`, `adopt`). The app shows a session only after it is sent, so offline focus time is missing from Today and the goal until then.
- **Phase 6, palette commands use `toggle()`.** The palette's start, pause and resume commands go through `toggle()` and are decided on UI state (`FocusScreen.kt` palette `LaunchedEffect`). That state starts as the default idle `FocusTimer()` before `load()`, and it can lag the controller. "Start" could then pause a running timer, or "Pause" could be dropped. The controller already has `start`, `pause` and `resume`; expose them through `TimerActions`, as the widget's Start/Pause will need too.
- **Phase 9, legacy `settings`.** The legacy `settings` key, which the plan names, is not read. A TWA timer that ended during the update therefore finishes with default settings (notify, sound) before the first snapshot arrives. Minor.

## Evidence

- The reviewer read: the plan's sections A, B, 0 and 8 and the Phase 5 section; the Phase 5 progress entry, including its Unverified list and open questions; all Phase 5 main sources; the test names and bodies of the controller, outbox and migration tests; the web `public/focus.js` `entry`/`finish` and `public/focus-ui.js` record/flush/adopt; the server `src/focus.js` `logFocus` (`client_id` dedup with `ON CONFLICT DO NOTHING`, 400/429 cases, a foreign homework dropped but the session kept); and the old TWA `FocusStore.java`/`FocusTimer.java` keys and fields. The migration's keys (`timer`, `logs`, `pendingTasks`) and timer fields match the TWA's writer. The two apps share the `applicationId`.
- Checked and correct:
  - **Alarm.** One exact alarm (`setExactAndAllowWhileIdle`, RTC_WAKEUP at `endsAt`) with an inexact fallback. `USE_EXACT_ALARM` on API 33+ and `SCHEDULE_EXACT_ALARM` up to 32.
  - **Receiver broadcasts.** Boot, package replaced, `TIME_SET` and `TIMEZONE_CHANGED` all reach the non-exported receiver.
  - **No foreground service.** None is used, so no foreground-service type is needed on Android 14+.
  - **Chronometer.** It counts down to `endsAt` and is hidden while paused.
  - **Channels.** The chime and silent channels honour `sound` and `notify`.
  - **Double finishes.** Mutex-guarded `phaseEnded` with `isDue`, plus the stale guard, prevent double finishes. `client_id = run` makes outbox retries idempotent on the server.
  - **Outbox errors.** A 400 drops the entry; 401, 429, 5xx and offline keep it in order.
- **Not run by the reviewer:** Gradle and npm, at the owner's request (a parallel run was in progress). This relies on the worker's logged run: `npm test` 104/104, and the combined Gradle command with 847 unit tests, 0 failures, and e2e 2/2. Everything in the worker's Unverified list still needs a device.
- No server, website, migration, secret or `android/` changes in the Phase 5 diff.
