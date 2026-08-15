package com.ddd.d3.identity.config;

import com.ddd.d3.identity.application.AccessTokenIssuer;
import com.ddd.d3.identity.application.ServiceTokenIssuer;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class IdentityJwtConfiguration {

    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    SigningKey signingKey(
            @Value("${D3_JWT_SIGNING_JWK:}") String signingJwk,
            Environment environment) {
        if (signingJwk != null && !signingJwk.isBlank()) {
            return SigningKey.fromJwk(signingJwk);
        }
        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            return SigningKey.generate();
        }
        throw new IllegalStateException("D3_JWT_SIGNING_JWK is required outside local and test profiles");
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKey signingKey) {
        return new NimbusJwtEncoder(signingKey.jwkSource());
    }

    @Bean
    AccessTokenIssuer accessTokenIssuer(
            JwtEncoder jwtEncoder,
            Clock clock,
            @Value("${D3_JWT_ISSUER:http://localhost:8081}") String issuer,
            @Value("${D3_JWT_USER_AUDIENCE:d3-user}") String audience) {
        return new AccessTokenIssuer(jwtEncoder, issuer, audience, clock);
    }

    @Bean
    ServiceTokenIssuer serviceTokenIssuer(
            JwtEncoder jwtEncoder,
            Clock clock,
            @Value("${D3_JWT_ISSUER:http://localhost:8081}") String issuer,
            @Value("${D3_JWT_JUDGE_AUDIENCE:judge-service}") String audience,
            @Value("${D3_JWT_JUDGE_CLIENT_ID:battle-service}") String clientId,
            @Value("${D3_JWT_SERVICE_TOKEN_USE:service}") String tokenUse,
            @Value("${D3_JWT_SERVICE_TOKEN_TTL:5m}") Duration ttl) {
        return new ServiceTokenIssuer(jwtEncoder, issuer, audience, clientId, tokenUse, ttl, clock);
    }
}
