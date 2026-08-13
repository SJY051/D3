---
name: demo-smoke
description: Run the D³ presentation preflight and classify live-demo readiness without hiding unavailable dependencies. Use only when the user explicitly invokes $demo-smoke before rehearsal or presentation.
---

# Demo smoke

1. Read the deterministic demo scenario in `docs/specs/d3-mvp.md` and the current deployment plan.
2. Run `pnpm demo:preflight` and preserve its service-by-service result.
3. Run the fake-judge golden browser test, then the real-Judge smoke only when the judge profile is configured.
4. Confirm seeded users, fixed problem, attack configuration, record projection, and backup recording metadata.
5. Report `READY` only when every required live component passes. Report `DEGRADED` with the exact fallback for optional failures, otherwise report `NOT READY`.

Never describe a mock, skipped check, or recording as live behavior.

