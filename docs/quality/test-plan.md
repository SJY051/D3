# Test plan

Owner: 최정민 and service owners  
Status: Initial baseline  
Requirement: D3-QLT-001

| Layer | Primary risk | Scaffold evidence | Completion evidence |
|---|---|---|---|
| Domain unit | Match state, outcome, rating, energy | Disabled requirement-named tests | Deterministic examples and boundary cases |
| Adapter integration | PostgreSQL, Redis, Kafka, outbox/inbox | Disabled Testcontainers tests | Real container transaction and retry evidence |
| Contract | HTTP, events, WebSocket | Parseable versioned schemas | Producer/consumer compatibility checks |
| Browser | Ranked golden path and privacy | Skipped Playwright scenario | Two-session fake-judge flow |
| Judge smoke | Runtime mapping and isolation | Skipped language cases | Real Judge0 execution per language |
| Demo preflight | Service readiness | Endpoint checker | All required live dependencies pass |

## Reporting

Every PR reports exact commands and separates pass, fail, skip, and not-run. A disabled skeleton proves only that the test location and requirement mapping exist.

## Initial critical suites

- D3-BTL-002: state transition, authoritative deadline, reconnect, surrender, incident void.
- D3-BTL-003: one solve, both solve, neither solve, exact tie, unknown static evidence.
- D3-BTL-004: energy anti-farming, warning, block, reflect, non-mutating display effects.
- D3-BTL-005: placement visibility, rating adjustment boundary, RP/tier separation.
- D3-ID-001 and D3-SEC-001: explicit OAuth linking, refresh rotation, revocation, room authorization.
- D3-COM-001 and D3-STAT-001: code privacy, idempotent projection, traceable match record.

