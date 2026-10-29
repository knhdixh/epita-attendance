# Agents

The native Android app (`android-native/`) is built phase by phase from **`docs/native-android-plan.md`**. Read its
sections A, B, 0, 8 and 9 before writing any code, and follow section B for branches, commits, the progress log and
review stops.

**Unattended runs** follow `docs/overnight-run.md`. It overrides section B's merge and stop rules for the
`native/night` branch only.

- Implementation log: `docs/native-android-progress.md`. Append to it, never rewrite past entries.
- Reviews: `docs/native-android-review-<n>.md`, written by the reviewer. Fix every item, then log the fixes.
- Never deploy, apply remote migrations, push to `main`, merge, or touch secrets (plan section B, "What you must
  never do").
- Never edit a migration that already exists; add the next number.
- Checks before every commit: `npm test`, and for Android changes
  `cd android-native && ./gradlew testProdDebugUnitTest lintProdDebug`.
