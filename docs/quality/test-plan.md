# Test plan

Owner: 최정민 and service owners

Status: Executable local platform with partial Judge adapter evidence; product integration, load and chaos pending

Last verified: 2026-08-14 against D3-QLT-001, issue #13 and #15 test sources, CI scaffold and Judge0 host boundary

Requirement: D3-QLT-001

## Evidence layers

| Layer | Primary risk | Current evidence | Completion evidence | Status |
|---|---|---|---|---|
| Domain unit | Match state, outcome, rating, energy | Active Battle lifecycle, versioned score/rating calculators, fixed one-solver, both-solver, neither-solver, exact-tie, placement, established, unranked and void boundaries, plus deterministic fake and real-Judge normalization slices | Deterministic examples, boundaries and clock control | PARTIAL PASS: Battle outcome, rating and attack domain requirements are active; live calibration evidence remains pending |
| Adapter integration | PostgreSQL, Redis, Kafka, outbox/inbox | Forward-only service migrations run on PostgreSQL Testcontainers; Battle proves Redis TTL/lease coordination, two-user matching, per-player concurrent active-match fencing, idempotent match creation, legacy clock/result restoration, single-snapshot aggregate reads, optimistic lifecycle persistence, transactional READY/SURRENDER replay, PostgreSQL transport-generation allocation, autonomous deadline advancement, generation-fenced Redis retry, cross-instance notification repair, durable Judge evidence, concurrent exactly-once result/rating commit, per-match audit persistence, failed-publication retention and replay-safe Battle outbox dispatch; Judge adds transactional outbox and producer behavior | Real-container transaction, uniqueness and retry evidence | PARTIAL PASS: ranked entry, lifecycle, Judge correlation, result/rating commit and both outbox producers are active; live multi-service and broker-restart evidence remains pending |
| Contract | HTTP, events, WebSocket | Fourteen parseable versioned documents; Battle HTTP/WebSocket v1/v2/v3, Judge HTTP v1, and event-envelope schemas have positive and adversarial samples. Negative samples reject client-owned identity/source, undeclared command types, wrong versions and privacy-unsafe fields | Producer/consumer compatibility and negative samples | PARTIAL PASS: Gateway/Battle WebSocket auth, membership, projection, Judge commands, result events and server-owned connection lifecycle tests are active; Community projection consumers remain incomplete |
| Browser | Ranked golden path and privacy | Playwright drives the approved WF-04 Battle v3 credential boundary through warning, reflect, deterministic overlay and expiry while asserting the controlled editor source never changes; the full Scenario A remains skipped | Two independent sessions with fake judge | PARTIAL PASS: attack source-integrity slice active; full vertical slice and live two-session trace pending |
| Judge host smoke | Runtime mapping and host isolation | Bound zero-ingress host, pinned images, hardened startup and executable sanitized smoke | Real Judge0 cases for six pinned runtimes | PASS: six hello-world plus six deterministic cases; live outage injection NOT RUN |
| Judge application smoke | Real adapter routing, credential and private connectivity | Local HTTP-fixture tests exercise the selectable real adapter path | judge-service calls the designated host over the bound private route for all six runtimes | NOT RUN: private service route PENDING |
| Load | Match fan-out, judge queue, feed reads | Scenario definitions below | Versioned report on designated host | NOT RUN |
| Chaos | Reconnect, broker lag, cache loss, Judge failure | Scenario definitions below | Recovery and no-duplicate assertions | NOT RUN |
| Demo preflight | Full-stack dependency readiness | `pnpm local:start` plus HTTP/TCP checker | All required live dependencies pass on frozen build | PASS on 2026-08-14 working tree; loopback and CORS regressions active; rerun on reviewed revision |

## Reporting contract

Every PR reports the command, revision, environment and separate counts for `PASS`, `FAIL`, `SKIP`, and `NOT RUN`. A disabled skeleton proves only location and requirement mapping. A load or chaos number is valid only for its recorded host, build, dataset, runtime versions and configuration.

For failures, retain the request or correlation ID and sanitized operational signal. Source code, tokens, credentials and hidden tests stay out of reports.

## Issue #13 Judge evidence

| Slice | Current result | What it proves |
|---|---|---|
| Deterministic fake and normalization | PASS | `RUN` public-only behavior, `SUBMIT` hidden/performance behavior, normalized user-code failures and separate platform failure |
| Judge0 HTTP client and adapter | PASS | Authenticated bounded polling, six allowlisted runtime mappings, fixed isolation options, request/response ceilings and provider-status normalization against a local HTTP fixture |
| Judge HTTP resource boundary | PASS | Service caller authorization, validation, idempotent acceptance, safe evidence reads, bounded error responses and request/source rejection |
| PostgreSQL repository | PASS | Idempotent insert/conflict, terminal evidence plus outbox atomicity, private event payload and opaque stale-worker claim fencing on PostgreSQL |
| Kafka outbox publisher | PASS | Acknowledged producer publication before the outbox row is marked published; duplicate delivery remains a consumer inbox concern |
| Production adapter to designated Judge0 | PASS | Issue #59 SSM command `a38944c3-8073-47de-b414-f3bd610acdf8` ran the production `HttpJudge0Client` and `Judge0ExecutionAdapter` from a temporary application-side runner through an exact source-security-group route. All six pinned `RUN` mappings passed, credentials and source were absent from output, and cleanup restored zero Judge0 ingress |
| Deployed Judge HTTP service to designated Judge0 | NOT RUN | Application deployment is outside issue #59 and the current local-only application topology |

These rows summarize narrow test evidence. The issue #13 PR report remains authoritative for exact commands, revision, counts and any skipped scaffold tests.

## Issue #27 authentication evidence

| Slice | Current result | What it proves |
|---|---|---|
| Gateway canonical Identity ingress | PARTIAL PASS | `/api` is stripped once; register, login, refresh and refresh-cookie logout are anonymous while profile and undeclared auth paths still require authentication |
| Judge machine-token validation | PASS | Issuer, Judge audience, Battle client, `token_use=service` and endpoint scope are all required; user-like, wrong-audience and wrong-client tokens fail closed |
| Battle browser WebSocket credential and command | PARTIAL PASS | A mock-decoded user token reaches the unavailable downstream route instead of failing Gateway auth; tests cover credential stripping, ambiguity rejection, exact origin, participant lookup, private two-view projection, ordering, reconnect replay, failed-session isolation, bounded async fan-out, a Redis notification reaching two listener instances, and strict session-bound READY/SURRENDER frames. Live Identity issuance, disconnect generation and two-browser handshake are NOT RUN |
| Browser session cookie and key lifecycle | NOT RUN | Depends on the rebased issue #26 Identity implementation and must prove cookie flags, rotation/revocation and stable demo/deploy signing keys |
| Identity issuance and Battle acquisition | NOT RUN | The short-lived service-token endpoint/client and positive Battle-to-Judge call remain to be integrated after issue #26 |

## Issue #60 match projection evidence

| Slice | Current result | What it proves |
|---|---|---|
| Versioned Kafka consumer | PASS | Production configuration starts a new consumer group at the earliest retained event; a real Kafka delivery of `match.finished.v1` reaches Community and replay converges to one inbox row and one ACTIVE projection |
| PostgreSQL inbox and projection | PASS | Concurrent duplicates and replay are no-ops, seat order and authoritative IDs/version remain traceable, stale events do not regress state, and an authoritative replay rebuilds a quarantined row |
| Transaction rollback | PASS | A forced failure while marking the inbox applied rolls back both the inbox claim and projection write |
| Contract privacy | PASS | Strict parsing rejects missing required scalar fields, unknown private fields, duplicate JSON fields, trailing JSON documents and aggregate/match correlation mismatches before persistence |
| Public record and result post | NOT RUN | User/rating projections, automatic result posts and public HTTP/UI are separate #17 slices and remain pending |

## Critical functional suites

- D3-BTL-002: state transition, authoritative deadline, reconnect boundary, surrender, incident void and duplicate commands.
- D3-BTL-003: one solve, both solve, neither solve, exact tie, repeatable runtime tiers and unknown static evidence.
- D3-BTL-004: energy anti-farming, warning, block, reflect, display-only effects, caret validity and editor undo.
- D3-BTL-005: placement visibility, adjustment-factor boundary, RP/tier separation and exactly-once update.
- D3-ID-001 and D3-SEC-001: explicit OAuth linking, refresh rotation, revocation, object and room authorization.
- D3-JDG-001: accepted, wrong answer, compilation, runtime, timeout, memory and platform failure for each supported runtime.
- D3-COM-001 and D3-STAT-001: code privacy, audience policy, idempotent projection and traceable match record.

## Load plan

Run load tests only on an isolated, designated environment with synthetic users and problems. Bind concurrency targets after the AWS or local demo host profile is known; until then throughput and latency limits are `UNKNOWN`.

| Scenario | Workload steps | Observe | Acceptance binding | Current status |
|---|---|---|---|---|
| Ranked WebSocket fan-out | Ramp paired rooms through small, medium and host-limit stages; send progress and attack events at scripted rates | Connect success, event p50/p95/p99, error/close rate, Battle CPU/memory, Redis ops | No lost committed state; numerical latency budget set after baseline | NOT RUN |
| Judge queue | Submit deterministic public/hidden cases across six languages at staged concurrency | Queue wait, execution p50/p95/p99, timeout/platform-failure rate, host CPU/memory | Correct classification and bounded queue; capacity target after runtime binding | NOT RUN |
| Result projection | Complete ranked matches in bursts and read feed/record until convergence | Outbox age, Kafka consumer lag, projection delay, duplicate count | Every source aggregate converges exactly once; delay budget after baseline | NOT RUN |
| Feed and record reads | Read first page and keyset-paginated history over representative fixtures | HTTP p50/p95/p99, DB pool, query time, errors | No pool exhaustion or deep-offset query; latency budget after dataset binding | NOT RUN |

Each report records ramp shape, duration, warm-up, sample count, dataset size, client location, host CPU/memory, Judge runtime map and raw result location. Do not compare runs that changed these bindings without labeling the change.

## Chaos plan

Chaos tests operate on local or disposable environments only. Each test starts from a known committed match/result and asserts recovery in terms of domain truth, not merely process health.

| Fault | Injection point | Expected invariant | Required evidence | Current status |
|---|---|---|---|---|
| Browser disconnect | Close one session during `RUNNING`, reconnect before and after 30 seconds | Timer continues; early resume restores room; expiry awards opponent once | Both-client events and persisted outcome | NOT RUN |
| Redis restart | Restart ephemeral Redis during queue or active presence | Queue/presence may rebuild; committed match/result remains in PostgreSQL | Recovery log and durable record comparison | NOT RUN |
| Kafka pause or duplicate | Pause delivery, then replay result/profile events | Authoritative result remains; projections converge once using inbox/version | Lag trace and zero duplicate assertion | NOT RUN |
| Judge0 failure | Return timeout/unavailable or terminate adapter connection | Judge classifies platform failure; Battle voids; rating/RP unchanged | Correlation trace and before/after rating | NOT RUN |
| Community restart | Restart consumer between inbox receipt and projection commit | Replay applies the event exactly once | Container integration transaction evidence | NOT RUN |
| Application rollout failure | Make the new revision fail readiness before traffic promotion | Previous verified revision keeps or regains traffic; no incompatible schema rollback | Deployment and rollback log | NOT RUN |

## Exit evidence

Before feature freeze, the issue tracker links every MUST item in the [internal priority matrix](../specs/d3-mvp.md#core-10--p0-must) to its functional test and relevant security review. Before the presentation, the [demo runbook](../operations/demo-runbook.md) records the frozen revision, successful full-stack preflight, Scenario A result, any fallback and the exact load/chaos reports cited on slides.
