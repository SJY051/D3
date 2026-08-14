# Submission artifact status

Owner: 윤서진

Status: Required-source baseline; implementation and final presentation evidence pending

Last verified: 2026-08-14 against the 18-page bootcamp planning PDF, repository sources and bound AWS identity

Requirement: D3-DOC-001

## Required artifact inventory

The planning PDF names the ten artifact classes below. This table is the authoritative repository inventory for owner, status, last verification and missing evidence. `Baseline` means a current source exists; it does not mean implemented behavior, final submission quality or an awarded score.

| # | Required artifact | Repository source | Owner | Status | Last verification point | Evidence still required |
|---:|---|---|---|---|---|---|
| 1 | Requirements specification: core/optional, priority and MUST/SHOULD | [MVP specification](specs/d3-mvp.md#internal-scoring-priority-plan) | 윤서진 | Baseline | 2026-08-14 · PDF/source and AWS activation-gate cross-check | Implemented evidence per matrix item; final rubric confirmation |
| 2 | Workflow Swimlane | [Ranked workflow](architecture/workflow.md) | 최정민 | Target baseline | 2026-08-13 · scenarios and contracts cross-check | Render review and Scenario A/B/C runtime traces |
| 3 | System architecture | [System context](architecture/system-context.md) | 윤서진 | Baseline | 2026-08-13 · boundary source review | Running topology and final deployment substitutions |
| 4 | Service architecture | [Service boundaries](architecture/services.md) | 윤서진 | Local platform active; product services partial | 2026-08-14 · Gateway/config/discovery and service ownership review | Implemented product endpoints, events and authorization evidence |
| 5 | Service database ERD | [Logical ERD](architecture/erd.dbml) | Service owners | Logical baseline: 26 tables, 14 intra-service refs; four Flyway V1 schemas | 2026-08-14 · logical/physical migration and Testcontainers review | Rendered diagram and feature query-plan evidence |
| 6 | Cloud architecture | [Cloud architecture](architecture/cloud.md) | 윤서진 | Account/region and Judge0 host bound; application services pending | 2026-08-14 · STS, EC2/IAM/network and six-runtime review | Application IAM, quota and deployed-resource evidence |
| 7 | Wireframes and functions | [WF-01 through WF-08](wireframes/README.md) | 최정민 | Review required | 2026-08-13 · route/requirement mapping review | Team sign-off and styled-screen linkage/screenshots |
| 8 | Test plan: unit, integration, load and chaos | [Test plan](quality/test-plan.md) | 최정민 and service owners | Plan baseline | 2026-08-13 · test/CI source review | Functional activation, designated host, load and chaos reports |
| 9 | Deployment plan: CI/CD, procedure and rollback | [Deployment plan](operations/deployment-plan.md) | 윤서진 | Local runtime active; developer account and Judge0 host bound; application deployment pending | 2026-08-14 · full local preflight, CI and Judge0 AWS boundary review | Application images, AWS/OIDC bindings, deploy and rollback rehearsal |
| 10 | Source and README | [README](../README.md), repository and [public contracts](../contracts/README.md) | Team | Executable local contributor baseline | 2026-08-14 · local start/preflight and repository command review | Integrated product behavior, final revision, license decision and release evidence |

## Supporting presentation sources

- [Presentation outline](presentation/outline.md): narrative baseline; exact mandatory order remains `UNKNOWN`.
- [Demo runbook](operations/demo-runbook.md): rehearsal and recovery procedure; live Scenario A and recording are pending.
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
