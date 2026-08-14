# PostgreSQL working rules

Owner: Service owners  
Status: Initial baseline  
Applies to: identity, battle, judge, and community databases

These rules make PostgreSQL behavior explicit for a team coming from MySQL or MariaDB. A service owns its Flyway migrations, role, pool, and query plans; it never reads another service database.

## Schema and migration rules

- Use unquoted lowercase `snake_case` identifiers. PostgreSQL folds unquoted names to lowercase; quoted mixed-case names become permanently quote-sensitive.
- Use `text` unless a length limit is a real domain invariant, `boolean` for flags, exact `numeric` for decimal quantities, and `timestamptz` for instants. Persist durations as explicit numeric units.
- Keep ORM schema mutation disabled. `spring.jpa.hibernate.ddl-auto=validate`; Flyway is the only schema writer.
- Treat an applied migration as immutable, including migrations used only by local persistent volumes. Evolve an existing schema with the next versioned migration and prove both fresh installation and upgrade from the previous version; never rewrite history to make the final schema look cleaner.
- Register every migration in `scripts/migration-checksums.json`; `pnpm verify:migrations` rejects modified history and migration files missing from the manifest. Add the next migration and its checksum together. A checksum change for an existing entry requires an explicit recovery review and is not the normal schema-change path.
- Encode invariants with `not null`, `unique`, foreign-key, and `check` constraints. PostgreSQL has no `add constraint if not exists`; write forward-only, reviewable migrations instead of vendor-specific guesses.
- Use stable UUID aggregate IDs in public contracts. Prefer a pinned time-ordered UUID implementation if adopted by the whole team; do not add an unreviewed database extension merely to change ID format during the prototype.
- Use `jsonb` only for genuinely variable metadata with a versioned reader. Frequently filtered or constrained fields stay typed columns.

## Query and index rules

- PostgreSQL does not automatically index the referencing side of a foreign key. Add an index when the relationship is queried, joined, or cascaded.
- Design indexes from observed `where`, `join`, and `order by` shapes. For composite indexes, put equality columns before range/sort columns and respect the leftmost-prefix rule.
- Prefer a partial index when every hot query uses the same selective predicate, such as active queue entries or unpublished outbox rows.
- Use keyset pagination such as `(created_at, id) < (?, ?)` for feeds, records, and histories. Avoid deep `offset` pagination.
- Diagnose slow queries with `explain (analyze, buffers)` against representative data; never add an index solely from intuition. Because `analyze` executes the statement, run it safely outside production write paths.

## Transactions, pools, and operations

- Keep transactions short and exclude HTTP, Judge0, Kafka, and other network waits. Use an atomic outbox in the same database transaction, then publish asynchronously.
- Lock rows in a consistent ID order, or prefer a single conditional update. Make retries explicit for serialization or deadlock failures; never retry non-idempotent effects blindly.
- Each service uses its own bounded HikariCP pool. The sum of service pools plus operator headroom must remain below the database connection limit; do not increase `max_connections` as a first response.
- Preserve autovacuum. After large seed or calibration loads, inspect statistics and run `analyze` when needed.
- Application roles are never superusers. Grant only their own database/schema privileges and keep migration credentials separate from runtime credentials in deployed environments.

Every schema PR includes the Flyway migration, ownership and privacy impact, expected query shapes, required indexes, rollback/forward-fix note, and a PostgreSQL-backed integration test.
