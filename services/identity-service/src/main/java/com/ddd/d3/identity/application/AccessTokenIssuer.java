package com.ddd.d3.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

public final class AccessTokenIssuer {

    // ponytail: fixed 15-minute access lifetime; move behind config alongside the refresh policy if it needs tuning
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final String SCOPE = "identity.profile";

    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, String issuer, String audience, Clock clock) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String issue(UUID userId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(Objects.requireNonNull(userId, "userId").toString())
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TTL))
                .claim("scope", SCOPE)
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
