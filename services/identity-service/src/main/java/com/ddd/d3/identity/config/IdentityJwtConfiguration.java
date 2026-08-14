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
    SigningKey signingKey() {
        return SigningKey.generate();
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKey signingKey) {
        return new NimbusJwtEncoder(signingKey.jwkSource());
    }

    @Bean
    AccessTokenIssuer accessTokenIssuer(
            JwtEncoder jwtEncoder,
            @Value("${d3.security.issuer:http://localhost:8081}") String issuer,
            @Value("${d3.security.access-audience:d3-user}") String audience) {
        return new AccessTokenIssuer(jwtEncoder, issuer, audience, Clock.systemUTC());
    }
}
