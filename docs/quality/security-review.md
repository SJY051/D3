# Security review boundary

Owner: 윤서진  
Status: Local ingress and Judge production-adapter route evidenced; application deployment partially evidenced
Requirement: D3-SEC-001

Review these surfaces when their implementation appears:

- account creation, password storage, GitHub OAuth state and explicit linking;
- access-token validation, refresh rotation, revocation, cookie policy, and key handling;
- object-level authorization for profiles, rooms, matches, submissions, and WebSocket destinations;
- user source privacy in logs, events, snapshots, records, and error responses;
- Judge0 API isolation, language and option allowlists, disabled execution network, resource limits, and request limits;
- event producer trust, schema validation, replay, outbox/inbox idempotency, and correlation data;
- environment files, GitHub Actions permissions, container provenance, Terraform state, and AWS IAM;
- Markdown rendering, code blocks, uploads if activated, and administrator operations.

## Local platform ingress review

| Control | Result | Evidence boundary |
|---|---|---|
| Browser ingress inventory | PASS in configuration test | Gateway exposes explicit Identity, Battle HTTP/WebSocket and Community routes; Judge has no browser route |
| Authentication default | PARTIAL PASS | Gateway allows the four canonical session operations and the two exact Community match-record GET shapes anonymously, strips the external `/api` prefix once, and rejects unauthenticated feed, post, profile, write, or undeclared paths. Identity cookie/session behavior and the integrated browser trace remain separate evidence |
| Correlation identifier | PASS in HTTP test | A bounded safe identifier is preserved and an invalid value is replaced before propagation; responses expose the effective identifier |
| CORS | PASS in HTTP test | The configured browser origin receives a successful preflight, an untrusted origin is rejected, and methods and headers use an explicit allowlist |
| Local listener isolation | PASS in configuration and helper test | `pnpm local:start` binds every Java child process to loopback by default; intentional non-loopback sharing requires an explicit operator override and separate discovery protection review |
| Health and public-record exposure | PASS in configuration and HTTP tests | Health/info plus exact `GET /api/v1/community/matches/{matchId}` and `GET /api/v1/community/players/{playerId}/matches` shapes are public; health details and all Community writes/feed access remain protected |
| Battle realtime authorization and privacy | PARTIAL PASS in unit, configuration, PostgreSQL/Redis integration and Gateway runtime tests | Gateway accepts one compact JWT subprotocol only on the exact Battle match path, authenticates it, and removes it before proxying. Battle requires `battle.play`, one exact origin and a PostgreSQL-backed participant lookup before upgrade. The closed v2 command reader accepts only READY/SURRENDER, derives the actor and match binding from the authenticated session, rejects undeclared fields, duplicate members, coercion, oversized frames and cross-match claims, and does not store fresh no-op receipt keys. Authenticated opens receive a PostgreSQL-issued monotonic generation; disconnect uses only the server-held value, bounded optimistic retries preserve it, and stale close events cannot override a newer connection. Exhausted optimistic close races retain only the highest failed generation per match participant in an in-process retry queue instead of discarding authoritative cleanup. READY/SURRENDER verifies the same server-held generation against the current connected PostgreSQL participant inside the command transaction, so a superseded socket cannot mutate the replacement session. A pre-start close clears readiness without activating the in-match grace period. Due match and reconnect deadlines are claimed in bounded PostgreSQL transactions with `FOR UPDATE SKIP LOCKED`; concurrent instances skip an existing claim, the earlier authoritative deadline wins, and the full committed batch is submitted to bounded asynchronous fan-out only after its claim loop completes. Redis broadcasts only the match ID across instances; failed committed notifications are coalesced by match ID and retried until accepted, and generation-fenced completion preserves a newer failure when an older retry succeeds. A bounded periodic local-session resynchronizer repairs notifications missed while a Redis subscriber was disconnected by re-reading PostgreSQL. Snapshot preparation failures retain the healthy session for the next cycle, while checked or unchecked transport send failures evict it and apply the server-held disconnect generation. Aggregate versions suppress duplicate client sends. Viewer-specific v2 snapshots exclude opponent and winner UUIDs, source, literals, connection generations and incident references. Stale versions and failed sessions are isolated. Per-viewer live-session bounding remains tracked in #37; Identity issuance and a live two-browser handshake remain NOT RUN |
| Community record privacy | PASS in PostgreSQL and HTTP tests | Public records contain only match ID, seat-ordered user IDs, result, ranked flag, source version and projection time. Result posts contain the same bounded summary and a match link; source, hidden-test data, credentials, score/runtime evidence and diagnostics are absent |

## Judge0 activation review

Last verified: 2026-08-15 against issue #13/#15/#59 implementation, issue #14 and the bound AWS resources

| Control | Result | Evidence boundary |
|---|---|---|
| Dedicated compute and no public API ingress | PASS | Instance `i-0981ab438329d3e62`; security group `sg-0e3253c9132787639` has zero ingress; external port probe blocked |
| Administrative access | PASS | No SSH key or port; SSM with `AmazonSSMManagedInstanceCore` |
| Instance metadata | PASS | IMDSv2 required, hop limit 1, metadata tags disabled |
| API credential | PASS | Generated value in Secrets Manager `d3/judge0/api-auth-token`; instance role can read only this secret path |
| Provenance | PASS | Judge0 CE `1.13.1`, release SHA-256 and all Compose images pinned in [`infra/judge/README.md`](../../infra/judge/README.md) |
| Privileged runtime containment | PASS with residual risk | Official server/worker containers require privileged mode; host is dedicated, zero-ingress, SSM-only, and contains no application workload |
| Submission network and resource limits | PASS | Network opt-in rejected, executed outbound socket blocked, request ceilings rejected, and configured defaults plus per-request CPU/wall/memory/process/stack/file boundaries exercised |
| Submission body-size limits | PASS at application boundary | Judge0 CE 1.13.1 has no field ceiling; issue #13 rejects the bounded HTTP body and UTF-8 source/stdin/expected-output fields before provider access, with negative tests |
| Source and diagnostic privacy | PASS at host and production-adapter boundaries | Host smoke found zero post-smoke secret/source log matches. Issue #59 SSM command `a38944c3-8073-47de-b414-f3bd610acdf8` also verified that the production adapter's output contains no credential or source; application tests keep source, hidden input and raw provider diagnostics out of public responses and events |
| Private service path | PASS for the temporary production-adapter route; deployment PENDING | Issue #59 allowed TCP 2358 only from a dedicated no-ingress application-runner SG, ran all six pinned mappings, removed the runner/SG/rule, and reconfirmed zero Judge0 ingress. A deployed Judge service must bind its own reviewed source SG |

## Judge application review

| Control | Result | Evidence boundary |
|---|---|---|
| Service authentication and authorization | PASS in issuer, acquisition, HTTP and validator tests | Identity issues only short-lived `judge.submit`/`judge.read` tokens to the Basic-authenticated Battle client; Battle acquires them without browser credentials; Judge requires the trusted signature/issuer, explicit clock skew, `aud=judge-service`, `client_id=battle-service`, `token_use=service` and endpoint scope. Anonymous, user-scope, user-token, wrong-audience and wrong-service callers are rejected. |
| Provider destination and credential handling | PASS at the production adapter boundary; deployment PARTIAL | The adapter accepts one exact configured origin, sends one configured authentication header, bounds provider responses and exposes no credential. Issue #59 exercised that path from the exact source SG; persistent deployment egress binding remains PENDING |
| Execution option allowlist | PASS in adapter tests | Six language mappings and fixed CPU, wall-time, memory, process/thread, stack, file-size and disabled-network options are asserted before provider access |
| Durable idempotency and recovery | PASS in container tests | PostgreSQL enforces one command key and request fingerprint; an opaque evaluation claim token fences stale workers; terminal evidence and outbox insertion share one transaction |
| Public response and event privacy | PASS in serialization tests | Safe evidence and `submission.judged.v1` omit source, hidden cases, compiler commands, provider credentials and raw diagnostics |
| Production adapter to AWS Judge0 | PASS | Issue #59 ran `HttpJudge0Client` and `Judge0ExecutionAdapter` over the source-SG-only private route for all six pinned runtimes with output-privacy checks; a deployed Judge HTTP service is NOT RUN |

Judge0 submission POSTs are never retried because CE 1.13.1 exposes no idempotency key for that operation. Judge durably marks a claim before the first provider request. After a token is received, transient polling failures retry only the same token within the original absolute deadline; an ambiguous POST result or exhausted polling becomes one privacy-safe `PLATFORM_FAILURE` without replaying completed cases. Database completion retries reuse the same in-memory evidence, and stale recovery of a marked claim emits `PLATFORM_FAILURE` without calling Judge0 again. Provider token and per-case evidence are not persisted in this MVP, so a judge-service process loss can still leave an orphan provider job or discard a completed provider result, but it cannot replay marked provider work; durable continuation remains outside the current live-path claim.

Host and production-adapter activation do not waive deployment review. The temporary route is removed; the real Judge-service source SG and persistent deployment egress control remain PENDING. Do not claim a deployed Judge HTTP service until those controls and an end-to-end service call are evidenced.

Unresolved Critical or High findings block merge. Record an inapplicable result with evidence rather than suppressing the scanner or weakening a test.

## Scaffold dependency decision

Monaco Editor 0.56.0 pins DOMPurify 3.4.8. The root pnpm override selects the compatible patched 3.4.13 release; keep the override until Monaco itself resolves to an equally new or newer safe version, then remove it with a clean audit and editor regression check.
