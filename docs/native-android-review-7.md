# Phase 7 review

Verdict: **FIX**. Four findings, all blocking. Three affect notifications on a real phone. They are: buttons that do nothing in a cold process, reminders that never post on a fresh Android 13+ install, and class reminders lost to a late inexact alarm. The fourth is a main-thread read of shared files. The planner, the account boundaries, the action queue and the parity with `src/notify.js` are sound (see "Checked and correct").

## Findings

1. **In a cold process, "Focus on this" does nothing and Absent never offers the catch-up, but the notification is still dismissed (medium).**
   - **Where.**
     - `notify/NotifyReceiver.kt:26–30`: `ACTION_DO` calls `reminderActions.handle` straight after `app.startData()`. Only the `replan` path (`:37–41`) waits for the token and calls `dataRepository.start()`.
     - `startData()` loads the cache asynchronously, through the token collector after `awaitToken()`. So in a fresh process `dataRepository.snapshot.value` is still `null` when `handle` runs.
     - `ReminderActions.kt:17` cancels the notification first.
     - `"homework-focus"` then reaches `AppContainer.kt:102`, which looks the homework up in `snapshot.value` and finds nothing.
     - `offerCatchup` (`ReminderActions.kt:36–37`) compares `null`'s email with the owner and returns.
   - **Failure scenario.** The homework reminder is posted by the alarm at 16:30, and the system kills the cached process. At 16:45 the user taps **Focus on this**. The notification disappears, no timer starts, and nothing says why. The same happens to the "Marked absent: … / Add catch-up" follow-up after **Absent** on a check-in posted from a cold process. Cold processes are the usual case for a tap minutes or hours after an alarm. Even in a warm process, the focus settings (custom lengths) reach `FocusController` only through the snapshot collector, so a just-loaded process can start a 25-minute default.
   - **A fix must cover:**
     - Before `handle`, `ACTION_DO` does what `replan` does: `awaitToken()`, return if signed out, then `dataRepository.start()`, which publishes the cached snapshot synchronously under its mutex.
     - Alternatively, give `ReminderActions` a suspending "loaded snapshot" provider instead of `() -> AppData?`.
     - The focus path applies the loaded snapshot's focus settings and account to the controller (`updateSettings`, `setAccount`) before `focusOn`, so it doesn't depend on the collector having run.
     - Keep the owner check in `offerCatchup`.
     - Tests: `HomeworkReminderTest.doneIsQueuedAndFocusOnThisStartsTheTimer` and `CheckInTest.absentOffersACatchup…` pass a snapshot that is already there, so neither can see this. Add a test where the snapshot is `null` until a fake load completes, for example a token store or repository that loads late. Assert that the focus target is started with the account's focus length and that the catch-up offer is posted. Swapping the fix back to `snapshot.value` must make it fail.

2. **On a fresh Android 13+ install the app never asks for `POST_NOTIFICATIONS` for reminders, so check-ins, class, habit, homework and exam reminders silently never appear (medium).**
   - **Where.**
     - The only prompt is `askNotificationsOnce` (`MainActivity.kt:75–77`, `focus/NotificationPermission.kt:14`). It runs at most once per install (`FocusStore.notificationsAsked`), and is triggered by the first timer start or by `SettingsScreen.kt:163`.
     - `:163` fires only when a **device** switch is changed. All three device switches are on by default, and so are the account's `classes` and `habits` (`DEFAULT_NOTIFY` in `src/notify.js:12`), so there is no "turn on" moment. The account switches (`:302` and the habits toggle) never ask at all.
     - `ReminderEngine.run` correctly posts nothing while `canPost()` is false, but nothing in the UI shows that state.
   - **Failure scenario.**
     - A student installs the app on a Pixel with Android 14, signs in and adds a timetable. They never start a focus timer. No check-in, class reminder or exam warning ever arrives, and Settings → Notifications shows every switch on.
     - A user who declined the prompt once at their first timer start can't get it back from the app: `askNotificationsOnce` returns early, and after two denials Android stops showing the dialog anyway.
     - TWA users who granted the permission keep it across the update, which hides this from the owner's own device.
   - **A fix must cover:**
     - Settings → Notifications shows when notifications are blocked for the app while any reminder switch is on. It has a button that requests the permission if it can still be asked, and otherwise opens `Settings.ACTION_APP_NOTIFICATION_SETTINGS`. An explicit tap there is not limited by "asked once".
     - Turning on the account's Classes or Habit reminders in the app asks too, the same way the device switches do.
     - Pick one in-context first ask that is not at launch (plan Phase 8). For example: the first time the user opens Settings → Notifications, or marks a session. Log the choice.
     - The permission callback already re-runs the engine. Make sure the button path does too, including on return from system settings (`onResume` is enough).
     - Tests: a Settings UI test with notifications reported as blocked shows the row, and tapping it calls the ask. Turning Classes or Habits on calls the ask. With notifications allowed, there is no row.

3. **Class reminders can be dropped silently: the alarm is inexact with an unbounded window, and a class reminder is thrown away 5 minutes after its time (medium).**
   - **Where.**
     - `AndroidReminders.kt:25` uses `setAndAllowWhileIdle(RTC_WAKEUP, at, …)`. For an inexact alarm, AlarmManager lets delivery move up to 75% of the time until the alarm (`maxTriggerTime`).
     - The engine re-arms at midnight (`ReminderEngine.kt:42–43`). At about 00:30 it arms the next reminder, for example an 08:50 class reminder. Its window then runs past 15:00. Wakeups from other apps usually bring it in sooner, but nothing bounds the delay.
     - `lateWindow(CLASS)` is 5 minutes (`Reminders.kt:40–43`), copied from the server's `LATE`. The server can use 5 minutes because its cron runs every minute, which a phone alarm doesn't. If the alarm comes at 08:56 or later, the 09:00 class reminder is filtered out (`Reminders.kt:68`) and never posted. App-standby quotas (working set and frequent buckets) delay alarms further.
   - **Failure scenario.** On a quiet night the phone does little else, so the first wakeup after 08:50 comes at 08:58. The plan drops "Mathématiques in 10 min", and the user gets no class reminder that morning. Check-ins, homework and habits survive because of their 3-hour window, but they can still come hours late on the same mechanism.
   - **A fix must cover:**
     - Bound the delivery. Use `setWindow(RTC_WAKEUP, at, 10 min, …)` (Android 12+ raises shorter windows to 10 minutes) on API 23+, where it is allowed while idle. Alternatively use `setExactAndAllowWhileIdle` for class reminders only. The app already holds `USE_EXACT_ALARM` for the timer, so if you choose this, log why it is justified. Either way, keep the midnight re-plan.
     - Make the class reminder's late window cover that delivery. A class reminder is still useful until the session starts: for example, accept it until `start_at` (at least 5 minutes after its time), and keep "starts now" for lead 0.
     - Change `ClassReminderTest.aLateAlarmCountsTheMinutesLeftAndAStaleReminderIsDropped` to the new rule: 6 minutes late before the start still goes, with "in 4 min", and one after the start is dropped. This changes the rule. It is not a weaker test.
     - Add a Robolectric test (`ShadowAlarmManager`) that the scheduled alarm is a wakeup alarm at the planned time, with a bounded window (or exact).

4. **Shared or opened `.ics` files are read on the main thread, up to 5 MB from someone else's content provider (low, ANR risk).**
   - **Where.** `MainActivity.handleIntent` (`:114–117`) runs in `onCreate` and `onNewIntent` on the main thread. It calls `readIncoming`, which calls `displayName` (a provider query) and `openInputStream(...).readAtMost(5 MB + 1)` (`share/Incoming.kt:44–54`).
   - **Failure scenario.** The user opens an `.ics` that Drive has not downloaded yet, or a large attachment from a mail app. The provider blocks while it fetches the file, the activity can't draw or take input, and after 5 s Android shows "Attendance80 isn't responding". StrictMode would flag this too.
   - **A fix must cover:**
     - Decide the type synchronously from the action, type and URI. Do the query and the read in `lifecycleScope` on `Dispatchers.IO`, then hand the result to `PendingRouteViewModel.shareFile` on the main thread.
     - While it reads, either navigate to Settings at once, or show nothing new.
     - Keep the 5 MB limit and the error messages. `IncomingTest` can keep testing `readIncoming` as it is.

## Answers to the open questions

- **Should the three device switches move into `notify_settings`? No, keep them on the device.**
  - `cleanNotify` (`src/notify.js:17–24`) keeps only `classes`, `classLead`, `habits`, `focus` and `tz`. They exist because the cron reads them (`collectDue`) and the website edits them. The check-in, homework and exam notifications have no server or website counterpart: the website can't post them, and the cron doesn't send them. Storing them server-side would be a server change whose only job is syncing devices.
  - Per device is also the better model. The homework default depends on whether Google Calendar is connected. A second device, such as a tablet, is exactly where someone wants no check-ins.
  - Plan 8.5 ("so the website and the app agree") is met for the switches the website actually has. The class and habit reminders correctly follow the account's switches.
  - Revisit this in Phase 13 only if the daily digest needs a server-side time.
  - One consequence to accept knowingly: the `notify` DataStore is excluded from backup, so a new phone starts with the defaults. That's fine because they are on.
- **Should optional sessions skip the check-in? No, keep asking.**
  - Plan 7.1 says "a tracked session that is unmarked", with no tag exception.
  - The website's own "to mark" rule includes optional sessions of attendance-counting courses: `unmarkedEvents` in `public/app.js:287–290` (the sidebar count, Today's "to mark", "Mark N as attended", the palette) and the calendar's unmarked dot (`public/calendar-ui.js:280`). Only the stats card's `unmarkedPast` leaves them out, because they don't count toward the percentage.
  - If the app skipped them, those sessions would stay in the website's "to mark" count with nothing prompting the user to clear them.
  - If the owner finds them noisy, change the rule on the website and in the app together (`unmarkedEvents` and `checkIns` both skip `TAGS[tag].counts == false`), not in the app alone.

## Deferred, non-blocking

- **Phase 9 (must be done before the release): the TWA's own Web Push subscription keeps receiving class and habit reminders after the update.**
  - The TWA declares a `DelegationService` (`android/app/src/main/AndroidManifest.xml:183–194`). The website registers its subscription with `app: true` (`public/notify-ui.js:24, 45`).
  - `collectDue` sends class and habit reminders to every subscription, including `app = 1` ones. Only focus skips them (`src/notify.js:129, 149, 170`).
  - After the native app installs over the TWA, that endpoint stays in `push_subscriptions`. The cron keeps sending to it. Depending on how Chrome handles a delegated origin whose app no longer has the service, the reminders show a second time on the same phone. The new hint in Settings ("nothing arrives twice") is then untrue.
  - Phase 9 should remove the user's `app = 1` subscriptions once the native app has signed in. This is a small server change with a test, for example an app-session-only call or `/api/data` from an `a80s_` session. Add "update over a TWA with notifications on: each class reminder arrives once" to the device checklist.
- **Phase 8, the review-6 pattern in the engine.**
  - `ReminderEngine.run(null)` cancels the alarm, and the snapshot collector (`AppContainer.kt:136`) passes the `null` it sees on every cold start before the cache loads. The cache load re-arms the alarm within milliseconds, so this only bites if the process is frozen or killed in that gap.
  - Make the collector treat `null` as sign-out only after `awaitToken()` says signed out, as `WidgetPlanWriter` does.
  - In the same change, wrap the post loop and `markSent` in `NonCancellable`. `collectLatest` can cancel a run between posting and recording, and the next run would then re-alert.
- **Phase 8, check-ins from a stale cache.** The alarm path re-plans from the cached snapshot without refreshing (`NotifyReceiver.replan`). A session marked on the website since the last refresh (at most an hour, with `RefreshWorker`) can still get "Were you at …?". The answer just re-sends the same status. A refresh-if-older-than-N-minutes before posting check-ins would avoid it.
- **Phase 8, the `file` scheme in the VIEW filters.** Senders on Android 7+ can't pass `file://`. Keeping it only lets another app point the app at its own private files. The content would be uploaded to the user's own account and only after a tap, so nothing leaks, but the scheme has no use.
- **Phase 11.**
  - Fold `NotifyStore`'s action queue into the outbox, as logged.
  - When doing that, drop a signed-out account's queued actions and sent keys at sign-out. They hold the email and ids and are only cleaned up when another account flushes.

## Checked and correct

- **Account boundaries.**
  - Sign-out cancels the alarm and every notification on the `reminders` channel (not the timer's).
  - The snapshot goes `null` before any other account's data can appear (`DataRepository.start`), and `run(null)` arms nothing.
  - Posted keys are `<account>|<key>`.
  - Queued actions carry the owner. `ActionSender` sends only the signed-in account's items, drops others, and sends nothing while signed out. The worker takes the account from the current session's snapshot.
  - A stale button from account A can't act as B: its owner is A's, and a homework id from A isn't in B's snapshot.
- **Idempotency of the queue.**
  - `PATCH /api/attendance/{id}` and `PATCH /api/homework/{id} {done:true}` are idempotent.
  - `PUT /api/habits/{id}/log` sets the count (`ON CONFLICT … SET count = excluded.count`, `src/habits.js:62`).
  - `POST /api/catchups` returns the existing catch-up for that session, including after a lost race (`src/catchups.js:25–44`).
  - A retry after a timeout can't duplicate anything. A 4xx drops the item, and 5xx or no network keeps it in order.
- **Habit Done day.** The day is the reminder's own date in the device zone, fixed when it was planned, so a tap after midnight still fills the right day. The server accepts days up to UTC today + 2. Done sends `target` (at least 1), as the web's `habitMessage` does.
- **Parity with `src/notify.js`.**
  - Class reminders: tracked courses, `status` null or present, `classLead` from the account, "in N min" from `max(now, at)`, and the same key.
  - Habit reminders: not archived, `remind_at` set, `habitStats(...).due && !todayDone` with `start = keyInZone(created_at)`, and the same key including `created_at`.
  - Both follow the account's `classes` and `habits` switches. The app sends `tz` with the settings (`SettingsActions.saveNotify`). It registers no push subscription.
- **Check-in rule.** It needs a tracked, attendance-counting course (`countsAttendance`) and an unmarked session, at `end_at`. A marked session drops out on the next plan, and a tapped one is in the posted log, so it is never asked twice.
- **Homework reminder times.**
  - `reminderMinutes` matches `src/homework.js`.
  - All-day reminders at 18:00 on the calendar are a sensible, logged deviation. The tests cover both 2026 clock changes and the item's own zone.
  - Off by default with Google Calendar connected, and on once chosen.
- **Rescheduling triggers.**
  - `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET` and `TIMEZONE_CHANGED` reach the non-exported receiver, since system broadcasts are delivered to it and `RECEIVE_BOOT_COMPLETED` is declared.
  - The zone is read on every run (`ZoneId.systemDefault()`).
  - Snapshot and switch changes re-run through `combine`. The extra run caused by `markSent` writing the same DataStore is harmless: it finds nothing new to post.
- **PendingIntents.** Each button has its own data URI, so extras don't collide. Everything is `FLAG_IMMUTABLE`, and the receiver is not exported.
- **Backup.** `datastore/notify.preferences_pb` is excluded from cloud backup and device transfer.
- **The Settings screenshot re-record (`3f4c820`)** is the intended UI change. Before and after, on phone and tablet, the Notifications panel gains the three device switches and their hints, and the class hint is reworded. Everything below moves down by that much. Nothing else in the panels changed. No test code changed in that commit.

## Evidence

- **Read by the reviewer:**
  - the plan's sections A, B, 0, Phase 7, 8.4, 8.5 and Phase 13;
  - the Phase 7 progress entry;
  - the full Phase 7 diff, without the PNGs: all of `notify/` and `share/`, the manifest, `AppContainer`, `MainActivity`, Settings, Shell and the tests;
  - `DataRepository.start`, `FocusController.focusOn`, `askNotificationsOnce`;
  - `src/notify.js`, the notify and catch-up routes in `src/worker.js`, `src/catchups.js`, `src/habits.js` `logHabit`;
  - `public/notify-ui.js`, `public/app.js` `unmarkedEvents`, `public/calendar-ui.js`, `public/stats.js`;
  - the TWA manifest;
  - the before and after PNGs for the tablet top, phone calendars and danger shots.
- **Not run by the reviewer:** Gradle and npm, at the owner's request. The worker reports `npm test` 104/104, and 984 unit tests with 0 failures, Roborazzi verified and `tools/e2e.sh` 2/2.
- No server, website, migration, secret or `android/` changes in the diff.
- **Still needs a device (Phase 9 checklist):** the Unverified list in the progress log. Add to it:
  - "Focus on this" and Absent → Add catch-up after the process was killed (finding 1);
  - a fresh install on Android 13+ (finding 2);
  - a class reminder after a night in Doze (finding 3);
  - opening an `.ics` that Drive has not downloaded (finding 4).
