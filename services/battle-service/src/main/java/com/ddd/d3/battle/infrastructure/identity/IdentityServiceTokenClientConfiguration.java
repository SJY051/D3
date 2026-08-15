package com.ddd.d3.battle.infrastructure.identity;

import feign.RequestInterceptor;
import feign.auth.BasicAuthRequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

final class IdentityServiceTokenClientConfiguration {

    @Bean
    RequestInterceptor identityServiceTokenBasicAuth(
            @Value("${D3_JWT_JUDGE_CLIENT_ID:battle-service}") String clientId,
            @Value("${D3_BATTLE_SERVICE_CLIENT_SECRET:}") String clientSecret) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("D3_JWT_JUDGE_CLIENT_ID is required");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("D3_BATTLE_SERVICE_CLIENT_SECRET is required");
        }
        return new BasicAuthRequestInterceptor(clientId, clientSecret);
    }
}
