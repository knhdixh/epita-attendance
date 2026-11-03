# Review 11: Phase 11 (offline tasks)

Branch `native/phase-11` (`c6ae372..5103e2b`), reviewed 2026-11-04.

**Verdict: FIX.** Two small retry holes need closing; the rest is solid.

- The outbox design holds up. It's a per-account queue file replayed over the server cache, temporary ids are remapped
  in paths and bodies, a refused create drops its dependants, and a lost answer never doubles a row (`client_id` on every
  create, `withPending` skips creates the server already has).
- The golden tests are generated from the server's own test tables.
- Every item deferred from reviews 4, 5, 7 and 10 is done.
- Checks I re-ran: `npm test` 470/470. Gradle `testProdDebugUnitTest lintProdDebug assembleProdDebug verifyRoborazziProdDebug`:
  BUILD SUCCESSFUL, 1104 tests in the result XML, 0 failures, 0 skipped.
- Server commits read: `de5eb4b` (occurrence), `081850a`/`a4e8e1a` with `0021` (additive).

## Findings

1. **Blocking: a retried DELETE reports a false error.** In a tunnel, the server can delete the row while the answer
   is lost. The outbox retries, gets 404, treats that as a refusal (`DataRepository.kt:276`), shows "Homework not found."
   (or "Comment not found.", "Reminder not found.", "Project not found.") and reloads. The user is told a delete failed
   when it worked.
   - A 404 on a queued `DELETE` means it's done: settle it quietly (no message), as a 2xx would.
   - Keep 404 a refusal for every other method.
   - Test with MockWebServer: the first DELETE answers but the client times out, the retry gets 404, no error is
     reported, 0 pending.

2. **Blocking: Undo of a repeating task isn't idempotent.** `uncompleteTask` (`src/homework-store.js`) rewinds a
   repeating task to the last `completions` row from the past 24 h. If a daily task was completed twice today
   (done early, then again), a retried `uncomplete` rewinds **both**. The same hole `occurrence` closed for complete.
   - Server (own `Server:` commit): `/uncomplete` and `PATCH {done: false}` take an optional `occurrence`, the due date
     the client saw after completing (the date it moved on to). A repeating task is only rewound when that still equals
     the stored `due_date`.
   - App: `Mutations.uncompleteTask` sends it, and Undo has it from the snapshot.
   - Tests: the same uncomplete twice rewinds once (server), and the body carries `occurrence` (app).

3. **Minor: Unicode whitespace.** Java's `\s` is ASCII-only and JavaScript's isn't. Some Android keyboards and pasted
   text bring U+00A0. Compile the ports' patterns with `UNICODE_CHARACTER_CLASS` (or replace `\s` with an explicit
   class), and add one golden input with a no-break space for each of quick add and filters.

## Answers to open questions

- **The draft after a refused change:** no, the message is enough. Refusals are rare, since the client validates the
  same fields before queueing, and keeping dropped mutations for editing adds UI for an edge case.

## Not blocking, noted

- `AppNavAccountTest.switchingAccountsDiscardsSavedNavigationAndAction` failed once under load. If it shows up again,
  give it its own wait for the result route instead of relying on idle.
- "Every mutation" is deliberately limited to the task, habit and notification paths. The remaining `mutate` callers
  are online-only screens, which is acceptable and logged.
- Deploy: `0020` and `0021` both need applying to prod. Owner's call.
