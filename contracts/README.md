# Public contracts

This directory is the cross-service compatibility boundary. Private entities and persistence models do not belong here.

- `http/`: OpenAPI surfaces. Unactivated services keep empty paths; Judge v1 is the first activated internal service contract.
- `events/`: versioned Kafka envelopes and event payloads.
- `websocket/`: browser-visible real-time envelopes. Battle event v1 preserves the original open-payload compatibility shape; Battle snapshot v2 is the closed, participant-scoped full snapshot per aggregate version. Battle command v2 accepts only `READY` and `SURRENDER`; player identity is derived from the authenticated session rather than the frame. Partial attack/activity and transport-owned connection commands remain inactive until their own contracts exist.

Adding a field requires producer and consumer evidence. Removing or changing a field requires a new contract version.
