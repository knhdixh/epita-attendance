# Device test extension checklist

`tools/e2e.sh --smoke` checks real local HTTP and seed acquisition only. The normal command additionally runs the managed-device tests and checks that both instrumented cases produced passing results. The two cases use Android Test Orchestrator so the offline case starts in a new app process with persisted app data. A local pass-through proxy forwards real API traffic and then closes connections to simulate the server being unreachable.

- Phase 3: local developer PKCE/callback, populated shell destinations, scope, search, deep links and cached offline process restart are covered by `LocalServerTest` and have passed on the managed device against the isolated local server.
- Phase 4.2: the live case marks all eligible past sessions through Attendance, verifies the selected IDs and unchanged other statuses via local `/api/data`, then checks the refreshed repository snapshot. Phase 4.7 must add creating and completing homework against local D1.
- Phase 5: add timer start, alarm firing, notification actions and offline outbox replay exactly once.
- Phase 6: add a managed-device widget snapshot test.
- Phase 7: add class and habit notification and action tests.
