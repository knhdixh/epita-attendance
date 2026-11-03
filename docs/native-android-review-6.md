# Phase 6 review

Verdict: **FIX**. Reviewed `native/phase-6` at `95c79b3` (`677eaf2`, `d3367d3` and the progress log; `378311c` is tooling and was not reviewed). There are six blocking findings. Findings 1 and 3 together mean that a placed widget often shows the wrong timer state, or "Open the app to sign in", for up to 30 minutes. Finding 2 means that every existing TWA user loses their placed widget on the update.

## Findings

1. **The widget does not redraw while its Glance session is alive, so a second change within about 45 s never reaches the screen (high).**
   - **Where.** `widget/FocusGlanceWidget.kt:108–112`. `provideGlance` reads the store and `now` once, outside `provideContent`, and the content only gets those captured values.
   - **What Glance does.** In Glance 1.1.1, `GlanceAppWidget.update()`, and so `updateAll()`, checks `isSessionRunning`. If a session is running, it only calls `AppWidgetSession.updateGlance()`. That sends `UpdateGlanceState`, which reloads the widget's `GlanceStateDefinition` state and does not run `provideGlance` again. This was checked in the 1.1.1 bytecode (`GlanceAppWidget$update$4`, `AppWidgetSession.processEvent`). A session stays alive at least 45 s after it starts (`TimeoutOptions` initial timeout of 45 s). This widget keeps no Glance state, so an update during that window recomposes the same captured `snapshot` and `now`.
   - **Failure scenarios.**
     1. Tap **Start** on the widget. The redraw starts a new session, and the widget correctly shows Pause and the countdown. Tap **Pause** 10 s later. `toggle()` pauses the timer, and `render()` → `redrawWidget()` reaches the live session, so the widget still shows Pause and a ticking countdown. A paused timer arms no alarm and triggers no further render, so the widget stays wrong until the 30-minute `updatePeriodMillis` or some other event. If the user taps again because "it didn't pause", `toggle()` resumes the timer.
     2. Open the app, then start a timer from the Focus screen within 45 s. The snapshot collector's redraw started the session, so the timer redraw is swallowed and the widget says Start while a focus runs.
     3. Every cold start: see finding 3. The first, wrong write starts a session, and the correct write a few ms later is swallowed.
   - **A fix must cover:**
     - The content must read the store **inside** composition, so an update to a live session shows new data. For example, `WidgetStore` exposes a `StateFlow`/`Flow` that the content collects. Another option is a `GlanceStateDefinition` written with `updateAppWidgetState` before `update`. `now` must also be taken at composition time (the chronometer base and "Now"/"Next class" depend on it), and the refresh alarm must be rescheduled from what is actually drawn.
     - `redrawWidget` is fire-and-forget on a fresh scope (`FocusGlanceWidget.kt:201–203`). From `FocusReceiver` (`ACTION_WIDGET_REFRESH`, inside `goAsync`), make it suspend and await it before `pending.finish()`, so a cached process isn't frozen before the update is enqueued.
     - Test: drive the real widget, or at least the store → content path, through two changes in quick succession (start, then pause) and assert that the second state is what is drawn. The current `WidgetRenderTest` composes `WidgetContent` directly with a fixed snapshot, so it cannot see this. Add "start then pause within a few seconds from the widget" to the device checklist.

2. **The old TWA widget is removed on the update, because the provider's component name changed (medium, plan Phase 6 "replaces `FocusWidget.java`", Phase 9 task 3).**
   - **Where.** The new manifest receiver is `.widget.FocusWidgetReceiver` (`AndroidManifest.xml`, the new `<receiver>`). The TWA's provider is `com.knhdixh.attendance80.FocusWidget` (`android/app/src/main/AndroidManifest.xml:212`). Both apps have the same `applicationId`, and the namespace is `com.knhdixh.attendance80` in both builds.
   - **Failure scenario.** The user has the TWA widget on the home screen and installs the native update over it. When the package changes, `AppWidgetService` drops providers whose component no longer exists, and every widget bound to them goes with them. The user's widget disappears, or shows "Can't load widget", and has to be found and placed again. The progress log lists this as Unverified ("it is not removed or migrated"). It is predictable without a device and cheap to avoid now.
   - **A fix must cover:**
     - Make the Glance receiver class exactly `com.knhdixh.attendance80.FocusWidget` (a `GlanceAppWidgetReceiver`), declared as `.FocusWidget` with the `APPWIDGET_UPDATE` filter and `@xml/focus_widget_info`. Receivers can't be aliased, so the class name itself must match.
     - Make sure the first update after the replacement draws from the native store. `MY_PACKAGE_REPLACED` already calls `restore()` → `render()` → redraw, and `APPWIDGET_UPDATE` follows the update. Glance has no session state for a widget ID it did not create, which is fine.
     - Test: a Robolectric check that `ComponentName("com.knhdixh.attendance80", "com.knhdixh.attendance80.FocusWidget")` resolves as a receiver with `android.appwidget.provider` metadata, so a later rename fails the build. Keep "update over a TWA with the widget placed" in the device checklist for Phase 9.

3. **The plan collector treats "the token is not loaded yet" as signed out, so the widget flips to "Open the app to sign in" on every cold start (medium; the account-boundary path is untested).**
   - **Where.** `AppContainer.kt:105–113`: `if (data == null && tokenStore.currentToken() != null) return@collectLatest`. `currentToken()` is `token.value` (`auth/TokenStore.kt:34`). Until `EncryptedTokenStore`'s `initJob` has read and decrypted the token (`TokenStore.kt:109`), that value is `null` for a signed-in user. The collector starts in `startData()` before anything waits for the token.
   - **Failure scenario.** On every cold start (opening the app, the hourly `RefreshWorker` in a new process, the outbox worker):
     1. The snapshot is `null` and the token is not loaded yet, so `buildWidgetPlan(null)` writes `signedIn = false` with no classes or to-dos and redraws.
     2. Then the cache loads and the correct plan is written. The user sees the home-screen widget flash "Open the app to sign in".
     3. With finding 1 the second redraw is swallowed, so the signed-out text stays for up to 30 minutes, about once an hour.
     4. If the process dies between the two writes (a worker process), the widget stays signed out.
   - **A fix must cover:**
     - Decide sign-out only once the token store is loaded: `awaitToken()` or `isLoaded` before acting on a `null` snapshot.
     - Keep the current correct behaviour on sign-out. A→null clears classes and to-dos at once, and A→B never shows A's items, including while B's first refresh is pending or fails offline.
     - Tests: no test covers this collector, and review 3 made account boundaries a review requirement. Move the decision into something testable, for example a small `WidgetPlanWriter(tokenStore, store, clock)` that the container calls. Then test with a fake token store that loads late: a cold start with a saved token and a cache never writes `signedIn = false`; sign-out clears the store; and a switch from A to B leaves none of A's class or to-do titles in `widget.json`.

4. **The plan part keeps only 8 classes and is rebuilt only when the snapshot changes, so the widget says "No more classes this week" while classes remain (low, a regression from the TWA widget).**
   - **Where.** `WidgetSnapshot.kt:49, 66` (`MAX_CLASSES = 8`). The plan part is written only from the snapshot collector. The widget's own redraw alarm (`nextWidgetRefresh`) moves through the stored list, but it never rebuilds it.
   - **Failure scenario.** EPITA days often have 4 to 6 sessions, so 8 classes cover about a day and a half. If the snapshot doesn't change for that long, the stored list runs out after the 8th class ends and the widget says "No more classes this week" with classes the next morning. The snapshot doesn't change when the user is offline, or when the process stays warm and refreshes return equal data (`StateFlow` drops equal values). The TWA sent every tracked class in the next 8 days, up to 24 (`public/focus-ui.js:561–562`).
   - **A fix must cover:** store at least the TWA's window (classes ending after now and starting within 8 days, up to 24), or rebuild the plan part from the cached snapshot on the refresh alarm. Add a test that the list covers the next days' classes beyond 8 sessions.

5. **The Phase 6 item from review 5 was not done: the palette, and now the widget, use `toggle()` where the intent is known (low–medium).**
   - **Where.**
     - `ui/focus/FocusScreen.kt:91–93` still maps palette "start", "pause" and "resume" to `actions.toggle()`, decided on UI state that can be the default `FocusTimer()` before `load()`.
     - `FocusGlanceWidget.kt:123` makes the widget button `toggle()` too.
     - Review 5 assigned this to Phase 6 ("as the widget's Start/Pause will need too"), and the progress log doesn't mention it.
   - **Failure scenario.**
     - Widget: the button shows the stored state, and `toggle()` acts on the controller's state. Whenever the two differ (finding 1, or a phase that ended since the last draw), a tap on "Pause" can resume the timer, and a tap on "Start" can pause one that is running.
     - Palette: "Start timer" can pause a running timer when the UI state lags.
   - **A fix must cover:**
     - Expose `start`, `pause` and `resume` through `TimerActions` and the controller-backed implementations.
     - Make the palette call them.
     - Make the widget pass the intended action in `ActionParameters`, decided from what it drew. That action is idempotent: Pause on a paused timer does nothing.
     - Keep the due-phase finishing that `toggle()` does before acting.
     - Tests for each command against a controller in the opposite state.

6. **The full unit suite is not reliable: `ShellUiTest.today` waits for the wrong node (test bug, pre-existing, blocking because it's a one-line fix).**
   - **Cause.** `ShellUiTest.kt:51–52` swaps in the populated data, then waits for "Do next". `TodayScreen.kt:78` draws the "Do next" heading in the empty state too, so the wait passes at once. `TodayViewModel.update` derives the new state asynchronously, and since `88a5472` (Phase 4) the last derived state stays on screen while it does. Under load, `performScrollToNode(hasText("Mathématiques"))` therefore searches the old, empty-data list. That matches the owner's failure: 15 children and `maxValue = 0` (the old list fits on screen, so nothing was scrolled).
   - **Not a product race, and Phase 6 did not cause it.** The diff touches nothing under `ui/` or the Today and Shell tests. The screen shows the previous list and then the new one, which is the intended behaviour of `88a5472`.
   - **A fix must cover:**
     - After swapping the data, wait for something only the new derivation produces before scrolling. For example, wait until "Nothing due in the next 7 days." has gone, or wait for a populated-only node in the unmerged tree. Then scroll and assert as now, as review 4 did for Breaks. Do not loosen the assertion.
     - Because the owner needs the whole suite reliable before merging, also fix `OfflineShellTest.cachedShellStaysVisibleWhileRefreshIsDelayed` in this round. Review 5 describes the latch-based fix; it was deferred to "the next phase that touches the shell tests, at the latest Phase 8". Both failed together in the same loaded run.

## Deferred, non-blocking

- **Phase 8, `updatePeriodMillis="1800000"`.** This wakes the device every 30 minutes, as the TWA did. Once finding 1 is fixed, the event-driven redraws and the inexact boundary alarm cover every change, so the period can go (or be raised) to save battery. Check on a device first.
- **Phase 8, an overrun countdown.** If the phase-end alarm is late (inexact fallback, Doze), the chronometer counts below zero until the receiver runs. Cosmetic.
- **Phase 8, the time zone of *Do next*.** The to-do list is built with the zone at snapshot time and isn't rebuilt on `TIMEZONE_CHANGED`. Only its order near midnight can be off.

## Checked and correct

- **Backup.** `widget.json` lives in `filesDir/snapshots/`, which both `backup_rules.xml` and `data_extraction_rules.xml` exclude (cloud and device transfer). `DataRepository.purgeCaches` deletes only `data-*.json` and `snapshot-*.tmp`, so it never removes the widget file.
- **Sign-out (once the token has loaded).** The collector writes an empty, signed-out plan. `signedOut()` stops the timer and clears its target, and its `render()` rewrites the timer part, so no homework label survives.
- **Taps.** `a80://session/{id}`, `a80://homework/{id}`, `a80://focus` and `a80://today` match the manifest filter and `internalRoute`. `MainActivity` is `singleTask` and routes in `onNewIntent`. *Do next* ids are homework ids (`buildPlan.todo`).
- **The redraw alarm.** One non-wakeup `RTC` alarm with a fixed request code is replaced on each draw, at the next class boundary, due time or local midnight. It stops by itself once no widget is placed. That is cheap on battery.
- **The widget makes no network calls.** Its data is plain, and the store writes atomically with a single per-process lock.
- **Plan rules.** `buildWidgetPlan` follows `buildPlan`'s rules (tracked courses, no excused or future-absent sessions, no all-day sessions), and the tests cover them with fixture data.
- **The timer wiring.** `WidgetTimerWiringTest` really drives the controller through start, pause, resume and stop into the store.
- **Dynamic colour.** `GlanceTheme` gives dynamic colour on Android 12+.

## Evidence

- **Read by the reviewer:**
  - the plan's sections A, B, 0 and 8, and Phase 6;
  - the Phase 6 progress entry;
  - the full Phase 6 diff (main sources, manifest, resources, all four tests);
  - `AppContainer.startData`, `DataRepository.start`/`purgeCaches`, `EncryptedTokenStore`, `FocusReceiver`, `FocusController.toggle`/`restore`/`signedOut`, `RefreshWorker`, `InternalLink`, `TodayScreen` and `ShellUiTest`;
  - the TWA's manifest, `FocusWidget.java` and `focus_widget_info.xml`, and `public/focus-ui.js` `sendPlan`;
  - Glance 1.1.1 bytecode (`GlanceAppWidget` update path, `AppWidgetSession`, `TimeoutOptions`).
- **Not run by the reviewer:** Gradle and npm, at the owner's request. The owner's run: `npm test` 104/104. Gradle had 891 tests with 2 failures under load average ~15 (`ShellUiTest.today` and the known `OfflineShellTest` one); both pass alone. The worker saw the same.
- No server, website, migration, secret or `android/` changes in the diff.
