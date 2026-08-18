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

## 2026-08-18 checkpoint (10 + 5 + 5 minutes)

Frozen revision: `25359ad`

Evidence record: the two 2026-08-18 rehearsal report comments on Issue #19.

### 10-minute report

1. **Project and product claim (1 min):** introduce the traceable path from a ranked match to a public developer identity.
2. **Architecture and ownership (2 min):** summarize the Identity, Community, Battle and Judge boundaries, including event projections and isolated judging.
3. **Current evidence (3 min):** distinguish the merged golden-path UI and rating projection from the two-session runtime evidence.
4. **Frozen revision history (2 min):** identify the PR #70 rating projection and PR #28 P0 UI merges that produced `25359ad`.
5. **P0 gaps and freeze plan (2 min):** call out the missing demonstration-problem seed, unexposed Surrender control, unproven attack exchange, and pending user-profile/handle projection before the 2026-08-19 RC freeze.

### 5-minute live demonstration

| Time | Demonstration path |
|---|---|
| 0:00–1:00 | Register and sign in two browser sessions; publish fenced code and verify the sanitized post from the other session. |
| 1:00–1:45 | Join the same-language ranked queue and confirm both sessions receive the same match. |
| 1:45–3:45 | Ready both players, enter `RUNNING`, show Run energy, keep an incorrect Submit at `WRONG_ANSWER`, accept and lock a correct Submit, and recover one WebSocket reconnect. |
| 3:45–5:00 | Switch to a match finished before the session (the live match's ten-minute deadline exceeds the slot), then show the viewer-relative result, rating/profile projection, automatic result post and player record. |

The rehearsal completed this path on fresh isolated volumes after one manual demonstration-problem `INSERT`. Therefore the no-manual-database-edits Scenario A acceptance remains pending under Issue #73. Surrender is not shown in the current Battle UI, and attack exchange was not demonstrated.

### 5-minute questions

Prepare concise evidence-backed answers for rating projection ordering, Judge isolation, the remaining Issue #17 identity/handle slice, the Issue #19 final acceptance boundary, and the team contribution split.

## 2026-08-20 final presentation draft

1. **Product story:** follow one developer from competitive coding evidence to public identity; state the user value before listing features.
2. **Architecture:** explain service ownership, synchronous and event-driven boundaries, private source handling, Judge isolation and projection consistency.
3. **Live demonstration:** replay only the frozen, preflight-ready path proven in the final rehearsal; name any skipped capability rather than substituting a mock.
4. **Evidence summary:** connect the live path to merged revisions, CI, contracts, runtime traces, projection rows and the final Issue #19 evidence.
5. **Limitations and follow-up:** separate remaining P0 acceptance work from P1 scope, including seed automation, Surrender exposure, attack evidence, identity/handle projection and release/deployment evidence.

Exact final timing, mandatory slide order, scoring weights and submission format remain governed by the `UNKNOWN` rubric note below.

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
