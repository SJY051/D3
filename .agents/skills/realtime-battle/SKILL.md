---
name: realtime-battle
description: Implement or review D³ matchmaking, match state, WebSocket, reconnect, scoring, rating, attack, and opponent-activity behavior. Use for D3-BTL-001 through D3-BTL-005 changes across battle service, gateway, contracts, and the battle client.
---

# D³ realtime battle

## Bind the match contract

1. Read D3-BTL-001 through D3-BTL-005 and Scenarios A through C in `docs/specs/d3-mvp.md`.
2. Read the affected WebSocket or event contract, `docs/architecture/services.md`, `docs/quality/test-plan.md`, and `docs/quality/security-review.md`.
3. Identify the authoritative aggregate, accepted commands, emitted observations, persistence owner, and deterministic evidence before editing.

Do not implement an unresolved balance value as a permanent domain constant; keep approved prototype values replaceable.

## Keep the server authoritative

- Represent lobby, ready, running, judging, and finished as explicit transitions. Validate actor, room access, current state, version, and idempotency before accepting a command.
- Decide starts, deadlines, reconnect expiry, surrender, outcomes, and attack effects from server state and timestamps. Limit RTT compensation to client timer rendering.
- Continue the match clock through disconnects, expose disconnection, restore the latest private snapshot within 30 seconds, and resolve expiry, surrender, or platform incident exactly once.
- Version snapshots and events so clients can discard stale or duplicate updates and resynchronize after gaps.
- Store committed results in PostgreSQL. Use Redis only for expiring queue, presence, reconnect, fan-out, and recoverable snapshot state.

## Protect source and interaction integrity

- Publish only the masked opponent projection allowed by D3-BTL-002. Keep identifiers, literals, accepted source, private snapshots, logs, and events out of opponent and public views.
- Treat client parser or linter evidence as advisory. Award energy only from server-validated, bounded, first-time progress and reject delete-and-retype farming.
- Make attacks finite, logged, replayable, and state-machine commands. Preserve warning, block, and reflect windows.
- Keep overlays and Caesar veils display-only. Constrain the rare caret move to a valid row and preserve editor undo behavior.

## Keep outcomes replaceable

- Model one-solve, both-solve, neither-solve, exact-tie, surrender, disconnect, and platform-void outcomes explicitly.
- Keep solve speed, dynamic evidence, bounded static evidence, and submission discipline as named score components. Transfer unknown static weight to dynamic evidence.
- Separate matchmaking rating from seasonal RP, tier, division, placement visibility, and leaderboard projection.

## Prove the change

1. Use an injected clock and deterministic command sequence for transition, timeout, reconnect, and cooldown tests.
2. Cover duplicate and out-of-order commands, unauthorized room access, anti-farming, block and reflect, exact ties, and platform voids.
3. Validate WebSocket and event schemas plus durable outbox or inbox behavior where affected.
4. Run the narrow service and client checks, then the two-session fake-judge golden path when the slice is complete.
5. Invoke `$verify-change`; report infrastructure smoke evidence separately from deterministic evidence.
