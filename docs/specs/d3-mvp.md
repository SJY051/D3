# D³ MVP Specification

Status: Initial baseline  
Product: D³ (Dopamin-Driven Development)  
Development lead: 윤서진  
Delivery window: 2026-08-13 through 2026-08-20

## Context

D³ is an eight-day bootcamp prototype that joins a developer microblog with a competitive programming game. The primary demonstration must show one continuous, working experience rather than disconnected feature screens: a user signs in, enters a ranked match, writes and submits code, receives an authoritative result, and sees the result reflected in the community and searchable match history.

The team has four members and must also produce the required planning, architecture, test, deployment, and presentation artifacts. AWS access is not yet available, so local operation is mandatory and cloud resources remain an activation decision rather than a prerequisite for feature work.

## Goals

- Deliver a demonstrable ranked one-versus-one coding battle as the product spine.
- Reuse the same judging contract for solo practice and ranked play.
- Make rating, seasonal progression, and match history part of the user's public developer identity.
- Demonstrate clear service ownership, synchronous and event-driven communication, isolated persistence, and observable operations.
- Give four developers a consistent repository, contract, verification, and AI-agent workflow.
- Preserve explicit extension points without requiring speculative services in the MVP.

## Non-goals

- Battles with three or more players.
- A production-ready anti-cheat system or globally calibrated rating economy.
- Persistent source-code injection as an attack effect.
- Full mobile code editing, full language-server infrastructure, or passkeys.
- Complete circles, secret posts, polls, notifications, media processing, or problem-authoring suites.
- Kubernetes, a dedicated statistics service, or mandatory S3 and CDN usage.
- Automatic deployment before AWS account, region, quota, and permission constraints are known.

## Users and roles

- **Player:** practices problems, joins matches, submits code, uses attacks, and reviews results.
- **Community member:** publishes developer-oriented posts and explores public profiles and records.
- **Problem operator:** inspects, activates, and makes bounded edits to seeded problems.
- **Team operator:** diagnoses service health and demonstrates the system.

## Product requirements

### D3-ID-001 — Account and identity

The system shall support local email/password registration and GitHub OAuth sign-in. A user shall have one internal identity and separately linked login credentials. Matching email addresses shall not silently merge accounts. After sign-in, the user shall remain authenticated through a short-lived access credential and a revocable rotating refresh session.

### D3-BTL-001 — Ranked and unranked entry

The system shall provide automatic ranked matchmaking and unranked room entry. Ranked matching shall begin with users who selected the same language and have nearby public rating, then widen the rating interval as wait time increases. Invite or public-room matches shall not change rating or seasonal rank.

The MVP shall support C, C++, Java, Python 3, JavaScript, and TypeScript in its language catalog. A ranked MVP match assigns the language selected by the player; a future rule may require a larger language pool at higher tiers.

### D3-BTL-002 — Authoritative real-time match

A match shall progress through explicit server-owned states equivalent to lobby, ready, running, judging, and finished. The server's timestamps and state shall decide deadlines and outcomes. A client may compensate for network round-trip time only when rendering its timer.

Players shall see a masked representation of opponent activity, including structural progress, cursor position, typing activity, and run or submit state, without seeing identifiers and literals during the match.

A disconnected player shall be visibly marked, may reconnect for 30 seconds, and shall lose when that period expires. The match timer continues during disconnection. Surrender shall immediately award the opponent a victory. A confirmed platform or Judge incident shall void the match rather than change rating.

### D3-JDG-001 — Run, submit, and judge

`Run` shall evaluate only public examples and shall not count as a submission attempt. `Submit` shall evaluate hidden tests and increment the attempt count. A correct submission shall lock further submissions for that player.

The judge result shall distinguish accepted, wrong answer, compilation error, runtime error, timeout, memory limit, and platform failure. User-controlled code shall execute without network access and within explicit CPU, wall-time, memory, process/thread, stack, and file-size limits.

The initial problem set shall contain at least one deterministic demonstration problem and six reviewed problems: two Easy, two Medium, and two Hard. Each reviewed problem shall include public examples, hidden correctness cases, size-tier performance cases, and expected-complexity metadata.

### D3-BTL-003 — Match outcome and performance score

When exactly one player solves the problem, that player wins. When both solve it, the outcome shall compare solve speed, efficiency, and submission discipline using an externally configurable initial weighting of 50%, 35%, and 15%. When neither solves it, the outcome shall compare hidden-test progress, efficiency, and submission discipline; an exact tie is a draw.

Efficiency shall combine repeated size-tier runtime measurement with a bounded static complexity heuristic. The initial split is 80% dynamic and 20% static. A low-confidence static result shall be reported as unknown and its weight transferred to dynamic evidence. The product shall not present measured runtime as proof of exact Big-O complexity.

### D3-BTL-004 — Attack interaction

Valid coding progress, first-time public-test progress, and bounded passive gain shall produce attack energy without rewarding delete-and-retype repetition. Language-aware parser or linter evidence may award first-time completion of a syntactically valid line, but client analysis shall never be authoritative for scoring or judging. Attack commands and effects shall be server-authoritative, finite, logged, and replayable for diagnosis.

The MVP shall expose three effects:

- a temporary non-destructive garbage overlay;
- a temporary Caesar-style display veil on selected rows;
- a low-frequency, high-cost caret move to a valid row.

Players shall receive a warning window and may spend energy to block or reflect an attack. Display attacks shall never mutate the stored source. Caret movement may affect subsequent user input but shall preserve normal editor undo behavior.

### D3-BTL-005 — Rating and seasonal rank

After five placement matches, the system shall publicly show a rating, seasonal RP, tier/division, and leaderboard position where applicable. During placement, the visible tier shall remain `Unranked`.

The tier order shall be Bronze, Silver, Gold, Platinum, Diamond, Master, and Grandmaster. Bronze through Diamond shall use divisions III through I. Rating shall use an initial high adjustment factor and a lower established-player factor behind a replaceable calculation boundary. Seasonal RP and tier shall not be treated as the matchmaking rating itself.

### D3-SOLO-001 — Solo practice

A signed-in player shall be able to select a problem and language, run public examples, submit to hidden tests, and retain an accepted private solution. Solo attempts shall use the same language and judge result contracts as battles and shall not affect ranked rating or RP.

### D3-COM-001 — Developer microblog

The community shall provide public and following feeds, profiles, follows, Markdown posts, fenced code blocks, comments, likes, and ranked-result auto-posts. Code blocks shall not count toward the prose character limit. Ranked result summaries shall be public; source code shall remain private unless the user explicitly shares it. Unranked result posts shall be opt-in.

The persistence model shall recognize public, followers, circle, and private visibility, while the MVP user interface is required to complete only public and followers visibility.

### D3-STAT-001 — Searchable developer record

A user shall be searchable by handle. The public record shall show rating, tier, RP, peak tier, win/loss record, win rate, language statistics, recent matches, and match details. Match details shall include score composition, attempts, attack history, and measured execution evidence without revealing private source code.

Community-facing statistics may be eventually consistent, but every displayed projection shall retain an identifier that can be traced to the authoritative identity or battle record.

### D3-ADM-001 — Bounded problem operation

A problem operator shall be able to list seeded problems, inspect their metadata, activate or deactivate them, and perform bounded metadata edits. The MVP need not author hidden tests or full problem packages through the browser. Version-controlled fixtures and database seeds remain the reproducible source of initial problem data.

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

### Scenario D — Solo and community privacy

1. A user solves a problem in solo mode and retains a private accepted solution.
2. A Markdown post with a fenced code block renders correctly without counting code toward prose limits.
3. The source remains absent from public match history until explicitly shared.

## Acceptance criteria

- Scenario A completes end to end with two independent browser sessions and no manual database edits.
- Scenarios B and C produce the specified outcomes without duplicate result or rating records.
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
- AWS account timing, service quotas, and regional availability may require the documented EC2/Compose fallback.
- Judge host load and language runtime variance may distort performance evidence; calibration must be versioned and tied to a designated host profile.
- Four developers and eight calendar days make late P1 work a direct risk to integration, documentation, and rehearsal.
- Caret movement, IME behavior, and masked editor rendering require early browser validation.
- Rating and RP thresholds are demonstrable defaults, not claims of statistical calibration for a small population.
- Static complexity classification can be unknown or wrong; it remains bounded, confidence-labelled evidence rather than authority.

## Open questions

- Exact free-topic scoring and any mandatory presentation structure.
- AWS account, region, IAM boundary, quota, and available managed-service budget.
- Final JWT lifetimes, rating adjustment factors, RP thresholds, and season reset values.
- Final attack costs, cooldowns, reflection cost, and syntax-progress caps after playtesting.
- Final prose character limit and the exact circle/private visibility release boundary.
- Judge host calibration profile and per-language normalization versions.
