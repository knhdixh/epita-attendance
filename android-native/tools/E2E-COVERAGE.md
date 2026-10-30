# Device test extension checklist

The harness and fixture are incomplete. The local smoke mode checks only real HTTP acquisition of a subset of seeded records. It does not run instrumented tests. The normal command deliberately fails until Phase 3 device coverage exists.

- Phase 3: implement real local dev sign-in and PKCE callback, each shell destination with seeded data, scope, search, deep links and offline restart using the cached snapshot. Expand the seed into a representative semester with genuinely group-split courses and verify sessions, logs and focus records through the API.
- Phase 4: exercise attendance marking and homework creation/completion against local D1.
- Phase 5: exercise timer start, alarm, notification actions and offline outbox replay exactly once.
- Phase 6: verify the widget snapshot on the managed device.
- Phase 7: verify class and habit notifications and their actions.
