# D³ presentation outline

Owner: 최정민

Status: Initial narrative baseline; exact rubric pending

Last verified: 2026-08-13 against the MVP specification and demo runbook

Requirements: D3-DOC-001, D3-UX-002

## Core claim

D³ turns competitive programming evidence into a developer's public identity: one ranked match produces a judged result, rating and seasonal progression, a community result post, and a traceable match record.

## Proposed 15-minute body

1. **Problem and product (2 min):** coding practice is isolated from everyday developer identity; introduce the continuous D³ path.
2. **Architecture and safety (2 min):** show service ownership, event projections, private source and isolated Judge0.
3. **Live Scenario A (7 min):** two sessions match, code, exchange a reversible attack, submit, receive a result, and inspect the projected post/record.
4. **Engineering evidence (2 min):** show reconnect or incident behavior, contract/test evidence and observability.
5. **Trade-offs and next step (2 min):** separate MVP evidence from P1, uncalibrated rating, cloud uncertainty and post-prototype work.

Keep five minutes for failure recovery and questions unless the final presentation rule says otherwise.

## Slide and evidence map

| Section | Primary repository source | Evidence still required |
|---|---|---|
| Product value and scope | [`docs/specs/d3-mvp.md`](../specs/d3-mvp.md) | Final rubric mapping |
| User journey | [`docs/architecture/workflow.md`](../architecture/workflow.md) | Implemented Scenario A capture |
| System boundaries | [`docs/architecture/system-context.md`](../architecture/system-context.md), [`services.md`](../architecture/services.md) | Running service/health view |
| Data ownership | [`docs/architecture/erd.dbml`](../architecture/erd.dbml) | Migrations and final rendered ERD |
| Cloud target | [`docs/architecture/cloud.md`](../architecture/cloud.md) | AWS bindings or declared local fallback |
| UX direction | [`docs/wireframes/README.md`](../wireframes/README.md) | Reviewed styled screenshots |
| Verification | [`docs/quality/test-plan.md`](../quality/test-plan.md) | Final pass/fail/skip counts |
| Live operation | [`docs/operations/demo-runbook.md`](../operations/demo-runbook.md) | Frozen revision, preflight and recording |

## Presentation rules

- Build the story around one traceable golden path rather than a catalogue of screens.
- Label architecture targets, implemented behavior, measured evidence and future work distinctly.
- Show rating/RP values as prototype rules, not statistically calibrated competitive claims.
- Keep private source, tokens, hidden tests and credentials out of slides, terminals and recordings.
- Use the exact build revision and environment recorded in the demo runbook.

Exact mandatory slide order, scoring weights and submission format remain `UNKNOWN` until the bootcamp rubric is confirmed.
