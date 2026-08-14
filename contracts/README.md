# Public contracts

This directory is the cross-service compatibility boundary. Private entities and persistence models do not belong here.

- `http/`: OpenAPI surfaces. Unactivated services keep empty paths; Judge v1 is the first activated internal service contract.
- `events/`: versioned Kafka envelopes and event payloads.
- `websocket/`: browser-visible real-time event envelopes. Battle v1 is one closed, participant-scoped full snapshot per aggregate version; partial attack/activity messages remain inactive until their own payload contract exists.

Adding a field requires producer and consumer evidence. Removing or changing a field requires a new contract version.
