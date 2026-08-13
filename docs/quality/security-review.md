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

Unresolved Critical or High findings block merge. Record an inapplicable result with evidence rather than suppressing the scanner or weakening a test.

## Scaffold dependency decision

Monaco Editor 0.56.0 pins DOMPurify 3.4.8. The root pnpm override selects the compatible patched 3.4.13 release; keep the override until Monaco itself resolves to an equally new or newer safe version, then remove it with a clean audit and editor regression check.
