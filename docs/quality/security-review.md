# Security review boundary

Owner: 윤서진  
Status: Baseline checklist; Judge application review partially evidenced
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

## Judge0 activation review

Last verified: 2026-08-14 against issue #13 implementation, issue #14 and the bound AWS resources

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
| Source and diagnostic privacy | PASS in bounded tests; live path NOT RUN | Host smoke found zero post-smoke secret/source log matches; application tests keep source, hidden input, credentials and raw provider diagnostics out of public responses and events |
| Private service path | PENDING | Current public subnet is zero-ingress for bootstrap; source-SG-only Judge-service route is not bound |

## Judge application review

| Control | Result | Evidence boundary |
|---|---|---|
| Service authentication and authorization | PASS in HTTP tests | JWT issuer, Judge audience, Battle service caller identity and endpoint scopes are required; anonymous, user-scope and wrong-service callers are rejected |
| Provider destination and credential handling | PARTIAL PASS | The adapter accepts one exact configured origin, sends one configured authentication header, bounds provider responses and exposes no credential; deployment egress restriction remains PENDING |
| Execution option allowlist | PASS in adapter tests | Six language mappings and fixed CPU, wall-time, memory, process/thread, stack, file-size and disabled-network options are asserted before provider access |
| Durable idempotency and recovery | PASS in container tests | PostgreSQL enforces one command key and request fingerprint; an opaque evaluation claim token fences stale workers; terminal evidence and outbox insertion share one transaction |
| Public response and event privacy | PASS in serialization tests | Safe evidence and `submission.judged.v1` omit source, hidden cases, compiler commands, provider credentials and raw diagnostics |
| Real judge-service to AWS Judge0 call | NOT RUN | The real adapter path exists, but its intended source-security-group-only route is not bound; retain issue #14 host smoke as separate evidence |

Host activation does not waive application review. The private route and deployment egress control remain PENDING and block a claim of real Judge service integration. Complete a final targeted diff review before issue #13 merge; unresolved Critical or High findings block merge.

Unresolved Critical or High findings block merge. Record an inapplicable result with evidence rather than suppressing the scanner or weakening a test.

## Scaffold dependency decision

Monaco Editor 0.56.0 pins DOMPurify 3.4.8. The root pnpm override selects the compatible patched 3.4.13 release; keep the override until Monaco itself resolves to an equally new or newer safe version, then remove it with a clean audit and editor regression check.
