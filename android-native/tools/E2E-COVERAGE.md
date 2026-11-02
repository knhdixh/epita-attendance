# Device test extension checklist

`tools/e2e.sh --smoke` checks real local HTTP and seed acquisition only. The normal command additionally runs the managed-device tests and checks that both instrumented cases produced passing results. The two cases use Android Test Orchestrator so the offline case starts in a new app process with persisted app data. A local pass-through proxy forwards real API traffic and then closes connections to simulate the server being unreachable.

- Phase 3: local developer PKCE/callback, populated shell destinations, scope, search, deep links and cached offline process restart are covered by `LocalServerTest` and have passed on the managed device against the isolated local server.
- Phase 4.2: the live case marks all eligible past sessions through Attendance, verifies the selected IDs and unchanged other statuses via local `/api/data`, then checks the refreshed repository snapshot.
- Phase 4.3: the live case opens the actual course deep link, changes its default tag through the Course destination, waits for the serialized repository refresh and verifies the tag through local `/api/data`.
- Phase 4.4: the live case changes and restores tracking from the actual Courses list, waits for the optimistic mutation and a serialized repository refresh, and compares the changed and untouched courses with local `/api/data`.
- Phase 4.5: the live session-detail case passed on the managed device against isolated local D1: it marks a past session absent, checks selected and untouched event statuses, verifies a catch-up, edits its tag and note, then marks it attended and checks removal of the untouched catch-up. It waits for serialized repository refreshes before persisted API comparisons. The offline restart remains a separate process.
- Phase 4.6: the managed-device live case opened Upcoming with local seeded data, advanced and returned a week, and selected the current day to reach its actual section. The two existing cases passed, including the fresh-process offline check.
- Phase 4.7: the live case creates and completes a homework item through the actual native form and list, waits for serialized repository refreshes, and compares the created item and untouched existing homework via local `/api/data`. Both cases passed on the managed device; the separate offline case still starts a fresh app process.
- Phase 5: add timer start, alarm firing, notification actions and offline outbox replay exactly once.
- Phase 6: add a managed-device widget snapshot test.
- Phase 7: add class and habit notification and action tests.
