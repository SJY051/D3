package com.ddd.d3.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshSession(
        UUID id,
        UUID userId,
        String tokenHash,
        Instant expiresAt,
        UUID rotatedFromId,
        Instant revokedAt,
        Instant createdAt) {

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
