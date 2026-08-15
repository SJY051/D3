package com.ddd.d3.battle.infrastructure.identity;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "identity-service",
        url = "${D3_IDENTITY_INTERNAL_URL:}",
        path = "/internal/v1/service-tokens",
        configuration = IdentityServiceTokenClientConfiguration.class)
public interface IdentityServiceTokenClient {

    @PostMapping
    ServiceTokenResponse issue(@RequestBody ServiceTokenRequest request);

    record ServiceTokenRequest(String scope) {}

    record ServiceTokenResponse(String accessToken, String tokenType, long expiresIn) {}
}
