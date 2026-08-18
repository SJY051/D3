# Submission artifact status

Owner: 윤서진

Status: Required-source baseline; implementation and final presentation evidence pending

Last verified: 2026-08-18 against origin/main at 25359ad plus PR #28 golden-path UI and PR #70 rating projection merges

Requirement: D3-DOC-001

## Required artifact inventory

The planning PDF names the ten artifact classes below. This table is the authoritative repository inventory for owner, status, last verification and missing evidence. `Baseline` means a current source exists; it does not mean implemented behavior, final submission quality or an awarded score.

| # | Required artifact | Repository source | Owner | Status | Last verification point | Evidence still required |
|---:|---|---|---|---|---|---|
| 1 | Requirements specification: core/optional, priority and MUST/SHOULD | [MVP specification](specs/d3-mvp.md#internal-scoring-priority-plan) | 윤서진 | Baseline | 2026-08-14 · PDF/source and AWS activation-gate cross-check | Implemented evidence per matrix item; final rubric confirmation |
| 2 | Workflow Swimlane | [Ranked workflow](architecture/workflow.md) | 최정민 | Target baseline | 2026-08-13 · scenarios and contracts cross-check | Render review and Scenario A/B/C runtime traces |
| 3 | System architecture | [System context](architecture/system-context.md) | 윤서진 | Baseline | 2026-08-13 · boundary source review | Running topology and final deployment substitutions |
| 4 | Service architecture | [Service boundaries](architecture/services.md) | 윤서진 | Local platform active; identity/battle/judge P0 paths plus Community match projection, ranked result post, public record HTTP and rating projection implemented; user-profile projection and handle search pending | 2026-08-17 · PR #70 `rating.changed.v1` consumer, rating-first upsert and replay/out-of-order Kafka/PostgreSQL evidence | Remaining Community user-profile projection, handle search and integrated browser authorization evidence |
| 5 | Service database ERD | [Logical ERD](architecture/erd.dbml) | Service owners | Logical baseline: 32 tables, 20 intra-service refs; four forward-only Flyway chains | 2026-08-17 · Community V5 rating-first nullable identity fields with V1–V4 checksum preservation | Rendered diagram, remaining legacy cleanup validation and production query-plan evidence |
| 6 | Cloud architecture | [Cloud architecture](architecture/cloud.md) | 윤서진 | Account/region and Judge0 host bound; application-to-Judge0 source-SG route proven; application services pending | 2026-08-15 · issue #59 production-adapter private-route smoke and full temporary-resource cleanup | Application IAM, quota and deployed-resource evidence |
| 7 | Wireframes and functions | [WF-01 through WF-08](wireframes/README.md) | 최정민 | WF-01–06 P0 revision approved 2026-08-16; styled WF-01/02/03/05/06 screens merged (PR #28); WF-07/08 review required | 2026-08-18 · PR #28 merge `25359ad` · styled routes name their WF IDs with browser/test evidence | Integrated two-session screenshots/recording (#19); WF-07/08 review |
| 8 | Test plan: unit, integration, load and chaos | [Test plan](quality/test-plan.md) | 최정민 and service owners | Plan baseline plus designated-host, private-route, Community projection, result-post and public-record evidence | 2026-08-16 · issue #64 concurrent/replay/order/rebuild/rollback, migration-upgrade, Kafka and public HTTP checks | Full deployed-service activation, integrated UI, load and chaos reports |
| 9 | Deployment plan: CI/CD, procedure and rollback | [Deployment plan](operations/deployment-plan.md) | 윤서진 | Local runtime active; developer account and Judge0 host bound; application deployment pending | 2026-08-14 · full local preflight, CI and Judge0 AWS boundary review | Application images, AWS/OIDC bindings, deploy and rollback rehearsal |
| 10 | Source and README | [README](../README.md), repository and [public contracts](../contracts/README.md) | Team | Executable local contributor baseline | 2026-08-18 · two-session golden-path rehearsal on frozen 25359ad and README refresh | Integrated product behavior, final revision, license decision and release evidence |

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
