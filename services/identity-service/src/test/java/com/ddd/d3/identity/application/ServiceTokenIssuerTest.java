package com.ddd.d3.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.identity.config.SigningKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class ServiceTokenIssuerTest {
    private static final Instant NOW = Instant.parse("2030-08-15T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void d3Sec001IssuesScopeSpecificBattleServiceTokensForJudge() {
        SigningKey key = SigningKey.generate();
        ServiceTokenIssuer issuer = issuer(key);

        Jwt submit = decoder(key).decode(issuer.issue("judge.submit").value());
        Jwt read = decoder(key).decode(issuer.issue("judge.read").value());

        assertServiceIdentity(submit, "judge.submit");
        assertServiceIdentity(read, "judge.read");
    }

    @Test
    void d3Sec001RejectsCallerSelectedScopesAndLongLivedTokens() {
        SigningKey key = SigningKey.generate();
        ServiceTokenIssuer issuer = issuer(key);

        assertThrows(IllegalArgumentException.class, () -> issuer.issue("identity.profile"));
        assertThrows(IllegalArgumentException.class, () -> new ServiceTokenIssuer(
                new NimbusJwtEncoder(key.jwkSource()),
                "https://identity.d3.local",
                "judge-service",
                "battle-service",
                "service",
                Duration.ofMinutes(16),
                CLOCK));
    }

    private static ServiceTokenIssuer issuer(SigningKey key) {
        return new ServiceTokenIssuer(
                new NimbusJwtEncoder(key.jwkSource()),
                "https://identity.d3.local",
                "judge-service",
                "battle-service",
                "service",
                Duration.ofMinutes(5),
                CLOCK);
    }

    private static NimbusJwtDecoder decoder(SigningKey key) {
        return NimbusJwtDecoder.withPublicKey(key.publicKey()).build();
    }

    private static void assertServiceIdentity(Jwt token, String scope) {
        assertEquals("https://identity.d3.local", token.getIssuer().toString());
        assertEquals("battle-service", token.getSubject());
        assertTrue(token.getAudience().contains("judge-service"));
        assertEquals("battle-service", token.getClaimAsString("client_id"));
        assertEquals("service", token.getClaimAsString("token_use"));
        assertEquals(scope, token.getClaimAsString("scope"));
        assertEquals(NOW.plus(Duration.ofMinutes(5)), token.getExpiresAt());
    }
}
