# Test plan

Owner: 최정민 and service owners

Status: Executable local platform with partial Judge adapter evidence; product integration, load and chaos pending

Last verified: 2026-08-14 against D3-QLT-001, issue #13 and #15 test sources, CI scaffold and Judge0 host boundary

Requirement: D3-QLT-001

## Evidence layers

| Layer | Primary risk | Current evidence | Completion evidence | Status |
|---|---|---|---|---|
| Domain unit | Match state, outcome, rating, energy | Active Battle lifecycle plus deterministic fake and real-Judge normalization slices; remaining requirement skeletons disabled | Deterministic examples, boundaries and clock control | PARTIAL PASS: active Judge/Battle slices plus remaining skips |
| Adapter integration | PostgreSQL, Redis, Kafka, outbox/inbox | Forward-only service migrations run on PostgreSQL Testcontainers; Battle proves Redis TTL/lease coordination, two-user matching, per-player concurrent active-match fencing, idempotent match creation, legacy clock/result restoration, single-snapshot aggregate reads, optimistic lifecycle persistence and transactional command replay; Judge adds transactional outbox and producer behavior | Real-container transaction, uniqueness and retry evidence | PARTIAL PASS: ranked entry and command persistence plus Judge producer active; Battle terminal outbox and realtime adapters pending |
| Contract | HTTP, events, WebSocket | Eleven parseable versioned documents; the original Battle event v1 is preserved and the closed participant snapshot is v2; Judge HTTP behavior, Battle ranked-queue auth/identity/error behavior, Gateway canonical Identity ingress and browser WebSocket credential conversion have active tests | Producer/consumer compatibility and negative samples | PARTIAL PASS: Gateway/Battle outbound WebSocket auth, membership and projection tests are active; Identity issuance, client commands and Battle event consumers remain incomplete |
| Browser | Ranked golden path and privacy | Skipped Playwright Scenario A | Two independent sessions with fake judge | SKIP: vertical slice absent |
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
| Full application to designated Judge0 | NOT RUN | Requires the PENDING private Judge-service route and deployment egress binding; issue #14 host smoke is not a substitute |

These rows summarize narrow test evidence. The issue #13 PR report remains authoritative for exact commands, revision, counts and any skipped scaffold tests.

## Issue #27 authentication evidence

| Slice | Current result | What it proves |
|---|---|---|
| Gateway canonical Identity ingress | PARTIAL PASS | `/api` is stripped once; register, login, refresh and refresh-cookie logout are anonymous while profile and undeclared auth paths still require authentication |
| Judge machine-token validation | PASS | Issuer, Judge audience, Battle client, `token_use=service` and endpoint scope are all required; user-like, wrong-audience and wrong-client tokens fail closed |
| Battle browser WebSocket credential | PARTIAL PASS | A mock-decoded user token reaches the unavailable downstream route instead of failing Gateway auth; unit tests cover credential stripping, ambiguity rejection, exact origin, participant lookup, private two-view projection, ordering, reconnect replay and failed-session isolation. Live Identity issuance and two-browser handshake are NOT RUN |
| Browser session cookie and key lifecycle | NOT RUN | Depends on the rebased issue #26 Identity implementation and must prove cookie flags, rotation/revocation and stable demo/deploy signing keys |
| Identity issuance and Battle acquisition | NOT RUN | The short-lived service-token endpoint/client and positive Battle-to-Judge call remain to be integrated after issue #26 |

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
