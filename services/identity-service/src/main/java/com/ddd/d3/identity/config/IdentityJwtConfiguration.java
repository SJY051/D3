package com.ddd.d3.identity.config;

import com.ddd.d3.identity.application.AccessTokenIssuer;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class IdentityJwtConfiguration {

    @Bean
    SigningKey signingKey(@Value("${D3_JWT_SIGNING_JWK:}") String signingJwk) {
        return signingJwk == null || signingJwk.isBlank()
                ? SigningKey.generate()
                : SigningKey.fromJwk(signingJwk);
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKey signingKey) {
        return new NimbusJwtEncoder(signingKey.jwkSource());
    }

    @Bean
    AccessTokenIssuer accessTokenIssuer(
            JwtEncoder jwtEncoder,
            @Value("${D3_JWT_ISSUER:http://localhost:8081}") String issuer,
            @Value("${D3_JWT_USER_AUDIENCE:d3-user}") String audience) {
        return new AccessTokenIssuer(jwtEncoder, issuer, audience, Clock.systemUTC());
    }
}
