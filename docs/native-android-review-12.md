# Review 12: Phase 12 (Todoist screens)

Branch `native/phase-12` (`ae13593..6d47e76`), reviewed 2026-11-04.

**Verdict: FIX** (one blocking layout bug, the rest small). All nine tasks are there and look right.

Checks:
- `npm test` 471/471.
- The worker's Gradle numbers (1166 tests, 0 failures) match the result XML.
- e2e passed after its fixes.

Screenshots I looked at myself (the log says only two were opened): tasks inbox (font 1 and 2), quick add, detail, board,
dark browse, and light Today end.
- The priority rings, the meta line, the quick-add highlighting and chips, and the detail sheet's layout all match plan
  section 6.
- The markdown title renders bold in the rows.

## Findings

1. **Blocking: the add button covers the end of every list.**
   - In `phone411x891_light_today_end_font1.png`, the floating "+" sits on the last line of "Next 7 days" ("about 92 h
     fre…"), and the text under it can't be scrolled into view.
   - Every screen that got the add button needs bottom content padding of at least the button's height plus 16 dp:
     Today, Inbox, Upcoming, project list and board, filter and label pages.
   - Add a screenshot or a Compose test that scrolls to the end of Today and asserts the last node isn't under the button.
2. **Should fix: sort, group and "show completed" are lost when the app closes.** Todoist keeps them per project.
   - Store them per project id in `UiPrefs` (DataStore, per account like the other UI prefs).
   - Test that they survive a new view model.
3. **Minor: Browse says "Filters & Labels" twice.** `phone411x891_dark_tasks_browse_font1.png` shows the section heading
   and then a row with the same words. Keep the heading; turn the row into a "Manage" or "Edit" entry, or drop it and make
   the heading tappable.
4. **Minor: the board's "No section 1" reads as a name.** Show "(No section)", and put each column's count in the
   muted meta style, separate from the name. The same applies to "Writing 2".
5. **Minor: quick add's highlight splits `!30m` across lines.** Make each matched token non-breaking (U+2060 word joiners
   in the displayed text only, or a single span with `LineBreak`/no-wrap), so a reminder chip-word never wraps after `!`.

## Deferred items not done in this phase

- Review 11: moving the Calendar's `setPlan` and "Plan my week" onto the outbox. **Deferred to Phase 13 (blocking there).**
  Upcoming's drag already goes through the outbox, so the two date-changing paths behave differently offline. The hung
  `CalendarActionsTest` is most likely the test waiting on `flushQueue` while the MockWebServer has no queued answer, or
  `flushMutex` re-entered from inside a `mutate`. Find the cause rather than reverting again.
- Review 10, the touch-target question: closed. Not reproduced since the e2e fix, and nothing points at a user-facing bug.

## Answers to open questions

- Keep the Homework row in Browse › School for now. Once the old Homework screen's last callers (palette, Course screen)
  open task lists instead, it can become the built-in `school` filter. That isn't part of Phase 13.
- Calendar `setPlan` on the outbox: yes, in Phase 13 (above).

---

# Re-review round 1 (2026-11-04): PASS

All five findings are fixed (`1da7898..63bbaf3`). I opened the re-recorded `phone411x891_light_today_end_font1.png`: the last line
clears the add button. Gradle: 1171 tests and 0 failures per the log, and `npm test` 471/471. The Calendar `setPlan` outbox item stays
deferred to Phase 13, where it's blocking.
