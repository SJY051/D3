package com.ddd.d3.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record Account(
        UUID id,
        String handle,
        String email,
        String passwordHash,
        String displayName,
        String status,
        Instant createdAt) {

    public static final String ACTIVE = "ACTIVE";
}
