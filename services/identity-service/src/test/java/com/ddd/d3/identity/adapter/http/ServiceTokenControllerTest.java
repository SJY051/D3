package com.ddd.d3.identity.adapter.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ddd.d3.identity.config.IdentityJwtConfiguration;
import com.ddd.d3.identity.config.IdentitySecurityConfiguration;
import com.ddd.d3.identity.config.SigningKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(
        value = ServiceTokenController.class,
        properties = {
            "spring.profiles.active=test",
            "D3_BATTLE_SERVICE_CLIENT_SECRET=test-battle-secret",
            "D3_JWT_ISSUER=https://identity.d3.local"
        })
@Import({
    IdentityJwtConfiguration.class,
    IdentitySecurityConfiguration.class,
    IdentityHttpExceptionHandler.class
})
class ServiceTokenControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SigningKey signingKey;

    @Test
    void d3Sec001IssuesAJudgeSubmitTokenOnlyToTheBattleClient() throws Exception {
        String response = mockMvc.perform(post("/internal/v1/service-tokens")
                        .with(httpBasic("battle-service", "test-battle-secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"judge.submit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(300))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String value = objectMapper.readTree(response).get("accessToken").asText();
        Jwt token = NimbusJwtDecoder.withPublicKey(signingKey.publicKey()).build().decode(value);
        assertEquals("battle-service", token.getSubject());
        assertEquals("battle-service", token.getClaimAsString("client_id"));
        assertEquals("service", token.getClaimAsString("token_use"));
        assertEquals("judge.submit", token.getClaimAsString("scope"));
    }

    @Test
    void d3Sec001RejectsWrongClientCredentialsWithoutEchoingThem() throws Exception {
        String response = mockMvc.perform(post("/internal/v1/service-tokens")
                        .with(httpBasic("battle-service", "wrong-secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"judge.read\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(response.contains("wrong-secret"));
    }

    @Test
    void d3Sec001RejectsAnUnapprovedServiceScope() throws Exception {
        mockMvc.perform(post("/internal/v1/service-tokens")
                        .with(httpBasic("battle-service", "test-battle-secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"identity.profile\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
