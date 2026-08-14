package com.ddd.d3.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.identity.config.SigningKey;
import com.nimbusds.jose.jwk.JWKSet;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class AccessTokenIssuerTest {

    private static final Clock CLOCK = Clock.systemUTC();

    @Test
    void d3Id001IssuesATokenVerifiableWithThePublishedJwks() throws Exception {
        SigningKey signingKey = SigningKey.generate();
        AccessTokenIssuer issuer = new AccessTokenIssuer(
                new NimbusJwtEncoder(signingKey.jwkSource()), "http://localhost:8081", "d3-user", CLOCK);
        UUID userId = UUID.fromString("00000000-0000-4000-8000-000000000001");

        String token = issuer.issue(userId);

        Jwt decoded = decoderFromPublishedJwks(signingKey).decode(token);
        assertEquals(userId.toString(), decoded.getSubject());
        assertEquals("http://localhost:8081", decoded.getClaimAsString("iss"));
        assertTrue(decoded.getAudience().contains("d3-user"));
        assertEquals("identity.profile", decoded.getClaimAsString("scope"));
        assertTrue(decoded.getExpiresAt().isAfter(decoded.getIssuedAt()));
    }

    @Test
    void d3Sec001RejectsATokenSignedByADifferentKey() {
        String foreignToken = new AccessTokenIssuer(
                        new NimbusJwtEncoder(SigningKey.generate().jwkSource()),
                        "http://localhost:8081", "d3-user", CLOCK)
                .issue(UUID.fromString("00000000-0000-4000-8000-000000000002"));

        NimbusJwtDecoder decoder = decoderFromPublishedJwks(SigningKey.generate());

        assertThrows(JwtException.class, () -> decoder.decode(foreignToken));
    }

    private static NimbusJwtDecoder decoderFromPublishedJwks(SigningKey signingKey) {
        try {
            RSAPublicKey publicKey = JWKSet.parse(signingKey.publicJwks())
                    .getKeys()
                    .get(0)
                    .toRSAKey()
                    .toRSAPublicKey();
            return NimbusJwtDecoder.withPublicKey(publicKey).build();
        } catch (Exception exception) {
            throw new IllegalStateException("could not build a decoder from the published JWKS", exception);
        }
    }
}
