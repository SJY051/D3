package com.ddd.d3.identity.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class IdentityJwtConfigurationTest {

    private final IdentityJwtConfiguration configuration = new IdentityJwtConfiguration();

    @Test
    void d3Sec001GeneratesAnEphemeralKeyOnlyForLocalAndTestProfiles() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");

        assertNotNull(configuration.signingKey("", local));
        assertNotNull(configuration.signingKey("", test));
        assertThrows(IllegalStateException.class, () -> configuration.signingKey("", new MockEnvironment()));
    }

    @Test
    void d3Sec001LoadsTheStableSecretKeyForADeployedProfile() throws Exception {
        RSAKey stableJwk = stableJwk();
        MockEnvironment demo = new MockEnvironment();
        demo.setActiveProfiles("demo");

        SigningKey loaded = configuration.signingKey(stableJwk.toJSONString(), demo);

        assertArrayEquals(stableJwk.toRSAPublicKey().getEncoded(), loaded.publicKey().getEncoded());
    }

    private static RSAKey stableJwk() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate())
                .keyID("stable-demo-key")
                .algorithm(JWSAlgorithm.RS256)
                .build();
    }
}
