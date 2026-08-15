package com.ddd.d3.identity.adapter.http;

import com.ddd.d3.identity.application.ServiceTokenIssuer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ServiceTokenController {

    private final ServiceTokenIssuer tokenIssuer;

    public ServiceTokenController(ServiceTokenIssuer tokenIssuer) {
        this.tokenIssuer = tokenIssuer;
    }

    @PostMapping("/internal/v1/service-tokens")
    public ServiceTokenResponse issue(@Valid @RequestBody ServiceTokenRequest request) {
        ServiceTokenIssuer.IssuedToken token = tokenIssuer.issue(request.scope());
        return new ServiceTokenResponse(token.value(), "Bearer", token.expiresInSeconds());
    }

    public record ServiceTokenRequest(@NotBlank String scope) {}

    public record ServiceTokenResponse(String accessToken, String tokenType, long expiresIn) {}
}
