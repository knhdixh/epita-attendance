# Review 10: Phase 10 (server, tasks)

Branch `native/phase-10` (2f89954..8e9c028), reviewed 2026-11-03 against `main` c3849fb.

**Verdict: FIX.** The data model and migration are sound. Additive `ALTER`s with NULL or valid defaults, `ON DELETE
CASCADE` everywhere, partial unique `client_id` indexes, no existing migration touched. I accept the `NO_DATE`
placeholder decision: rebuilding `homework` on D1 would fire `SET NULL` on focus sessions and countdowns. Old clients
are shielded (no `?tasks=1`, `/api/plan`, feeds, gcal). I re-ran `npm test`: 455/455 pass. I probed the three
parsers by hand (Todoist examples, past `until` and `starting` dates re-cleaned on a later PATCH): their output is
stable and sensible. Four things need fixing before Phase 11 builds on this.

## Findings

1. **Blocking: the full `tools/e2e.sh` fails.** The log says `LocalServerTest.a_signInAndLiveShell` times out at
   `LocalServerTest.kt:248`, and `b_` fails after it. It also fails on the Phase 9 server, so it's older than this
   phase. AGENTS.md still requires the checks to pass, though, and Phase 11 touches the same path. Find the root
   cause and fix it.
   - The test taps `calendar-col-<tomorrow>` at `height * 0.08`. It ran at about 22:00 local time and passed earlier
     in the day, so suspect "tomorrow" being computed in the test's zone versus the app's, or the column not being on
     screen in the view that opens late in the day.
   - If it's a test-clock problem, pin the clock or derive the date from what the screen shows. If it's an app bug
     (for example, the grid's "tomorrow" is wrong near midnight), fix the app and add a unit test at 23:30 local.
   - Don't skip or loosen the assertion. Log the cause you found and show the passing run.

2. **Blocking: quick add breaks multi-word project and section names.**
   `parseQuickAdd('Read chapter 3 tom at 9am #School /Week 1 …')` gives section `"Week"` and the title
   `"Read chapter 3 1"`. Todoist matches existing names, so `/Week 1` and `#Thesis work` work there.
   - Add an option `parseQuickAdd(text, { today, projects, sections, labels })` (arrays of existing names).
     After `#`, `/` or `@`, take the longest existing name that matches case-insensitively at a word boundary, and
     fall back to one word when none matches (what happens now).
   - Include the whole name in `ranges`.
   - Table tests: multi-word project and section, a prefix clash (`#School` vs `#School admin`), an unknown name
     falling back to one word, and nothing leaking into the title.
   - Phase 11 ports this signature, so settle it now.

3. **Blocking: `PATCH done` and `/complete` disagree.** `POST /api/homework/{id}/complete` completes open subtasks
   and sets `completed_at`. `PATCH {done: true}` (what the website sends) does neither for subtasks, and only
   rerouting recurring tasks covers that case. `PATCH {done: false}` on a repeating task that moved on does nothing
   like Undo.
   - Route `PATCH` with `done` changing through `completeTask` / `uncompleteTask`. Apply any other fields in the same
     body first, then the completion.
   - Test that a parent with two subtasks completed by `PATCH` completes all three, the same as `/complete`.

4. **Blocking: an undated task forgets its time zone.** `homeworkInput` stores `tz = 'UTC'` when an undated task
   comes without `tz`. A later `PATCH {due_date: '2026-11-10', due_time: '09:00'}` that leaves out `tz` then
   computes `due_at` in UTC: an hour or two off in Paris and Helsinki, so reminders and the feed are wrong.
   - Keep the `tz` sent with an undated task.
   - When a PATCH gives an undated task a date, require `tz` in the body, or fall back to the user's
     `notify_settings.tz`. Pick one, use the plain-English 400 if neither exists, and log the choice.
   - Test: create undated with `tz: 'Europe/Paris'`, PATCH a date and time without `tz`, and assert `due_at`.

5. **Should fix: plan section 4 (API reference) wasn't updated.** The progress log is now the only list of the new
   endpoints, request bodies and `/api/data?tasks=1`. The plan allows section 4 corrections, and the Phase 11 worker
   reads section 4, not the log.
   - Add the endpoints: method, path, body fields, response shape, `client_id` rules.
   - Add the `NO_DATE` / `tasks=1` contract and the `PATCH`/`move` placement semantics.

6. **Minor: `until` and `starting` don't accept month and year.** `every 2nd mon until dec 2026` gives
   "I couldn't read the date "dec 2026"". Either accept `<month> <year>` (the last day of that month for `until`, the
   first for `starting`) with tests, or leave it and say so in the log. Your choice; nothing depends on it.

## Not findings (checked)

- The `EXPLAIN QUERY PLAN` assertions cover the seven `/api/data` additions. The label join is
  `homework_user_due` → `task_labels` PK, which is fine for the D1 free-plan read budget at this size.
- Cascades: project → subprojects, sections, tasks → subtasks, labels, comments, reminders and completions; account
  deletion. Google Calendar events are removed before rows cascade away.
- Token scope: write tokens get move, complete and uncomplete only; project, label and filter endpoints stay closed.
- `plate`: `/api/plan` is unchanged (`test/plate.test.js`); the only export added to `public/countdowns.js` is the
  word lists.
- Commit subjects `Server:` / `Tasks:` for server-only commits are fine.

## Deploy note for the owner

After this merges, `0020_tasks.sql` must be applied to prod before or with the deploy (`npm run deploy` does both).
It's additive, so the website keeps working with the new columns and ignores them.
