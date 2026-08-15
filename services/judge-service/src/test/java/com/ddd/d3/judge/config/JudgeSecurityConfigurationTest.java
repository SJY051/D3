package com.ddd.d3.judge.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JudgeSecurityConfigurationTest {

    private static final String ISSUER = "https://identity.d3.local";

    @Test
    void d3Sec001AcceptsOnlyTheBattleServiceIdentityForTheJudgeAudience() {
        var validator = JudgeSecurityConfiguration.judgeTokenValidator(
                ISSUER, "judge-service", "battle-service", "service", Duration.ofSeconds(30));

        assertFalse(validator.validate(token(builder -> {})).hasErrors());
        assertTrue(validator.validate(token(builder -> builder.audience(List.of("d3-user")))).hasErrors());
        assertTrue(validator.validate(token(builder -> builder.claim("client_id", "browser-user"))).hasErrors());
        assertTrue(validator.validate(token(builder -> builder.claim("token_use", "user"))).hasErrors());
        assertTrue(validator.validate(token(builder -> builder.issuer("https://untrusted.example"))).hasErrors());
        assertTrue(validator.validate(token(builder -> builder
                        .issuedAt(Instant.now().minusSeconds(120))
                        .expiresAt(Instant.now().minusSeconds(31))))
                .hasErrors());
    }

    private static Jwt token(Consumer<Jwt.Builder> customization) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("battle-service")
                .audience(List.of("judge-service"))
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(60))
                .claim("client_id", "battle-service")
                .claim("token_use", "service")
                .claim("scope", "judge.submit judge.read");
        customization.accept(builder);
        return builder.build();
    }
}
