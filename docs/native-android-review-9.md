# Phase 9 (local install) review

Verdict: **PASS**, reviewed 2026-11-03 at `dec4466` on `native/phase-9`, by Claude. Scope: `docs/briefs/phase-9-local.md` (the plan's Phase 9 step 4 and the Play Console work are left for a real release).

- `ae9dd0e`: `versionCode` 6, above the TWA's 5; `versionName` 2.0.0.
- `9fe620a` (server): `startAppSession` deletes the user's `push_subscriptions` rows with `app = 1` in the same batch that creates the native session, so the passkey and code-exchange sign-ins both do it. Browser rows (`app = 0`) and other users' rows stay; the test covers all three. Uses the `push_subscriptions_user` index. Other devices still on the TWA lose Web Push for that account (accepted). **Takes effect only after the owner deploys.**
- `d43e2d8`: the exact-alarm broadcast is documented as "granted", and `FocusReceiver` now re-arms through `restore()` on it, with a test.
- Checks by the reviewer: `npm test` 106/106; `testProdDebugUnitTest lintProdDebug assembleProdDebug assembleProdDebugAndroidTest verifyRoborazziProdDebug assembleProdRelease` BUILD SUCCESSFUL, 1071 tests, 0 failures.

Unverified until the owner's phone: everything in the log's first-day device checklist.
