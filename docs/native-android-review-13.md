# Review 13: Phase 13 (Todoist reminders and surfaces)

Branch `native/phase-13` (`954cd32..248e424`), reviewed 2026-11-04.

**Verdict: FIX** (two small items; everything else passes).

- All four plan items are there, plus the blocking deferred item: the Calendar's plan is on the outbox.
- The root cause of the hung `CalendarActionsTest` was found and explained: an inline flush on Robolectric's Main thread.
  The fix is in the test only, which is right, because the app's dispatchers are fine on a device.
- `npm test` 471/471. The log reports Gradle at 1208 tests, 0 failures, and the e2e passing.
- The deviations are reasonable, and I accept them all:
  - snooze is a two-step menu, because of the three-button limit;
  - "Tomorrow" means 09:00;
  - a date-only task's relative reminders count back from 09:00;
  - snoozes stay on the device.

## Findings

1. **A snooze outlives the occurrence it was for.**
   - The repro: snooze a repeating task's reminder for an hour, then tick it in the app. The task moves to its next date
     and stays open, so `taskReminders` still posts the snooze an hour later, about an occurrence that's already done.
     The same happens if the task is deleted and the snooze is then pruned only by age.
   - The fix: store the snooze with the task's `due_date` at the time it was pressed. Drop it when the task is done, gone,
     or its `due_date` no longer matches.
   - Test: snooze, complete the repeating task (it moves on), and assert nothing is planned for the snooze.
2. **There's no screenshot of the new Notifications rows.** The 20 re-recorded Settings images only shifted.
   - Add Roborazzi shots of the Notifications panel with "Task reminders" and "Daily digest" (on, with its time), light
     and dark, phone, plus font 2.0.
   - Open them and say in the log that you looked.

## Answers to open questions

- **Quick-add overlay for the tile and widget:** not now. The sheet over the app is fine for a personal install. Revisit
  it if cold start is slow on the phone, which is on the device checklist.
- **Syncing snoozes:** no. They stay per device.

## The whole plan

Phase 13 is the last phase in section 9. After this merges, the remaining work is the device checklists in the Phase 9
and Phase 12/13 "Unverified" sections, and a real release (Phase 9 step 4 and the Play Console), both for the owner.
