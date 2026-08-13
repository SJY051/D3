# Service boundaries

Owner: 윤서진

Status: Initial baseline

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

- Battle requests judge job acceptance synchronously through a versioned client boundary, then persists the returned stable submission ID with its match, player, attempt, `RUN`/`SUBMIT` mode and stable command ID before acknowledging acceptance. Distinct `RUN` commands receive distinct command IDs without incrementing the submission attempt; retries reuse the original ID.
- Judge publishes `submission.judged.v1` after durable evaluation.
- Battle publishes `match.finished.v1` and `rating.changed.v1` from an outbox after committing the result.
- Identity publishes `user-profile.changed.v1` for consumer-owned projections.
- Every consumer records `eventId` or an equivalent inbox key before applying an event.
- A projection may be stale, but it must retain the authoritative aggregate ID and version.

## Contract activation gap

The current Judge HTTP stub does not define a stable submission acceptance response or safe evidence read operation. `submission.judged.v1` carries an opaque evidence version but not the accepted job's `RUN`/`SUBMIT` mode, hidden-test progress, or dynamic runtime inputs Battle needs to apply submission locking and D3-BTL-003 scoring after a restart. Judge acceptance, Battle's durable submission and mode correlation, and scoring remain activation blockers until those versioned boundaries are approved.

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
