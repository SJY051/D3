package com.ddd.d3.identity.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.UUID;

/**
 * ponytail: RSA keypair generated in-memory at startup; tokens invalidate on restart.
 * Load a persistent key from Secrets Manager when tokens must survive a redeploy.
 */
public final class SigningKey {

    private final RSAKey rsaKey;

    private SigningKey(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    public static SigningKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var keyPair = generator.generateKeyPair();
            RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            return new SigningKey(rsaKey);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA key generation is unavailable", exception);
        }
    }

    public JWKSource<SecurityContext> jwkSource() {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    public RSAPublicKey publicKey() {
        try {
            return rsaKey.toRSAPublicKey();
        } catch (JOSEException exception) {
            throw new IllegalStateException("public key is unavailable", exception);
        }
    }

    /** The public half only, in the JWKS JSON shape judge-service fetches. */
    public Map<String, Object> publicJwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }
}
