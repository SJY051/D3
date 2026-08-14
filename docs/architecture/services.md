# Service boundaries

Owner: 윤서진

Status: Local platform active; Judge boundary partially implemented

Last verified: 2026-08-14 against the MVP system constraints and public contract inventory

| Boundary | Owns | Does not own | Initial caller or consumer |
|---|---|---|---|
| Identity | Accounts, login identities, refresh sessions, public profile | Rating, posts, matches | Gateway; community profile projection |
| Battle | Problems, queue decisions, rooms, outcomes, rating, RP, tiers | Source execution, login credentials, posts | Gateway; judge result events |
| Judge | Submission jobs, execution evidence, static/dynamic evaluation | Match winner, rating, public source | Battle job request; Judge0 |
| Community | Posts, follows, comments, likes, result/profile projections | Authoritative rating or identity | Gateway; Kafka events |

The API gateway uses the reactive Spring Cloud Gateway variant so HTTP and battle WebSocket traffic can share one ingress boundary. Domain services remain conventional Spring MVC applications unless their own requirements justify otherwise.

Gateway authentication does not replace service authorization. Every domain service validates the bearer credential at its own boundary and enforces ownership or role checks for the requested object; the judge boundary additionally rejects non-service callers.

## Communication rules

- Battle requests judge job acceptance synchronously through a versioned client boundary, then persists the returned stable submission ID with its match, player, attempt, `RUN`/`SUBMIT` mode and stable command ID before acknowledging acceptance. The job row is the sole accepted-submission correlation: only a positive-attempt `SUBMIT` may become accepted, and at most one accepted submit exists per match player. Distinct `RUN` commands receive distinct command IDs without incrementing the submission attempt; retries reuse the original ID.
- Judge publishes `submission.judged.v1` after durable evaluation.
- Battle publishes `match.finished.v1` and `rating.changed.v1` from an outbox after committing the result.
- Battle commits each player's named speed, dynamic-efficiency and submission-discipline score components plus the calculation/weight version atomically with the final total and result; later configuration changes never rewrite that historical explanation.
- Identity publishes `user-profile.changed.v1` for consumer-owned projections.
- Every consumer records `eventId` or an equivalent inbox key before applying an event.
- A projection may be stale, but it must retain the authoritative aggregate ID and version.

## Contract activation state

Issue #11 activates the local platform boundary: Config Server serves the versioned `local-v1` profile, Eureka registers the Gateway and four domain services, and Gateway declares only Identity, Battle HTTP/WebSocket and Community browser routes. Judge remains an internal service boundary and has no browser route. Gateway health is public, other ingress requires a bearer token, and a bounded `X-Correlation-Id` is preserved or generated at ingress. Identity, Battle and Community deny non-health requests until their product security contracts are implemented.

Each domain database now has a service-owned forward-only Flyway chain aligned with this document's logical ERD. Previously applied V1 migrations remain immutable; Identity, Battle and Community reach the normalized model through V2, while Judge preserves V1/V2 and strengthens its model through V3. Upgrade normalization never silently discards a V1-valid row: ambiguous refresh lineages, accepted-submission pointers, duplicate judge correlations, legacy null attempt numbers and duplicate runtime evidence retain an explicit legacy record, while an untypable Community projection remains as a `REBUILD_REQUIRED` placeholder referenced by existing posts and its original payload enters a rebuild queue for authoritative event replay. A legacy Judge SUBMIT with no attempt is assigned a deterministic positive attempt before it can enter the active dispatcher, preventing an invalid row from starving the bounded queue. Battle constraints that cannot be inferred safely for every historical row are installed `NOT VALID`, which still enforces all new writes; a later evidence-backed cleanup migration must validate them. Identity keeps new refresh-token rotation inside one user's single-use lineage. Battle derives new accepted-submission state from its owned judge-job correlation instead of maintaining a second pointer. Community stores valid match seats as distinct typed UUIDs and reconstructs the public event array at its boundary. Container tests prove both fresh installation and adversarial existing-data upgrade; Battle additionally proves Redis and Kafka connectivity. These migrations provide persistence boundaries, not completed Identity, Battle or Community behavior.

Judge HTTP v1 has service-authenticated `RUN`/`SUBMIT` handlers, idempotent acceptance with a stable submission ID, and a bounded evidence read containing the minimum correctness and repeated size-tier runtime summary required by Battle. Judge persists the private command and safe evidence in its PostgreSQL database, fences asynchronous evaluation claims, and commits the terminal evidence with a `submission.judged.v1` outbox record before Kafka publication. Public responses and events omit source, hidden cases, provider credentials, compiler commands, and raw diagnostics.

The deterministic fake is the local default and provides repeatable normalized-result evidence without representing host execution as live. An explicitly selected real adapter maps the six supported language keys to pinned Judge0 runtime IDs, applies fixed resource and network options, and normalizes provider results behind the same application seam. The real adapter path has narrow HTTP and normalization tests; judge-service-to-AWS execution over the intended private route is **NOT RUN**.

Issue #13 therefore provides partial vertical-slice evidence, not an end-to-end Judge claim. The source-security-group-only AWS path and designated-host application smoke remain **PENDING**, and Battle still must persist its accepted submission correlation and consume judged results under issue #15. Scenario A remains incomplete until those integrations have producer/consumer and live-path evidence.

The current v1 event inventory is sufficient for a basic match projection, but not the complete P0 public rating projection. `match.finished.v1` defines seat-ordered player IDs, but omits score composition, attempts, attack history, and execution evidence. `rating.changed.v1` has an unconstrained tier string and omits an independently defined division as well as leaderboard position, language statistics, and peak tier. `user-profile.changed.v1` omits display name. Consequently, division display requires a compatible structured representation or new versioned boundary, and the target enriched Community projection fields in `erd.dbml` cannot all be populated from the current schemas.

Before an enriched Community projection is implemented, approve one of these versioned boundaries:

- a new event version carrying the minimum privacy-reviewed summary; or
- a bounded versioned read API/read model owned by the authoritative service and keyed by the event's aggregate ID and version.

Community stores the returned data in its own database. Cross-service tables, foreign keys, entities, and database queries remain prohibited. Existing v1 stubs are not completion evidence for the enriched record.

## Persistence rules

- Follow `docs/architecture/postgresql.md` for schema, indexing, transaction, pool, and migration rules.
- One local PostgreSQL cluster may host separate databases, but each service uses a separate role and credentials.
- Flyway migrations live with the service that owns the database.
- Cross-service database queries, foreign keys, and shared entity libraries are outside the contract.
- Redis values require a TTL and may be rebuilt or discarded without losing a committed result.
