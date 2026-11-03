Phase 9, local install only, on the branch native/phase-9 (already checked out). The owner is not releasing the app
yet: they will install the native app over the TWA on their own phone. Do only the tasks below; leave the rest of
the plan's Phase 9 (step 4, the folder renames and READMEs, and the Play Console work) for a real release.

1. **Version.** Set `versionCode` in android-native/app/build.gradle.kts above the TWA's (read it from
   android/app/build.gradle; it is 5 today), so the native app installs as an update. Keep `versionName` 2.0.0.
   Android commit.

2. **Drop the TWA's Web Push subscriptions when the native app signs in** (review 7, deferred to Phase 9). The TWA
   registered Web Push subscriptions with `app = 1` in `push_subscriptions` (migrations/0018_notifications.sql). After
   the native app replaces the TWA on the phone, the server keeps sending class and habit reminders to them, so they
   arrive twice. When the server issues a native app session token (the bearer token flow from Phase 1, see
   migrations/0019_app_auth.sql and src/worker.js), delete that user's `app = 1` rows in the same request. Leave
   browser subscriptions (`app = 0`) alone. This also stops Web Push on any other device still running the TWA for
   that account; that is accepted, so say it in the log. No migration is needed. Add a `node:test` test next to the
   existing auth or notify tests: an app = 1 and an app = 0 subscription for the user and one for another user;
   after the native sign-in only the user's app = 1 row is gone. Server commit (subject 'Server: …'), separate from
   any Android commit.

3. **Review items deferred to Phase 9 that need no device and no Play Console:** fix the exact-alarm receiver comment
   (`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` arrives when the permission is granted, not revoked) and
   make the focus-timer receiver re-arm its alarm on that broadcast too (review 8), with a test. List the other
   Phase 9 deferred items (startup measurement, Play declaration, device checklist) in the log as left for the
   device or the release.

4. **Signed release build check.** In android-native, run `./gradlew assembleProdRelease` (it signs with the TWA key
   through Gradle; never read or print android/keystore.properties or the keystore). Then run
   `apksigner verify --print-certs` (build-tools under ANDROID_HOME) on the APK and confirm its SHA-256 equals
   `ANDROID_CERT_SHA256` in wrangler.jsonc, and `aapt2 dump badging` shows the new versionCode. Record both in the
   log. If R8 warnings or errors appear for the release build, fix them (keep rules in proguard-rules.pro). Do not
   install anything; the owner does that.

The log entry is 'Phase 9 (local install)'. In it, write a short device checklist for the owner's first day: the
Unverified items from the Phase 5 to 8 entries and reviews 5 to 8, each one line, grouped by screen or feature.
The server change in task 2 only takes effect after the owner deploys; say so at the top of the entry.
