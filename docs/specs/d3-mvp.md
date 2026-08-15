# D³ MVP Specification

Status: Initial baseline

Product: D³ (Dopamin-Driven Development)

Development lead: 윤서진

Owner: 윤서진

Delivery window: 2026-08-13 through 2026-08-20

Last verified: 2026-08-14 against the planning PDF, agreed product decisions and issue #15 evidence

## Context

D³ is an eight-day bootcamp prototype that joins a developer microblog with a competitive programming game. The primary demonstration must show one continuous, working experience rather than disconnected feature screens: a user signs in, enters a ranked match, writes and submits code, receives an authoritative result, and sees the result reflected in the community and searchable match history.

The team has four members and must also produce the required planning, architecture, test, deployment, and presentation artifacts. The assigned AWS account and Seoul region are available, but local operation remains mandatory and cloud resources remain an activation decision rather than a prerequisite for feature work.

## Goals

- Deliver a demonstrable ranked one-versus-one coding battle as the product spine.
- Reuse the same judging contract for solo practice and ranked play.
- Make rating, seasonal progression, and match history part of the user's public developer identity.
- Demonstrate clear service ownership, synchronous and event-driven communication, isolated persistence, and observable operations.
- Give four developers a consistent repository, contract, verification, and AI-agent workflow.
- Preserve explicit extension points without requiring speculative services in the MVP.

## Delivery priority

- **P0 / MUST:** required for the integrated demonstration and feature-freeze acceptance. A P0 clause is normative for this prototype.
- **P1 / SHOULD:** retained in the product contract but activated only after the P0 path remains integrated. An unavailable P1 surface stays behind a feature boundary and does not block P0 acceptance.

The Core 10 below are the normative P0 outcomes. The Optional 10 are P1 outcomes; their requirement clauses use explicit P1 activation language rather than unconditional MVP language.

## Internal scoring-priority plan

The bootcamp planning PDF describes a generic planning shape of ten core items at seven points each and ten optional items at three points each. It does not provide a separate free-topic rubric or prescribe the D³ feature names below. This table mirrors that shape only as an **internal delivery and evidence priority**: `MUST` protects the continuous demonstration path, while `SHOULD` is attempted only after the required path remains integrated. The numbers are planning weights, not a claim of awarded points.

### Core 10 — P0 MUST

| ID | Weight | Judge-readable outcome | Requirement source | Required evidence | Current evidence |
|---|---:|---|---|---|---|
| M-01 | 7 | A user registers, signs in, refreshes and revokes a local account session | D3-ID-001 | Auth integration and negative authorization tests | PASS: Identity register/login/rotating refresh/logout/profile tests, canonical Gateway flow, HttpOnly cookie profile policy, stable deploy key enforcement and user `identity.profile battle.play` issuance are active |
| M-02 | 7 | Two users selecting the same language enter one ranked match | D3-BTL-001 | Two-client matchmaking test and queue trace | PARTIAL PASS: deterministic widening-window policy, Redis TTL coordination, authenticated HTTP contract, idempotent PostgreSQL match creation, concurrent single-active-match fencing and Identity `battle.play` issuance have active tests; live two-browser queue trace remains PENDING |
| M-03 | 7 | Both clients follow one server-owned clock and match lifecycle, including reconnect, surrender and incident void | D3-BTL-002 | Deterministic state tests plus two-session evidence | PARTIAL PASS: lifecycle, reconnect and match-deadline ordering, surrender, incident void, legacy snapshot normalization, single-statement PostgreSQL reads, optimistic persistence, authenticated participant-only outbound WebSocket fan-out, latest-snapshot replay, closed READY/SURRENDER commands, stale-session command rejection, server-owned transport-generation fencing and an autonomous PostgreSQL-backed deadline driver have active tests; live two-session evidence remains PENDING |
| M-04 | 7 | Run and Submit use an isolated Judge0 boundary with six explicit language mappings and classified failures | D3-JDG-001 | Six runtime smokes and failure normalization tests | PARTIAL PASS: authenticated Judge HTTP v1, deterministic fake, real adapter path, failure normalization and durable evidence/outbox are implemented in #13; Battle WebSocket v3 forwards private RUN/SUBMIT source only under short-lived machine tokens, persists stable submission correlations, retries early judged events through its inbox and reads safe evidence exactly once. The dedicated Judge0 CE 1.13.1 host passed its pinned six-runtime and isolation smoke, while the application-to-host private-path smoke is NOT RUN |
| M-05 | 7 | A committed result explains speed, dynamic efficiency and submission discipline without claiming measured Big-O | D3-BTL-003 | Formula examples, boundary tests and repeatability sample | PARTIAL PASS: Battle transactionally consumes versioned Judge evidence, applies the externally configurable 50/35/15 formula, persists named score components and evidence versions, and fixes one-solve, both-solve, neither-solve and exact-tie boundaries. Designated-host calibration and an integrated repeatability sample remain PENDING |
| M-06 | 7 | Players exchange at least one warned, reversible, server-authoritative attack without mutating stored source | D3-BTL-004 | Browser/editor test, event trace and source-integrity assertion | PARTIAL PASS: PR #50 adds warned launch, one-hop block/reflect, deterministic expiry, replayable PostgreSQL attack events, authoritative energy grants and versioned WebSocket v3 attack commands/snapshots. The approved WF-04 route now converts the short-lived session token into the v3 WebSocket credential protocol, renders warning/block/reflect states and deterministic display-only garbage, and has Playwright evidence that the controlled source remains byte-for-byte unchanged through warning, reflection, activation and expiry. Result/rating/outbox linkage is active; a live two-browser event trace remains PENDING |
| M-07 | 7 | A ranked result updates public rating and separate seasonal RP/tier exactly once | D3-BTL-005 | Rating/RP tests and idempotent persistence evidence | PASS at the service boundary: placement/established factors, separate RP, five-match `Unranked` boundary, tier/division mapping and void/unranked no-op have fixed tests; concurrent PostgreSQL claims prove one atomic result/rating commit, immutable per-match audit fields, and replay-safe `rating.changed.v1` outbox publication |
| M-08 | 7 | A developer feed publishes Markdown with fenced code while preserving visibility and source privacy | D3-COM-001 | Rendering, character-count, audience and privacy tests | Route/wireframe scaffold; not implemented |
| M-09 | 7 | The committed ranked result creates a public result post and traceable searchable match record | D3-STAT-001 | Outbox/inbox replay plus UI trace to match ID | PARTIAL PASS: Battle atomically emits replay-safe `match.finished.v1` and `rating.changed.v1` records with stable match correlation; Community projections and the public UI trace remain PENDING |
| M-10 | 7 | The team rehearses one deterministic end-to-end build with preflight, observability and a labeled backup | D3-UX-002, D3-QLT-001, D3-SEC-001 | Frozen revision, live Scenario A, security review and recording | Preflight/runbook baseline; live evidence not run |

### Optional 10 — P1 SHOULD

| ID | Weight | Judge-readable outcome | Requirement source | Activation evidence | Current evidence |
|---|---:|---|---|---|---|
| S-01 | 3 | A local account explicitly links GitHub OAuth without email-based silent merge | D3-ID-001 | OAuth state/linking integration tests | Planned; not implemented |
| S-02 | 3 | Invite or public rooms run without changing ranked rating or RP | D3-BTL-001 | Unranked room test with zero rating mutation | Planned; not implemented |
| S-03 | 3 | Solo practice reuses the judge pipeline and retains a private accepted solution | D3-SOLO-001 | Solo run/submit and privacy test | Route/wireframe scaffold; not implemented |
| S-04 | 3 | Following feed, follows, comments and likes extend the public microblog | D3-COM-001 | Audience and interaction integration tests | Logical ERD only; not implemented |
| S-05 | 3 | Player records add language statistics, peak tier, leaderboard position and detailed score evidence | D3-STAT-001 | Projection and record UI tests | Wireframe/contract baseline only |
| S-06 | 3 | Operators manage a reviewed six-problem catalog through bounded metadata controls | D3-JDG-001, D3-ADM-001 | Six fixture reviews, role tests, and reproducibility evidence | Route/wireframe scaffold; not implemented |
| S-07 | 3 | Low-confidence static complexity evidence is labelled unknown and transfers weight to runtime evidence | D3-BTL-003 | Confidence-boundary and fallback tests | Algorithm and calibration `UNKNOWN` |
| S-08 | 3 | The full attack set adds Caesar veil and low-frequency caret move with block or reflect | D3-BTL-004 | Cross-browser editor/undo and balance evidence | Planned; not implemented |
| S-09 | 3 | Opponent activity adds safe structural progress, cursor and typing signals without identifiers or literals | D3-BTL-002 | Redaction contract and browser privacy tests | Contract shape pending |
| S-10 | 3 | Circle/private audience UI and opt-in unranked result posts activate over the already recognized persistence audiences | D3-COM-001 | Visibility migration and authorization tests | Logical model only; UI outside P0 |

An item moves from planned to evidenced only when its named behavior and verification both exist. Partial implementation, a route shell, a skipped test or an architecture diagram remains partial evidence rather than a completed feature.

## Non-goals

- Battles with three or more players.
- A production-ready anti-cheat system or globally calibrated rating economy.
- Persistent source-code injection as an attack effect.
- Full mobile code editing, full language-server infrastructure, or passkeys.
- Complete circles, secret posts, polls, notifications, media processing, or problem-authoring suites.
- Kubernetes, a dedicated statistics service, or mandatory S3 and CDN usage.
- Automatic deployment before AWS IAM, quota, resource, and approval constraints are known.

## Users and roles

- **Player:** practices problems, joins matches, submits code, uses attacks, and reviews results.
- **Community member:** publishes developer-oriented posts and explores public profiles and records.
- **Problem operator:** inspects, activates, and makes bounded edits to seeded problems.
- **Team operator:** diagnoses service health and demonstrates the system.

## Product requirements

### D3-ID-001 — Account and identity

**P0:** The system shall support local email/password registration. A user shall have one internal identity, and after sign-in shall remain authenticated through a short-lived access credential and a revocable rotating refresh session.

**P1:** When GitHub OAuth is activated, it shall use a separately linked login identity. Matching email addresses shall not silently merge accounts.

### D3-BTL-001 — Ranked and unranked entry

**P0:** The system shall provide automatic ranked matchmaking. Ranked matching shall begin with users who selected the same language and have nearby public rating, then widen the rating interval as wait time increases.

**P0:** The language catalog shall support C, C++, Java, Python 3, JavaScript, and TypeScript. A ranked match assigns the language selected by the player; a future rule may require a larger language pool at higher tiers.

**P1:** When unranked invite or public rooms are activated, their matches shall not change rating or seasonal rank.

### D3-BTL-002 — Authoritative real-time match

**P0:** A match shall progress through explicit server-owned states equivalent to lobby, ready, running, judging, and finished. The server's timestamps and state shall decide deadlines and outcomes. A client may compensate for network round-trip time only when rendering its timer.

**P0:** Players shall see opponent connectivity and masked run or submit state without receiving opponent identifiers, literals, or source during the match.

**P0:** A disconnected player shall be visibly marked, may reconnect for 30 seconds, and shall lose when that period expires. The match timer continues during disconnection. Surrender shall immediately award the opponent a victory. A confirmed platform or Judge incident shall commit the domain void outcome without changing rating; `match.finished.v1.result` serializes that outcome as `VOIDED`.

**P1:** When detailed opponent activity is activated, it shall add redacted structural progress, cursor position, and typing activity without exposing identifiers, literals, or source.

### D3-JDG-001 — Run, submit, and judge

**P0:** `Run` shall evaluate only public examples and shall not count as a submission attempt. `Submit` shall evaluate hidden tests and increment the attempt count. A correct submission shall lock further submissions for that player.

**P0:** The judge result shall distinguish accepted, wrong answer, compilation error, runtime error, timeout, memory limit, and platform failure. User-controlled code shall execute without network access and within explicit CPU, wall-time, memory, process/thread, stack, and file-size limits.

**P0:** The initial problem set shall contain at least one deterministic demonstration problem with public examples, hidden correctness cases, and size-tier performance cases.

**P1:** When the reviewed catalog is activated, it shall add six problems—two Easy, two Medium, and two Hard—with public examples, hidden correctness cases, size-tier performance cases, and expected-complexity metadata.

### D3-BTL-003 — Match outcome and performance score

**P0:** When exactly one player solves the problem, that player wins. When both solve it, the outcome shall compare solve speed, dynamically measured efficiency, and submission discipline using an externally configurable initial weighting of 50%, 35%, and 15%. When neither solves it, the outcome shall compare hidden-test progress, dynamic efficiency, and submission discipline; an exact tie is a draw.

**P0:** Dynamic efficiency shall use repeated size-tier runtime measurement. The product shall not present measured runtime as proof of exact Big-O complexity.

**P1:** When static complexity evidence is activated, efficiency shall use an initial 80% dynamic and 20% bounded-static split. A low-confidence static result shall be reported as unknown and its weight transferred to dynamic evidence.

### D3-BTL-004 — Attack interaction

**P0:** First-time public-test progress and bounded passive gain shall produce attack energy without rewarding repetition. Attack commands and effects shall be server-authoritative, finite, logged, and replayable for diagnosis.

**P0:** The battle shall expose a temporary non-destructive garbage overlay. Players shall receive a warning window and may spend energy to block or reflect the attack. The display effect shall never mutate stored source.

**P1:** When advanced progress analysis is activated, valid coding progress and language-aware parser or linter evidence may award first-time completion of a syntactically valid line; client analysis remains non-authoritative for scoring and judging.

**P1:** The advanced attack set shall add a temporary Caesar-style display veil on selected rows and a low-frequency, high-cost caret move to a valid row. The veil shall not mutate stored source. Caret movement may affect subsequent input but shall preserve normal editor undo behavior. Both effects use the warning, block, and reflect rules.

### D3-BTL-005 — Rating and seasonal rank

**P0:** After five placement matches, the system shall publicly show a rating, seasonal RP, and tier/division. During placement, the visible tier shall remain `Unranked`.

**P0:** The tier order shall be Bronze, Silver, Gold, Platinum, Diamond, Master, and Grandmaster. Bronze through Diamond shall use divisions III through I. Rating shall use an initial high adjustment factor and a lower established-player factor behind a replaceable calculation boundary. Seasonal RP and tier shall not be treated as the matchmaking rating itself.

**P1:** When leaderboard enrichment is activated, the public record shall add leaderboard position, language statistics, and peak-tier details.

### D3-SOLO-001 — Solo practice

**P1:** When solo practice is activated, a signed-in player shall be able to select a problem and language, run public examples, submit to hidden tests, and retain an accepted private solution. Solo attempts shall use the same language and judge result contracts as battles and shall not affect ranked rating or RP.

### D3-COM-001 — Developer microblog

**P0:** The community shall provide a public feed, profiles, Markdown posts, fenced code blocks, and ranked-result auto-posts. Code blocks shall not count toward the prose character limit. Ranked result summaries shall be public; source code shall remain private unless the user explicitly shares it.

**P0:** The persistence model shall recognize public, followers, circle, and private audiences. The P0 interface is required to expose only public visibility.

**P1:** When social interactions are activated, the community shall add a following feed, follows, comments, and likes. Circle/private audience controls and opt-in unranked result posts are also P1 surfaces.

### D3-STAT-001 — Searchable developer record

**P0:** A user shall be searchable by handle. The public record shall show rating, tier, RP, win/loss record, win rate, recent matches, and a traceable basic match result without revealing private source code.

**P0:** Community-facing statistics may be eventually consistent, but every displayed projection shall retain an identifier and source version that can be traced to the authoritative identity or battle record.

**P1:** When record enrichment is activated, the public record shall add peak tier, leaderboard position, language statistics, score composition, attempts, attack history, and measured execution evidence.

### D3-ADM-001 — Bounded problem operation

**P1:** When problem operation is activated, an authorized operator shall be able to list seeded problems, inspect their metadata, activate or deactivate them, and perform bounded metadata edits. The interface need not author hidden tests or full problem packages. Version-controlled fixtures and database seeds remain the reproducible source of initial problem data.

## Experience requirements

### D3-UX-001 — Wireframe-first visual system

Low-fidelity wireframes shall exist and be reviewed before styled versions of sign-in, feed, solo practice, matchmaking, battle, result, record, and problem-operation surfaces are accepted. Styled screens shall identify the wireframe surface they implement.

The visual system shall be dark-first, information-dense, and use restrained electric accents. The project shall replace default component-library styling with D³ semantic tokens. Decorative gradients, glass effects, repetitive card containers, and non-functional promotional copy require an explicit product reason during visual review.

Community and record surfaces shall remain usable at a 360 CSS-pixel viewport. Active coding battle is optimized for a 1280 CSS-pixel desktop viewport; smaller layouts may provide read-only status, spectating, and results instead of the full editor. Current Chrome and Edge are the primary demonstration targets. Critical controls shall be keyboard reachable and shall not communicate state by color alone.

### D3-UX-002 — Deterministic demonstration path

The project shall provide seeded users, a fixed demonstration problem, and a deterministic attack configuration for rehearsal. The primary presentation shall use real application services and the real judge. A preflight check and a clearly identified local backup recording shall cover infrastructure failure without representing mocked behavior as live behavior.

## System constraints

- Identity, battle, judge, and community are independently owned service boundaries. Gateway, configuration, and discovery are supporting applications.
- Each domain service owns its database and credentials. Cross-service database access and cross-service foreign keys are prohibited.
- PostgreSQL is authoritative for durable records. Redis is limited to expiring matchmaking, presence, reconnect, snapshot, and fan-out data.
- Synchronous judge job acceptance and asynchronous judged/match/profile projections shall have versioned contracts. Critical producer events require an atomic outbox boundary; consumers require idempotency.
- The frontend consumes versioned HTTP, WebSocket, and event-derived contracts; private implementation models are not shared as public contracts.
- Local development shall operate before AWS is available. Cloud design targets container services, managed PostgreSQL, managed Redis, managed Kafka, isolated Judge0 compute, and optional object storage/CDN activation.
- Incomplete P1 surfaces shall remain unavailable rather than appearing as empty or mocked production features.

## Delivery and quality requirements

### D3-QLT-001 — Observable completion

A P0 change is complete only when its linked acceptance scenario, relevant automated test, error behavior, operational signal, and affected contract documentation are present. Placeholder behavior tests shall remain explicitly skipped and shall not count as passing behavior.

Critical domain rules shall have deterministic unit tests. Persistence and broker adapters shall have container-backed integration tests. HTTP, WebSocket, and event contracts shall be validated. The golden path shall have an automated browser test using the fake judge and a separate real-Judge smoke test.

### D3-SEC-001 — Security gate

Authentication, authorization, WebSocket room access, OAuth linking, judge submission boundaries, secrets, event trust boundaries, container images, and cloud permissions shall receive a targeted review before feature freeze. Unresolved Critical or High findings block merge unless the finding is shown to be inapplicable with recorded evidence.

### D3-DOC-001 — Submission evidence

The repository shall retain current sources for requirements, workflow, system architecture, service architecture, ERD, cloud architecture, wireframes, test plan, deployment plan, and source/README evidence. Each artifact shall identify its owner, state, and last verification point.

## Observable scenarios

### Scenario A — Ranked golden path

1. Two seeded users sign in and select the same supported language.
2. Matchmaking creates a ranked match and both users become ready.
3. Both clients render the same authoritative start and deadline.
4. Players type, run public examples, gain bounded energy, and exchange at least one reversible attack.
5. A player submits a correct solution; hidden judging completes and the submission locks.
6. The match finishes, rating and RP changes are recorded, and both clients receive the result.
7. A ranked-result post and searchable match record appear from the committed result.

### Scenario B — Reconnect and surrender

1. A player disconnects during a running match and the opponent sees the disconnected state.
2. Reconnection within 30 seconds restores the authoritative room and latest private snapshot.
3. A second match demonstrates timeout loss or explicit surrender and records the expected ranked result.

### Scenario C — Platform incident

1. The judge adapter returns a classified platform failure rather than a user-code failure.
2. The battle is voided, neither player's rating or RP changes, and an operator can trace the incident through correlation identifiers and health signals.

### Scenario D — P0 community formatting and privacy

1. A Markdown post with a fenced code block renders correctly without counting code toward prose limits.
2. The source remains absent from public match history until explicitly shared.

### Scenario E — P1 solo practice

1. When the solo feature boundary is activated, a user solves a problem in solo mode and retains a private accepted solution without changing rating or RP.

## Acceptance criteria

- Scenario A completes end to end with two independent browser sessions and no manual database edits.
- Scenarios B and C produce the specified outcomes without duplicate result or rating records.
- Scenario D verifies the P0 Markdown character-count and private-source boundaries; Scenario E is required only when its P1 feature boundary is activated.
- Every supported language has a mapped judge runtime, a verified hello-world smoke case, and an explicit unavailable state when its runtime is unhealthy.
- The demonstration problem produces repeatable correctness and scoring results within documented tolerance on the designated judge host.
- Public record data can be traced to committed source events and does not expose accepted source by default.
- A fresh developer can start the default local infrastructure profile, run the fake-judge golden test, and discover all required environment keys without receiving a secret from version control.
- The wireframe review precedes styled-screen acceptance, and every P0 surface has an identified wireframe source.
- Required repository tests distinguish implemented behavior, skipped skeletons, failures, and infrastructure-dependent smoke tests.
- The preflight check reports a clear pass or failure for web, gateway, domain services, broker, databases, Redis, and the selected judge adapter.
- All ten submission artifact sources are present and have an owner and status by feature freeze.

## Dependencies and risks

- The free-topic scoring rubric is not yet available; score mapping may need revision without changing the golden path.
- IAM permissions, service quotas, and regional service availability may require the documented EC2/Compose fallback.
- Judge host load and language runtime variance may distort performance evidence; calibration must be versioned and tied to a designated host profile.
- Four developers and eight calendar days make late P1 work a direct risk to integration, documentation, and rehearsal.
- Caret movement, IME behavior, and masked editor rendering require early browser validation.
- Rating and RP thresholds are demonstrable defaults, not claims of statistical calibration for a small population.
- Static complexity classification can be unknown or wrong; it remains bounded, confidence-labelled evidence rather than authority.

## Open questions

- Exact free-topic scoring and any mandatory presentation structure.
- AWS IAM boundary, quota, and available managed-service budget in the assigned account and Seoul region.
- Final JWT lifetimes, rating adjustment factors, RP thresholds, and season reset values.
- Final attack costs, cooldowns, reflection cost, and syntax-progress caps after playtesting.
- Final prose character limit and the exact circle/private visibility release boundary.
- Judge host calibration profile and per-language normalization versions.
