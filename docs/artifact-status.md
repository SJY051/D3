# Submission artifact status

Owner: 윤서진

Status: Submission sources aligned; fresh RC `c738cd8` two-session acceptance PASS (2026-08-19); recording and application cloud deployment evidence pending

<<<<<<< HEAD
Last verified: 2026-08-19 against `c738cd8`, the merged P0 implementation, PR #98 early-judging tests, and recorded evidence boundaries
=======
Last verified: 2026-08-19 against `c738cd8`, the merged P0 implementation and recorded evidence boundaries
>>>>>>> origin/main

Requirement: D3-DOC-001

## Required artifact inventory

The planning PDF names the ten artifact classes below. This table is the authoritative repository inventory for owner, status, last verification and missing evidence. `Baseline` means a current source exists; it does not mean implemented behavior, final submission quality or an awarded score.

| # | Required artifact | Repository source | Owner | Status | Last verification point | Evidence still required |
|---:|---|---|---|---|---|---|
<<<<<<< HEAD
| 1 | Requirements specification: core/optional, priority and MUST/SHOULD | [MVP specification](specs/d3-mvp.md#internal-scoring-priority-plan) | 윤서진 | P0 implementation and deterministic-fake rehearsal evidence aligned; P1 remains explicitly optional | 2026-08-19 · `2915d0f` · PRs #70/#75/#76/#79/#89/#91/#92/#96 cross-check | Final fresh-RC acceptance and submission recording |
| 2 | Workflow Swimlane | [Ranked workflow](architecture/workflow.md) | 최정민 | P0 service hand-offs implemented; both-accepted submit starts early judging while deadline remains the one-accepted path | 2026-08-19 · `c738cd8` · `25359ad` rehearsal plus the fresh RC acceptance covering the both-accepted early-JUDGING path | Deployed live-Judge0 application E2E |
| 3 | System architecture | [System context](architecture/system-context.md) | 윤서진 | Local Gateway, four domain services, service-owned data and event topology active | 2026-08-19 · `2915d0f` · active local topology cross-check | Application cloud deployment evidence |
| 4 | Service architecture | [Service boundaries](architecture/services.md) | 윤서진 | Identity/Battle/Judge P0 paths and Community result post, public record, rating/profile projections and handle search implemented; issue #17 closed | 2026-08-19 · `2915d0f` · PRs #60/#64/#70/#75/#76/#89/#96 | Final fresh-RC browser acceptance; live Judge0 application E2E |
| 5 | Service database ERD | [Logical ERD](architecture/erd.dbml) | Service owners | Logical baseline: 33 tables, 21 intra-service refs; Identity V3, Community V6, Judge V3, Battle V12 | 2026-08-19 · `2915d0f` · DBML declarations and Flyway-chain cross-check | Rendered diagram and production query-plan evidence |
| 6 | Cloud architecture | [Cloud architecture](architecture/cloud.md) | 윤서진 | Account/region and zero-ingress Judge0 host bound; issue #59 production-adapter source-SG route smoke PASS; application services not deployed | 2026-08-19 · `2915d0f` · issue #59 six-runtime route evidence and cleanup | Application IAM/quota/resources and deployed judge-service integration (**PENDING/NOT RUN**) |
| 7 | Wireframes and functions | [WF-01 through WF-08](wireframes/README.md) | 최정민 | WF-01–WF-06 approved and implemented; global active-match rejoin banner and self-verdict/accepted-lock state merged; WF-07/WF-08 review required | 2026-08-19 · `2915d0f` · PRs #28/#76/#91/#92/#96 | Final integrated screenshots/recording; WF-07/WF-08 review |
| 8 | Test plan: unit, integration, load and chaos | [Test plan](quality/test-plan.md) | 최정민 and service owners | Unit/integration/contract suites plus `25359ad` deterministic-fake two-browser rehearsal, issue #59 route smoke, and #98 early-judging unit/integration tests recorded | 2026-08-19 · `c738cd8` · service, browser and AWS evidence boundary cross-check; fresh RC acceptance PASS including the early-JUDGING capture | Deployed live-Judge0 application, load and chaos reports |
| 9 | Deployment plan: CI/CD, procedure and rollback | [Deployment plan](operations/deployment-plan.md) | 윤서진 | Local runtime and CI active; Judge0 host/route smoke proven; image pipeline and application cloud deployment pending | 2026-08-19 · `2915d0f` · Compose/CI and issue #59 review | Container images, AWS/OIDC bindings, application deploy and rollback rehearsal |
| 10 | Source and README | [README](../README.md), repository and [public contracts](../contracts/README.md) | Team | Executable local P0 baseline including #89 heartbeat/reconnect and #96 self-verdict/accepted lock | 2026-08-19 · `2915d0f` · merged source/docs/contracts cross-check | Frozen RC substitution, final acceptance, recording, license decision and release evidence |
=======
| 1 | Requirements specification: core/optional, priority and MUST/SHOULD | [MVP specification](specs/d3-mvp.md#internal-scoring-priority-plan) | 윤서진 | P0 implementation and deterministic-fake rehearsal evidence aligned; P1 remains explicitly optional | 2026-08-19 · `c738cd8` · PRs #70/#75/#76/#79/#89/#91/#92/#96 cross-check | Final fresh-RC acceptance and submission recording |
| 2 | Workflow Swimlane | [Ranked workflow](architecture/workflow.md) | 최정민 | P0 service hand-offs implemented and rehearsed locally with the deterministic fake judge | 2026-08-19 · `c738cd8` · `25359ad` two-browser rehearsal plus #89/#96 delta | Final fresh-RC trace; deployed live-Judge0 application E2E |
| 3 | System architecture | [System context](architecture/system-context.md) | 윤서진 | Local Gateway, four domain services, service-owned data and event topology active | 2026-08-19 · `c738cd8` · active local topology cross-check | Application cloud deployment evidence |
| 4 | Service architecture | [Service boundaries](architecture/services.md) | 윤서진 | Identity/Battle/Judge P0 paths and Community result post, public record, rating/profile projections and handle search implemented; issue #17 closed | 2026-08-19 · `c738cd8` · PRs #60/#64/#70/#75/#76/#89/#96 | Final fresh-RC browser acceptance; live Judge0 application E2E |
| 5 | Service database ERD | [Logical ERD](architecture/erd.dbml) | Service owners | Logical baseline: 33 tables, 21 intra-service refs; Identity V3, Community V6, Judge V3, Battle V12 | 2026-08-19 · `c738cd8` · DBML declarations and Flyway-chain cross-check | Rendered diagram and production query-plan evidence |
| 6 | Cloud architecture | [Cloud architecture](architecture/cloud.md) | 윤서진 | Account/region and zero-ingress Judge0 host bound; issue #59 production-adapter source-SG route smoke PASS; application services not deployed | 2026-08-19 · `c738cd8` · issue #59 six-runtime route evidence and cleanup | Application IAM/quota/resources and deployed judge-service integration (**PENDING/NOT RUN**) |
| 7 | Wireframes and functions | [WF-01 through WF-08](wireframes/README.md) | 최정민 | WF-01–WF-06 approved and implemented; global active-match rejoin banner and self-verdict/accepted-lock state merged; WF-07/WF-08 review required | 2026-08-19 · `c738cd8` · PRs #28/#76/#91/#92/#96 | Final integrated screenshots/recording; WF-07/WF-08 review |
| 8 | Test plan: unit, integration, load and chaos | [Test plan](quality/test-plan.md) | 최정민 and service owners | Unit/integration/contract suites plus `25359ad` deterministic-fake two-browser rehearsal and issue #59 route smoke recorded | 2026-08-19 · `c738cd8` · service, browser and AWS evidence boundary cross-check | Fresh-RC acceptance; deployed live-Judge0 application, load and chaos reports |
| 9 | Deployment plan: CI/CD, procedure and rollback | [Deployment plan](operations/deployment-plan.md) | 윤서진 | Local runtime and CI active; Judge0 host/route smoke proven; image pipeline and application cloud deployment pending | 2026-08-19 · `c738cd8` · Compose/CI and issue #59 review | Container images, AWS/OIDC bindings, application deploy and rollback rehearsal |
| 10 | Source and README | [README](../README.md), repository and [public contracts](../contracts/README.md) | Team | Executable local P0 baseline including #89 heartbeat/reconnect, #96 self-verdict/accepted lock, #95 persistent ranked queue, #98 early both-accepted finish, #101 author handles and #103 sequence-stream fix | 2026-08-19 · `c738cd8` · merged source/docs/contracts cross-check | Frozen RC substitution, final acceptance, recording, license decision and release evidence |
>>>>>>> origin/main

## Supporting presentation sources

- [Presentation outline](presentation/outline.md): narrative baseline; exact mandatory order remains `UNKNOWN`.
- [Demo runbook](operations/demo-runbook.md): isolated preflight and two-session rehearsal completed on frozen `25359ad`; final two-session acceptance and recording remain pending.
- [Security review](quality/security-review.md): required boundary; implementation-targeted review remains pending.
- [Submission checklist](requirements/submission-checklist.md): operational freeze and packaging gates.

## PDF provenance and evaluator focus

The PDF uses a generic 10-core-by-7 and 10-optional-by-3 planning structure. D³ mirrors the shape in its [internal priority matrix](specs/d3-mvp.md#internal-scoring-priority-plan); the feature names and planning weights are project decisions, not an official free-topic rubric. No separate free-topic scoring table was present in the reviewed PDF.

The PDF also highlights possible evaluation movement around artifact completeness (±10), architecture (±5), and presentation/demonstration (±5). These are treated as review attention areas rather than guaranteed points. The PDF's example staffing and 40-hour schedule assume five to six people and are not used as evidence for D³'s four-person, eight-calendar-day plan.

## Status rules

- **Baseline:** source exists and is internally cross-referenced.
- **Target baseline / planned:** intended behavior or operation is documented but not observed.
- **Review required:** a named human approval is still absent.
- **Awaiting binding / `UNKNOWN`:** external identity, host, value or constraint has not been supplied.
- **Evidenced:** the named acceptance path passed on a recorded revision and environment.

Update this inventory whenever a source, owner, status or evidence point changes. Final freeze requires every row to name the exact commit and captured evidence rather than only a date.
