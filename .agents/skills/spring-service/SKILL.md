---
name: spring-service
description: Implement or review D³ Spring service changes in services/** and platform/**. Use for domain logic, REST APIs, JPA or Flyway persistence, PostgreSQL, Redis or Kafka adapters, Gradle service configuration, and Spring service tests.
---

# D³ Spring service

## Bind the contract

1. Resolve the issue and requirement IDs in `docs/specs/d3-mvp.md`.
2. Read the affected module, its nearest tests, and the public HTTP, event, or WebSocket contract it serves.
3. Read `docs/architecture/services.md`, `docs/architecture/postgresql.md`, and `docs/architecture/erd.dbml` for a boundary, persistence, or integration change. Read `docs/quality/security-review.md` when authentication, authorization, user source, WebSocket access, secrets, containers, or Judge0 is involved.
4. State the owning service, observable behavior, non-goals, and acceptance evidence before editing.

Begin implementation only when ownership and evidence are explicit.

## Preserve the service boundary

- Keep identity, battle, judge, and community models and persistence private to their owner. Exchange stable IDs and versioned contracts.
- Keep PostgreSQL authoritative for durable state. Give every Redis value a TTL and a rebuild path.
- Keep HTTP, Judge0, and Kafka waits outside database transactions. Publish committed cross-service effects through an outbox and consume them idempotently.
- Keep domain services on Spring MVC unless an accepted requirement changes that choice. Preserve the API gateway's reactive boundary without leaking gateway models into services.
- Validate bearer credentials and object ownership at each service boundary; gateway authentication is not service authorization.

## Implement in the detected stack

- Use the root-managed Java, Spring Boot, Spring Cloud, and dependency versions with Gradle Kotlin DSL. Extend the existing convention instead of introducing Maven or module-local version drift.
- Keep request and response contracts separate from persistence entities. Validate requests at the boundary and return the project's versioned error shape.
- Treat Flyway as the only schema writer and Hibernate as a validator. Design PostgreSQL types, constraints, indexes, and queries from domain invariants and actual access shapes.
- Add a dependency or shared abstraction only when the requirement needs it and no existing project seam fits.

## Prove the change

1. Add deterministic unit evidence for domain rules.
2. Use PostgreSQL-, Redis-, or Kafka-backed Testcontainers evidence for affected adapters; an in-memory substitute does not prove production behavior.
3. Validate every affected HTTP, event, or WebSocket contract and its failure path.
4. Run the narrow Gradle task for the owning module, then the relevant repository checks. Keep infrastructure-dependent smoke tests separate from deterministic tests.
5. Invoke `$verify-change` and report pass, fail, skip, and not-run separately. A disabled scaffold or skipped container test proves structure only.
