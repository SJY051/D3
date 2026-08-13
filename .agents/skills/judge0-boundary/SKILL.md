---
name: judge0-boundary
description: Implement or review D³ judge-service and Judge0 integration changes. Use for run or submit flows, language-runtime mapping, result normalization, execution limits, static or dynamic evidence, fake-judge adapters, Judge0 smoke tests, and judge deployment boundaries.
---

# D³ Judge0 boundary

## Bind the judge contract

1. Read D3-JDG-001, D3-BTL-003, D3-SOLO-001, Scenario C, and the acceptance criteria in `docs/specs/d3-mvp.md`.
2. Read `docs/architecture/services.md`, `docs/quality/security-review.md`, and `docs/quality/test-plan.md`. Read `docs/operations/deployment-plan.md` before changing containers or cloud plans.
3. Identify the caller, job identity, runtime mapping, normalized result, privacy boundary, and acceptance evidence before editing.

Keep fake-judge evidence, real-Judge smoke evidence, and cloud readiness as separate claims.

## Keep execution behind the adapter

- Route browser requests through the application boundary; the browser never calls Judge0. Accept judge work only from authenticated service callers.
- Keep a versioned adapter seam shared by the deterministic fake judge and the real Judge0 client. Prevent provider payloads and status codes from becoming public contracts.
- Keep Battle authoritative for match winner, rating, and RP. Publish durable judged evidence with correlation and idempotency identifiers.
- Preserve `Run` as public-example evaluation with no submission attempt. Preserve `Submit` as hidden evaluation that increments attempts; an accepted result locks later submissions through the owning battle or solo workflow.

## Isolate and normalize execution

- Allow only configured C, C++, Java, Python 3, JavaScript, and TypeScript runtime mappings. Expose an unavailable state when a mapped runtime is unhealthy.
- Disable execution network access and enforce explicit CPU, wall-time, memory, process or thread, stack, file-size, request-size, and output limits at the Judge0 boundary.
- Keep source, hidden tests, compiler commands, credentials, and private snapshots out of logs, public events, records, and error responses.
- Normalize accepted, wrong answer, compilation error, runtime error, timeout, memory limit, and platform failure. Preserve raw diagnostics only in the private operational boundary.
- Classify infrastructure failures separately from user-code failures so Battle can void the match without rating or RP changes.

## Keep evaluation evidence honest

- Run deterministic correctness cases before performance tiers. Tie runtime evidence to a versioned problem, language runtime, calibration profile, and designated host.
- Repeat size-tier measurements and preserve raw bounded evidence. Treat static complexity as confidence-labelled supporting evidence; transfer unknown weight rather than guessing Big-O.
- Make job acceptance, polling or callback completion, retries, and event publication idempotent. Bound retries and never create a second logical submission from transport recovery.

## Prove the change

1. Test normalization, limits, privacy, idempotency, and platform-failure classification through the fake adapter.
2. Add contract evidence at the Battle-to-Judge and Judge-to-event boundaries.
3. Keep a separate real-Judge smoke case for each supported language and report unavailable runtimes explicitly.
4. Run untrusted code only in the configured isolated judge environment. Without that environment, leave the real smoke test not-run rather than substituting host execution.
5. Invoke `$verify-change`; report deterministic, container-backed, real-Judge, and deployment evidence separately.
