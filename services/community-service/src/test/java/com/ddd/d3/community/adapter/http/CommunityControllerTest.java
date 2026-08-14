package com.ddd.d3.community.adapter.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ddd.d3.community.application.CommunityService;
import com.ddd.d3.community.config.CommunityRequestSizeFilter;
import com.ddd.d3.community.config.CommunitySecurityConfiguration;
import com.ddd.d3.community.domain.PostVisibility;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommunityController.class)
@Import({CommunitySecurityConfiguration.class, CommunityHttpExceptionHandler.class})
class CommunityControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID POST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Autowired MockMvc mockMvc;
    @MockitoBean CommunityService service;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void d3Sec001RequiresAuthenticationForCommunityApis() throws Exception {
        mockMvc.perform(get("/v1/community/feed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void d3Com001CreatesOnlyPublicPostsForTheAuthenticatedSubject() throws Exception {
        when(service.createPublicPost(any(), any(), any())).thenReturn(new CommunityService.PostView(
                POST_ID,
                USER_ID,
                PostVisibility.PUBLIC,
                "hello",
                "<p>hello</p>",
                5,
                Instant.parse("2026-08-14T00:00:00Z")));

        mockMvc.perform(post("/v1/community/posts")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markdown\":\"hello\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.renderedHtml").value("<p>hello</p>"));
    }

    @Test
    void d3Sec001RejectsOversizedPostBodiesBeforeDeserialization() throws Exception {
        String oversized = "{\"markdown\":\"" + "x".repeat(CommunityRequestSizeFilter.MAX_REQUEST_BYTES) + "\"}";

        mockMvc.perform(post("/v1/community/posts")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .header("X-Correlation-Id", "corr-big")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.correlationId").value("corr-big"));

        verify(service, never()).createPublicPost(any(), any(), any());
    }
}
