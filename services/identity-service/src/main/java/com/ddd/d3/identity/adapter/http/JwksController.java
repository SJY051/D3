package com.ddd.d3.identity.adapter.http;

import com.ddd.d3.identity.config.SigningKey;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwksController {

    private final SigningKey signingKey;

    public JwksController(SigningKey signingKey) {
        this.signingKey = Objects.requireNonNull(signingKey, "signingKey");
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return signingKey.publicJwks();
    }
}
