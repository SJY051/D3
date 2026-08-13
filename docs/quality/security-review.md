# Security review boundary

Owner: 윤서진  
Status: Baseline checklist  
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

Last verified: 2026-08-14 against issue #14 and the bound AWS resources

| Control | Result | Evidence boundary |
|---|---|---|
| Dedicated compute and no public API ingress | PASS | Instance `i-0981ab438329d3e62`; security group `sg-0e3253c9132787639` has zero ingress; external port probe blocked |
| Administrative access | PASS | No SSH key or port; SSM with `AmazonSSMManagedInstanceCore` |
| Instance metadata | PASS | IMDSv2 required, hop limit 1, metadata tags disabled |
| API credential | PASS | Generated value in Secrets Manager `d3/judge0/api-auth-token`; instance role can read only this secret path |
| Provenance | PASS | Judge0 CE `1.13.1`, release SHA-256 and all Compose images pinned in [`infra/judge/README.md`](../../infra/judge/README.md) |
| Privileged runtime containment | PASS with residual risk | Official server/worker containers require privileged mode; host is dedicated, zero-ingress, SSM-only, and contains no application workload |
| Submission network and resource limits | PASS | Network opt-in rejected, executed outbound socket blocked, request ceilings rejected, and configured defaults plus per-request CPU/wall/memory/process/stack/file boundaries exercised |
| Submission body-size limits | PENDING | Judge0 CE 1.13.1 has no source/stdin/expected-output field ceiling; issue #13 must validate explicit application limits before provider access |
| Source and diagnostic privacy | PASS for runtime | Hardened startup overlays, rotated bootstrap secrets, sanitized smoke, and zero post-smoke secret/source log matches; real adapter review remains issue #13 work |
| Private service path | PENDING | Current public subnet is zero-ingress for bootstrap; source-SG-only Judge-service route is not bound |

Host activation does not waive application review. Unresolved private-path, adapter authorization, option-allowlist, or source-logging findings block real Judge service integration.

Unresolved Critical or High findings block merge. Record an inapplicable result with evidence rather than suppressing the scanner or weakening a test.

## Scaffold dependency decision

Monaco Editor 0.56.0 pins DOMPurify 3.4.8. The root pnpm override selects the compatible patched 3.4.13 release; keep the override until Monaco itself resolves to an equally new or newer safe version, then remove it with a clean audit and editor regression check.
