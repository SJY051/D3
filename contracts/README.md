# Public contracts

This directory is the cross-service compatibility boundary. Private entities and persistence models do not belong here.

- `http/`: OpenAPI surfaces; `paths` are intentionally empty in the scaffold.
- `events/`: versioned Kafka envelopes and event payloads.
- `websocket/`: browser-visible real-time event envelopes.

Adding a field requires producer and consumer evidence. Removing or changing a field requires a new contract version.

