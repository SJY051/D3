package com.ddd.d3.battle.infrastructure.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ddd.d3.battle.application.JudgeServiceTokenProvider;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import feign.RequestInterceptor;
import feign.RequestTemplate;

class IdentityJudgeServiceTokenProviderTest {

    @Test
    void d3Sec001AcquiresScopeSpecificSubmitAndReadTokensFromIdentity() {
        IdentityServiceTokenClient client = mock(IdentityServiceTokenClient.class);
        when(client.issue(new IdentityServiceTokenClient.ServiceTokenRequest("judge.submit")))
                .thenReturn(new IdentityServiceTokenClient.ServiceTokenResponse("submit-token", "Bearer", 300));
        when(client.issue(new IdentityServiceTokenClient.ServiceTokenRequest("judge.read")))
                .thenReturn(new IdentityServiceTokenClient.ServiceTokenResponse("read-token", "Bearer", 300));
        var provider = new IdentityJudgeServiceTokenProvider(client);

        JudgeServiceTokenProvider.Token submit = provider.acquire(JudgeServiceTokenProvider.Scope.SUBMIT);
        JudgeServiceTokenProvider.Token read = provider.acquire(JudgeServiceTokenProvider.Scope.READ);

        assertEquals("Bearer submit-token", submit.authorizationHeader());
        assertEquals("Bearer read-token", read.authorizationHeader());
        verify(client).issue(new IdentityServiceTokenClient.ServiceTokenRequest("judge.submit"));
        verify(client).issue(new IdentityServiceTokenClient.ServiceTokenRequest("judge.read"));
    }

    @Test
    void d3Sec001RejectsMalformedIdentityTokenResponses() {
        IdentityServiceTokenClient client = mock(IdentityServiceTokenClient.class);
        when(client.issue(new IdentityServiceTokenClient.ServiceTokenRequest("judge.submit")))
                .thenReturn(new IdentityServiceTokenClient.ServiceTokenResponse("user-token", "Basic", 300));
        var provider = new IdentityJudgeServiceTokenProvider(client);

        assertThrows(IllegalStateException.class, () -> provider.acquire(JudgeServiceTokenProvider.Scope.SUBMIT));
    }

    @Test
    void d3Sec001AuthenticatesTokenAcquisitionWithOnlyTheBattleClientSecret() {
        RequestInterceptor interceptor = new IdentityServiceTokenClientConfiguration()
                .identityServiceTokenBasicAuth("battle-service", "client-secret");
        RequestTemplate request = new RequestTemplate();

        interceptor.apply(request);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("battle-service:client-secret".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(expected, request.headers().get("Authorization").iterator().next());
    }
}
