package com.ddd.d3.battle.infrastructure.identity;

import com.ddd.d3.battle.application.JudgeServiceTokenProvider;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class IdentityJudgeServiceTokenProvider implements JudgeServiceTokenProvider {

    private final IdentityServiceTokenClient client;

    IdentityJudgeServiceTokenProvider(IdentityServiceTokenClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Token acquire(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        IdentityServiceTokenClient.ServiceTokenResponse response =
                client.issue(new IdentityServiceTokenClient.ServiceTokenRequest(scope.claimValue()));
        if (response == null || !"Bearer".equals(response.tokenType())) {
            throw new IllegalStateException("identity returned an invalid service token response");
        }
        return new Token(response.accessToken(), response.expiresIn());
    }
}
