# Submission checklist

Owner: 최정민

Status: Operational baseline; final format and rubric confirmation pending

Last verified: 2026-08-13 against the planning PDF and [artifact inventory](../artifact-status.md)

Requirement: D3-DOC-001

Use the [artifact inventory](../artifact-status.md) as the single source for owners and current status. This checklist defines the gates for turning those sources into a truthful submission package.

## Feature evidence gate

- [ ] Each Core 10 `MUST` row in the [internal priority matrix](../specs/d3-mvp.md#core-10--p0-must) links to an assigned issue, implemented behavior and acceptance evidence.
- [ ] Each Optional 10 `SHOULD` row is labelled evidenced, partial or unavailable; partial work is not presented as complete.
- [ ] Scenario A passes end to end in two independent browser sessions without manual database edits.
- [ ] Scenarios B and C prove reconnect/surrender and platform-incident void without duplicate results or rating changes.
- [ ] Six Judge0 language mappings record exact runtime versions and classified smoke results, or the UI identifies a runtime as unavailable.
- [ ] Private source, credentials, tokens and hidden tests are absent from public events, records, logs, screenshots and recordings.

## Required artifact gate

- [ ] All ten rows in the [artifact inventory](../artifact-status.md#required-artifact-inventory) have a final owner, status, commit SHA and evidence link.
- [ ] Requirement priorities, acceptance evidence and implementation status agree with the final issue tracker.
- [ ] Swimlane and Mermaid diagrams render without syntax or clipped-label errors.
- [ ] The final ERD renders and agrees with service-owned Flyway migrations; cross-service IDs are not physical foreign keys.
- [ ] Wireframes have a recorded team review, and each accepted styled surface names WF-01 through WF-08.
- [ ] Test reporting separates pass, fail, skip and not-run, including load/chaos environment bindings.
- [ ] Deployment evidence distinguishes local fallback from actual AWS resources and includes a rollback rehearsal.
- [ ] README start/check commands work from a fresh clone at the frozen revision.

## Architecture and quality gate

- [ ] System, service, data, event and Judge0 boundaries use consistent names across diagrams, contracts and code.
- [ ] PostgreSQL remains authoritative; every Redis key class is expiring and recoverable.
- [ ] Outbox/inbox and aggregate-version evidence demonstrates idempotent result projections.
- [ ] The security review has no unresolved Critical or High finding for the frozen build.
- [ ] Numerical performance claims cite the exact host, build, dataset, sample and raw report; otherwise they remain `UNKNOWN`.

## Presentation and demo gate

- [ ] The [presentation outline](../presentation/outline.md) matches the confirmed time and mandatory slide rules.
- [ ] The [demo runbook](../operations/demo-runbook.md) records the frozen revision, operators, seed, environment and Judge runtime map.
- [ ] Full-stack preflight passes immediately before the timed rehearsal.
- [ ] At least one complete timed rehearsal records deviations and follow-up owners.
- [ ] A local backup recording uses the same verified build and real services and is visibly labelled with revision and capture time.
- [ ] Slides distinguish implemented behavior, planned architecture, measured evidence and future work.

## Packaging and handoff gate

- [ ] Remove secrets, private source, local `.env`, transient logs and unrelated personal data from the package.
- [ ] Record the final source commit, artifact checksums or immutable links, submission time and submitting owner.
- [ ] Re-open every submitted link or archive after upload and verify access from a non-owner context where permitted.
- [ ] Preserve the source package, presentation, recording, evidence reports and submission receipt in the team-owned location.
- [ ] Record final deviations from the PDF and any later rubric clarification instead of silently changing claims.

Exact free-topic scoring beyond the reviewed generic structure, AWS bindings, final performance values and any mandatory upload portal fields remain `UNKNOWN` until supplied.
