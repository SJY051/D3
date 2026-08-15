package com.ddd.d3.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

public final class ServiceTokenIssuer {

    private static final Duration MAXIMUM_TTL = Duration.ofMinutes(15);
    private static final String JUDGE_SUBMIT = "judge.submit";
    private static final String JUDGE_READ = "judge.read";

    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final String clientId;
    private final String tokenUse;
    private final Duration ttl;
    private final Clock clock;

    public ServiceTokenIssuer(
            JwtEncoder encoder,
            String issuer,
            String audience,
            String clientId,
            String tokenUse,
            Duration ttl,
            Clock clock) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.issuer = requireText(issuer, "issuer");
        this.audience = requireText(audience, "audience");
        this.clientId = requireText(clientId, "clientId");
        this.tokenUse = requireText(tokenUse, "tokenUse");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAXIMUM_TTL) > 0) {
            throw new IllegalArgumentException("service token ttl must be between zero and 15 minutes");
        }
    }

    public IssuedToken issue(String scope) {
        String allowedScope = switch (requireText(scope, "scope")) {
            case JUDGE_SUBMIT -> JUDGE_SUBMIT;
            case JUDGE_READ -> JUDGE_READ;
            default -> throw new IllegalArgumentException("unsupported service token scope");
        };
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(clientId)
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("scope", allowedScope)
                .claim("client_id", clientId)
                .claim("token_use", tokenUse)
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new IssuedToken(value, ttl.toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
        public IssuedToken {
            requireText(value, "value");
            if (expiresInSeconds <= 0) {
                throw new IllegalArgumentException("expiresInSeconds must be positive");
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
