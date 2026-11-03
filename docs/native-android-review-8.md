# Phase 8 review

Verdict: **FIX**. Three findings. Finding 1 brings back a focus-timer behaviour this phase broke. Findings 2 and 3 are small layout defects that the owner's phone shows: the keyboard covering a field, and back in landscape. Each fix is a few lines. Everything else Phase 8 owed is done, or has a stated reason that I accept. The deferred list is at the end.

## Findings

1. **"Focus done" disappears within seconds when the alarm fires in a cold process and the break starts by itself (medium-low; a Phase 8 regression to Phase 5).**
   - **Where.**
     - `focus/FocusNotifications.kt` `showTimer` now calls `manager.cancel(ID_DONE)` every time a running timer is drawn.
     - `showTimer` is called from `FocusController.render`, which runs on every `restore()`, not only on user commands. The callers that matter are:
       - `AppContainer.startData()` (`scope.launch { focusController.restore() }`);
       - `MainActivity.afterNotificationChange` (each `onResume`);
       - the quick-focus permission callback;
       - `updateSettings` when the settings changed.
   - **Failure scenario.**
     - The user starts a 25-minute focus, with auto-breaks on (the default), and locks the phone. The system kills the process.
     - The alarm fires. `FocusReceiver` → `phaseEnded()` → `render` (nothing to cancel yet) → `phaseDone` posts "Focus done: 5-minute short break started." with the chime.
     - `change()` recorded a log, so `outbox.requestFlush()` enqueues `OutboxWorker`. Once the network is up, which is usually within seconds, the worker calls `app.startData()`. In this fresh process that is the first call, so it launches `focusController.restore()`. The break is running, so `render` → `showTimer` → `cancel(ID_DONE)`.
     - The heads-up and the lock-screen entry are gone before the user looks. Any other cold start during the break does the same: a reminder alarm (`NotifyReceiver` calls `startData`) or `RefreshWorker`.
     - In a warm process nothing re-renders, so this shows up only some of the time. That makes it look random on a real phone.
   - **The test** (`phaseDoneIsClearedWhenTheNextPhaseStartsFromAnywhere`) checks exactly the over-broad rule: any `showTimer` clears it.
   - **A fix must cover:**
     - Clear "phase done" only when the user acts on the timer: start, toggle, skip or stop, from the app, a notification button, the tile, the widget or the sheet. For example, add a `notifier.clearDone()` that those controller methods call, and drop the cancel from `showTimer`. Another way is to clear it only when the run or phase being drawn differs from the one the done notification was posted for.
     - `restore()`, `updateSettings` and `onResume` must leave it alone. Tapping the notification still removes it (`setAutoCancel`).
     - Tests: after `phaseEnded()` with auto-break, a `restore()` (and an `updateSettings` with changed settings) keeps `ID_DONE`. `start()`/`toggle()`/`skip()`/`stop()` clear it. The current test must change to the new rule.

2. **On Breaks, the break-name field and **Save break** sit under the keyboard (low).**
   - **Where.** The activity is edge-to-edge (`enableEdgeToEdge()`, targetSdk 36), so `adjustResize` no longer shrinks the content. Each screen with a text field has to apply `imePadding()` itself. Settings, Countdowns, Habits, Focus, the homework form, the calendar sheet and the quick-focus sheet do. `BreaksScreen`'s `LazyColumn` (`BreaksScreen.kt:138`) doesn't. Its `SimulationPanel` field (`break-label`, `:318`) is the second item, below the month card, so on a phone it sits in the lower half of the screen.
   - **Failure scenario.** The user picks days on a phone and taps "Name, e.g. Trip home". The keyboard opens over the field and the Save button, and the list can't scroll them into view because its viewport didn't shrink. The user types blind, then has to close the keyboard to save.
   - **Fix.** Add `.imePadding()` to the Breaks `LazyColumn`, as the other screens do.
   - **Also.** The progress log says edge-to-edge was "already in place". Recheck every screen that has a `TextField` (I found no other one outside a dialog).

3. **On wide screens, including a phone in landscape, back leaves Courses or Homework instead of closing the opened item. The selection also doesn't follow rotation (low).**
   - **Where.**
     - `wide` is `screenWidthDp >= 600` (`Shell.kt:203`), so any phone in landscape gets `TwoPane`.
     - The opened item is a local `sel` (`Shell.kt:340, 373, 385`). The only back handler pops the nav back stack (`:290`). Nothing sets `sel = null` on back.
     - The plan asked for `ListDetailPaneScaffold`, which handles this. Using a hand-made `TwoPane` instead is a deviation, and the log doesn't record it.
   - **Failure scenario.**
     - Landscape, Courses: the user taps a course, then presses back. The app goes to Today, or closes if Courses was the first screen, instead of going back to the list. The predictive-back preview shows the screen leaving. The Homework edit pane behaves the same way.
     - Sessions isn't affected, because its detail is still a dialog that handles back itself.
     - **Rotation.**
       - Portrait `homework/5`, rotate to landscape, Cancel (`sel = null`), rotate back to portrait: the cancelled form opens again, because the route is still `homework/5`.
       - Open an item in landscape and rotate to portrait: the open form closes and the list shows (the draft is kept).
   - **A fix must cover:**
     - `BackHandler(enabled = wide && sel != null) { sel = null }` in each of the three branches, placed after the shell's handler so it takes priority.
     - Make the route and `sel` agree. For example, closing the pane on a `…/{id}` route pops or replaces it with the list route. Going narrow with `sel` set navigates to the item route.
     - Log the `TwoPane` deviation and the reason for it: no extra adaptive library.
     - Test: wide Courses, open a course, `Espresso.pressBack()` (or the dispatcher), and the placeholder shows while the title is still "Courses".

## Earlier reviews' Phase 8 items

| From | Item | State |
|---|---|---|
| Review 1 | Instrumented `AndroidKeystoreCipher` test; rename the JVM fake | Done (`6f4265b`). Round trip, fresh IV, non-extractable key, tamper and foreign alias. The progress log reports 3/3 on the emulator. |
| Review 4 | Countdowns wall clock | Done (`at`). |
| Review 4 | Owner guard on kept lists | Done for Sessions, Course, Courses, Attendance and Today, by comparing `data.user.email`. |
| Review 4 | Sessions bulk actions while the filter is pending | Done (`pending = s.filter != filter`). `derive` keeps the filter as given, so it can't stay pending. Test with an injected dispatcher. |
| Review 4 | Calendar view and layers, dismissed exams | Done (`UiPrefs`, per email hash, excluded from backup and transfer). |
| Review 4 | Tablet list-detail | Done with findings 3 and the Sessions note below. Breaks and Settings stay one column (stated). |
| Review 4 | Delete-account dialog (optional) | Not done, stated. Accepted. |
| Review 5 | `POST_PROMOTED_NOTIFICATIONS` and `canPostPromotedNotifications()` | Done. Needs an Android 16 device. |
| Review 5 | Quick-focus permission result | Done (`FinishGate`). The sheet closes after the answer and redraws. |
| Review 5 | Stale "phase done" | Done too broadly: finding 1. |
| Review 5 | Swiped-away ongoing notification | Not done. The reason given (re-posting something the user dismissed) is reasonable. Accepted. |
| Review 5 | `OfflineShellTest` latch | Already latch-based (`CountDownLatch` dispatcher). Confirmed. |
| Review 6 | Widget tap awaits a redraw | Done (`TimerCommandCallback`). |
| Review 6 | Zone of *Do next* | Done (`rebuildWidgetPlan` on `TIMEZONE_CHANGED`/`TIME_SET`). The early `dataRepository.start()` before the token loads is harmless: `session` and `token()` are both null, so it neither purges nor publishes. |
| Review 6 | `updatePeriodMillis` | Left, waiting for a device check (as the review said). Accepted. |
| Review 6 | Overrun countdown | Not done, cosmetic. Accepted, but the log gives no reason; see deferred. |
| Review 7 | Engine `run(null)` and `NonCancellable` | Done. `onSnapshot` waits for `awaitToken()`. The cancellation test fails without `NonCancellable` (the worker reverted it once to check). |
| Review 7 | Check-ins from a stale cache | Done. A refresh with a 5 s budget runs when the cache is older than 10 min. `refresh` resets `loading` in `finally`, so a timeout leaves no spinner stuck. |
| Review 7 | `file` scheme | Removed from both VIEW filters. |
| Review 7 | Exact-alarm re-grant | Receiver registered. The comment is wrong; see deferred. |
| Review 7 | Share lost on recreation | Done (`PendingRouteViewModel.receive` in `viewModelScope`). |
| Review 7 | Second ask for fresh installs | Done: a one-line notice on Today with **Allow**. |
| Review 7 | Container-wiring test | Covered by testing the `onSnapshot` decision, not a receiver test. That is the stated reason, and I accept it as in review 7's round 1. |

## Answers on the plan's Phase 8 list

- **Dynamic colour: not used, correctly.** Section 6 says no wallpaper colour in the app, and the widget keeps it. The worker followed section 6 and logged it (rule 3).
- **Dark theme: real.**
  - 260 of the 548 screenshots are dark, on both phone and tablet.
  - I looked at dark Homework at 200% and the tablet shots. Colours come from the theme tokens, and the text is readable.
- **200% font: real but partial.**
  - There are 60 `font2` shots, for Sign-in, Today, Attendance, Homework, Focus, Upcoming, More, Scope, Search and the shell.
  - I viewed dark Homework at 200% on the phone. It is truly scaled, wraps, and the bottom bar drops its labels.
  - Sessions, Courses, Calendar, Breaks, Habits, Countdowns and Settings have no 200% shot (deferred).
- **TalkBack labels: real, with limits.**
  - `TalkBackLabelsTest` fails on any composed `Role.Button` node with no text or description, on 12 screens, phone and tablet.
  - I grepped every `IconButton` and clickable `Icon` in `main`, and all have descriptions. The new exam-dismiss icon has a role and a click label.
  - Limits: the test sees only what is composed on the first frame (not items further down a lazy list, sheets or dialogs). It also misses clickables without a role.
- **Predictive back: fine.**
  - `enableOnBackInvokedCallback="true"` with activity 1.9.3. Compose `BackHandler`, Compose dialogs and the calendar sheet all go through the dispatcher. There is no `onBackPressed` override or `KEYCODE_BACK` handling anywhere.
  - On Android 16 (targetSdk 36) this was already the default.
- **Edge-to-edge: in place, apart from finding 2.**
  - Scaffold insets cover the bars on phones, and the rail layout on wide screens.
  - The search palette and the other dialogs manage their own windows.
- **Notification ask: never at launch.**
  - The places that ask are the first timer start, the first visit to Settings → Notifications, the Classes or Habits switches, and **Allow** on Settings or Today.
- **Baseline profile: not measured. Accepted.** Measure cold start on the owner's phone in Phase 9.

## Tablet screenshot re-record (`281a92f`)

- **What changed.**
  - Images only, 76 files, all `tablet840x1000_*`. No phone PNG changed anywhere in the phase, and no test code is in that commit.
  - The only test-code change elsewhere is `CourseScreenshotTest` (`b32c4e3`). It now looks for buttons inside `course-detail`, because the list beside it has buttons with the same text. That is a scoping fix, not a weaker test.
- **Before and after, checked:**
  - `light_course_main_1`: the full-width course became Courses list | course. The course content is identical.
  - `light_homework_edit_1`: the edit form is beside the Homework list.
  - `light_sessions_main_1`: the Sessions list is beside "Select a session." This is the intended layout.
- **Sessions detail.** `light_sessions_sheet_1` now captures only the dialog window: the session detail is still an `AlertDialog`, centred over both panes. So on tablets the Sessions right pane never holds anything but the placeholder (deferred, non-blocking).

## Deferred, non-blocking

- **Phase 9.**
  - **The exact-alarm receiver comment is backwards.** `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` is sent when the permission is *granted*. On revoke, Android 12 stops the app and drops its exact alarms, and nothing re-arms them until the app runs again. Correct the manifest comment and the log, and keep the receiver: on a grant it re-arms as exact. `FocusReceiver` also gets the action but only redraws the widget. It should `restore()` so the timer alarm becomes exact again. This only affects Android 12, since 13+ holds `USE_EXACT_ALARM`.
  - **Measure startup** for the baseline-profile decision.
  - **Device checklist additions:**
    - "Focus done" after a cold-process phase end (finding 1);
    - Breaks name with the keyboard open (finding 2);
    - back and rotation in landscape (finding 3).
  - The earlier Phase 9 items stand: `app = 1` push subscriptions and the `USE_EXACT_ALARM` Play declaration.
- **Phase 8 follow-ups, any later phase.**
  - **Sessions on tablets.** Either render `SessionDetail`'s content inline in the right pane when wide, or drop the pane for Sessions. Today the pane shows "Select a session." behind a dialog.
  - **Today's "Notifications are blocked" notice** ignores the reminder switches and can't be dismissed. Show it only when a reminder switch is on, as the Settings row does.
  - **200% screenshots** for Sessions, Courses, Calendar (week grid), Breaks, Habits, Countdowns and Settings.
  - **TalkBack test.** Also check clickables without a role, and scroll lazy lists to the end.
  - **The overrun countdown (review 6).** Log the reason it stays.
  - **A small leak.** `receive { readIncoming(shared, resolver) }` holds the activity's `ContentResolver`, and so the old activity, until a slow read finishes. Pass `applicationContext.contentResolver` instead.
- **Noted, no action.**
  - In a cold process `lastSynced` is null, so almost every reminder alarm now refreshes `/api/data` first. That is about 10–20 extra loads per user per day, well within the D1 free-plan read budget.

## Checked and correct

- **`ReminderEngine.onSnapshot`.** A null snapshot cancels only after the token store has loaded and says signed out. A late snapshot plans normally.
- **The stale refresh in `replan`.** It runs after `awaitToken()` and `start()`, has a 5 s budget, and leaves the rest of the receiver's ~10 s for planning. A concurrent collector run is serialised by the engine mutex, and the sent keys prevent a double post.
- **`QuickFocusActivity`.** The ask runs before the command, and `gate.asked()` is set before any answer can arrive, since both are on the main thread. The sheet closes after the answer.
- **The widget plan writer.** It is shared between the collector and the zone rebuild. A null snapshot while signed in writes nothing.
- **Remembered choices.** The stored view is applied only while the session has none of its own (`view` defaults to `""`). Nothing is saved until the load is done, so the default can't overwrite a stored view first.
- **Live Update.** It is gated on API 36 and `canPostPromotedNotifications()`, with the plain ongoing notification as the fallback.
- **Scope of the diff.** No server, website, migration, secret or `android/` change.

## Evidence

- **Read by the reviewer:**
  - AGENTS.md;
  - the plan's sections A, B, 0, Phase 8, 6 and 8;
  - the Phase 8 progress entry;
  - every "Phase 8" item in reviews 1 and 4–7;
  - the full Phase 8 main-source diff and the new tests (`FocusNotificationsTest`, `ReminderEngineGuardsTest`, `TwoPaneTest`, `TalkBackLabelsTest`, `AndroidKeystoreCipherTest`, `CourseScreenshotTest`);
  - from the existing code: `FocusController` (render, restore, phaseEnded), `FocusReceiver`, `OutboxWorker`, `App.startData`, `DataRepository.start` and `refresh`, `WidgetPlanWriter`, `SessionDetail`, `BreaksScreen`, `SignInScreen` and the `imePadding` sites;
  - before and after PNGs for the tablet course main, homework edit, sessions main and sessions sheet shots, plus phone dark Homework at 200%.
- **Not run by the reviewer:** Gradle and npm, at the owner's request. The worker reports:
  - `npm test` 104/104;
  - 1062 unit tests with 0 failures, lint, and Roborazzi verified with `git status` clean;
  - `tools/e2e.sh` 5/5;
  - one `TalkBackLabelsTest` hang that has not come back.
