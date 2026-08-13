# Service boundaries

Owner: 윤서진  
Status: Initial baseline

| Boundary | Owns | Does not own | Initial caller or consumer |
|---|---|---|---|
| Identity | Accounts, login identities, refresh sessions, public profile | Rating, posts, matches | Gateway; community profile projection |
| Battle | Problems, queue decisions, rooms, outcomes, rating, RP, tiers | Source execution, login credentials, posts | Gateway; judge result events |
| Judge | Submission jobs, execution evidence, static/dynamic evaluation | Match winner, rating, public source | Battle job request; Judge0 |
| Community | Posts, follows, comments, likes, result/profile projections | Authoritative rating or identity | Gateway; Kafka events |

The API gateway uses the reactive Spring Cloud Gateway variant so HTTP and battle WebSocket traffic can share one ingress boundary. Domain services remain conventional Spring MVC applications unless their own requirements justify otherwise.

Gateway authentication does not replace service authorization. Every domain service validates the bearer credential at its own boundary and enforces ownership or role checks for the requested object; the judge boundary additionally rejects non-service callers.

## Communication rules

- Battle requests judge job acceptance synchronously through a versioned client boundary.
- Judge publishes `submission.judged.v1` after durable evaluation.
- Battle publishes `match.finished.v1` and `rating.changed.v1` from an outbox after committing the result.
- Identity publishes `user-profile.changed.v1` for consumer-owned projections.
- Every consumer records `eventId` or an equivalent inbox key before applying an event.
- A projection may be stale, but it must retain the authoritative aggregate ID and version.

## Persistence rules

- Follow `docs/architecture/postgresql.md` for schema, indexing, transaction, pool, and migration rules.
- One local PostgreSQL cluster may host separate databases, but each service uses a separate role and credentials.
- Flyway migrations live with the service that owns the database.
- Cross-service database queries, foreign keys, and shared entity libraries are outside the contract.
- Redis values require a TTL and may be rebuilt or discarded without losing a committed result.
