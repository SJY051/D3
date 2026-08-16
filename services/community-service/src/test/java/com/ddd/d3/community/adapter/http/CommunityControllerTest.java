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
import com.ddd.d3.community.CommunitySecurityConfiguration;
import com.ddd.d3.community.domain.PostVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private static final UUID MATCH_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PLAYER_TWO = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Autowired MockMvc mockMvc;
    @MockitoBean CommunityService service;
    @MockitoBean JwtDecoder jwtDecoder;
    @Test
    void d3Sec001KeepsThePublicFeedBehindAuthentication() throws Exception {
        mockMvc.perform(get("/v1/community/feed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void d3Stat001ExposesAnActiveMatchRecordWithoutAuthentication() throws Exception {
        when(service.matchRecord(MATCH_ID)).thenReturn(Optional.of(new CommunityService.MatchRecordView(
                MATCH_ID,
                USER_ID,
                PLAYER_TWO,
                "PLAYER_ONE_WIN",
                true,
                7,
                Instant.parse("2026-08-16T01:00:00Z"))));

        mockMvc.perform(get("/v1/community/matches/{matchId}", MATCH_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchId").value(MATCH_ID.toString()))
                .andExpect(jsonPath("$.playerOneUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.playerTwoUserId").value(PLAYER_TWO.toString()))
                .andExpect(jsonPath("$.result").value("PLAYER_ONE_WIN"))
                .andExpect(jsonPath("$.ranked").value(true))
                .andExpect(jsonPath("$.sourceVersion").value(7))
                .andExpect(jsonPath("$.source").doesNotExist())
                .andExpect(jsonPath("$.hiddenTests").doesNotExist())
                .andExpect(jsonPath("$.diagnostics").doesNotExist());
    }

    @Test
    void d3Stat001ExposesPlayerMatchesWithKeysetPaginationWithoutAuthentication() throws Exception {
        var record = new CommunityService.MatchRecordView(
                MATCH_ID,
                USER_ID,
                PLAYER_TWO,
                "DRAW",
                true,
                8,
                Instant.parse("2026-08-16T02:00:00Z"));
        when(service.playerMatches(USER_ID, Optional.empty(), 1))
                .thenReturn(new CommunityService.MatchRecordPage(List.of(record), "next-record"));

        mockMvc.perform(get("/v1/community/players/{playerId}/matches", USER_ID).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].matchId").value(MATCH_ID.toString()))
                .andExpect(jsonPath("$.nextCursor").value("next-record"));
    }

    @Test
    void d3Stat001ReturnsNotFoundForAnUnknownOrInactiveMatch() throws Exception {
        when(service.matchRecord(MATCH_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/community/matches/{matchId}", MATCH_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATCH_RECORD_NOT_FOUND"));
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
                null,
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

    @Test
    void d3Sec001AppliesTheBodyLimitToEncodedPostPaths() throws Exception {
        String oversized = "{\"markdown\":\"" + "x".repeat(CommunityRequestSizeFilter.MAX_REQUEST_BYTES) + "\"}";

        mockMvc.perform(post("/v1/community/posts")
                        .with(request -> {
                            request.setRequestURI("/v1/community/%70osts");
                            return request;
                        })
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());
    }
}
