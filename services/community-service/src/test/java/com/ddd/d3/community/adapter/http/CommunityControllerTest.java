package com.ddd.d3.community.adapter.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    void d3Com001ExposesTheProjectedAuthorHandleOnFeedPosts() throws Exception {
        when(service.publicFeed(Optional.empty(), 20)).thenReturn(new CommunityService.FeedPage(List.of(
                new CommunityService.PostView(
                        POST_ID,
                        USER_ID,
                        "alice",
                        PostVisibility.PUBLIC,
                        "hello",
                        "<p>hello</p>",
                        5,
                        null,
                        Instant.parse("2026-08-14T00:00:00Z"))), null));

        mockMvc.perform(get("/v1/community/feed")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[0].authorUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.posts[0].authorHandle").value("alice"));
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
                "alice",
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
                .andExpect(jsonPath("$.authorHandle").value("alice"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.renderedHtml").value("<p>hello</p>"));
    }

    @Test
    void d3Com001FollowsForTheAuthenticatedSubjectNotTheRequestBody() throws Exception {
        mockMvc.perform(post("/v1/community/follows")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"followedUserId\":\"" + PLAYER_TWO + "\"}"))
                .andExpect(status().isNoContent());
        verify(service).follow(USER_ID, PLAYER_TWO);
    }

    @Test
    void d3Com001UnfollowsForTheAuthenticatedSubject() throws Exception {
        mockMvc.perform(delete("/v1/community/follows/{followedUserId}", PLAYER_TWO)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isNoContent());
        verify(service).unfollow(USER_ID, PLAYER_TWO);
    }

    @Test
    void d3Com001RejectsSelfFollowAsBadRequest() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("a user cannot follow themselves"))
                .when(service).follow(USER_ID, USER_ID);

        mockMvc.perform(post("/v1/community/follows")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"followedUserId\":\"" + USER_ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void d3Sec001KeepsFollowingBehindAuthentication() throws Exception {
        mockMvc.perform(post("/v1/community/follows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"followedUserId\":\"" + PLAYER_TWO + "\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).follow(any(), any());
    }

    @Test
    void d3Com001ExposesFollowStateForTheViewer() throws Exception {
        when(service.followState(PLAYER_TWO, Optional.of(USER_ID)))
                .thenReturn(new CommunityService.FollowState(3, 1, true));

        mockMvc.perform(get("/v1/community/profiles/{userId}/follow-state", PLAYER_TWO)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(3))
                .andExpect(jsonPath("$.followingCount").value(1))
                .andExpect(jsonPath("$.viewerFollowing").value(true));
    }

    @Test
    void d3Com001LikesAndUnlikesForTheAuthenticatedSubject() throws Exception {
        mockMvc.perform(post("/v1/community/posts/{postId}/likes", POST_ID)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isNoContent());
        verify(service).like(USER_ID, POST_ID);

        mockMvc.perform(delete("/v1/community/posts/{postId}/likes", POST_ID)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isNoContent());
        verify(service).unlike(USER_ID, POST_ID);
    }

    @Test
    void d3Com001ReturnsNotFoundWhenLikingANonPublicPost() throws Exception {
        org.mockito.Mockito.doThrow(new com.ddd.d3.community.application.PostNotFoundException())
                .when(service).like(USER_ID, POST_ID);

        mockMvc.perform(post("/v1/community/posts/{postId}/likes", POST_ID)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    }

    @Test
    void d3Com001ExposesLikeStateForTheViewer() throws Exception {
        when(service.likeState(POST_ID, Optional.of(USER_ID)))
                .thenReturn(new CommunityService.LikeState(5, true));

        mockMvc.perform(get("/v1/community/posts/{postId}/like-state", POST_ID)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(5))
                .andExpect(jsonPath("$.viewerLiked").value(true));
    }

    @Test
    void d3Sec001KeepsLikingBehindAuthentication() throws Exception {
        mockMvc.perform(post("/v1/community/posts/{postId}/likes", POST_ID))
                .andExpect(status().isUnauthorized());
        verify(service, never()).like(any(), any());
    }

    @Test
    void d3Com001AddsACommentForTheAuthenticatedSubjectAndReturnsSanitizedHtml() throws Exception {
        var comment = new CommunityService.CommentView(
                UUID.fromString("66666666-6666-4666-8666-666666666661"),
                POST_ID,
                USER_ID,
                "alice",
                "hi",
                "<p>hi</p>",
                Instant.parse("2026-08-19T00:00:00Z"));
        when(service.addComment(USER_ID, POST_ID, "hi")).thenReturn(comment);

        mockMvc.perform(post("/v1/community/posts/{postId}/comments", POST_ID)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markdown\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorUserId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.authorHandle").value("alice"))
                .andExpect(jsonPath("$.renderedHtml").value("<p>hi</p>"));
    }

    @Test
    void d3Com001ReturnsNotFoundWhenCommentingOnANonPublicPost() throws Exception {
        when(service.addComment(USER_ID, POST_ID, "hi"))
                .thenThrow(new com.ddd.d3.community.application.PostNotFoundException());

        mockMvc.perform(post("/v1/community/posts/{postId}/comments", POST_ID)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markdown\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    }

    @Test
    void d3Com001ListsCommentsWithKeysetPagination() throws Exception {
        var comment = new CommunityService.CommentView(
                UUID.fromString("66666666-6666-4666-8666-666666666661"),
                POST_ID, USER_ID, "alice", "hi", "<p>hi</p>", Instant.parse("2026-08-19T00:00:00Z"));
        when(service.postComments(POST_ID, Optional.empty(), 1))
                .thenReturn(new CommunityService.CommentPage(List.of(comment), "next-comment"));

        mockMvc.perform(get("/v1/community/posts/{postId}/comments", POST_ID)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments[0].renderedHtml").value("<p>hi</p>"))
                .andExpect(jsonPath("$.nextCursor").value("next-comment"));
    }

    @Test
    void d3Com001DeletesOwnCommentButReturnsNotFoundForOthers() throws Exception {
        UUID commentId = UUID.fromString("66666666-6666-4666-8666-666666666661");
        mockMvc.perform(delete("/v1/community/posts/{postId}/comments/{commentId}", POST_ID, commentId)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isNoContent());
        verify(service).deleteComment(USER_ID, POST_ID, commentId);

        org.mockito.Mockito.doThrow(new com.ddd.d3.community.application.CommentNotFoundException())
                .when(service).deleteComment(USER_ID, POST_ID, commentId);
        mockMvc.perform(delete("/v1/community/posts/{postId}/comments/{commentId}", POST_ID, commentId)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
    }

    @Test
    void d3Sec001RejectsOversizedCommentBodiesBeforeDeserialization() throws Exception {
        String oversized = "{\"markdown\":\"" + "x".repeat(CommunityRequestSizeFilter.MAX_REQUEST_BYTES) + "\"}";

        mockMvc.perform(post("/v1/community/posts/{postId}/comments", POST_ID)
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        verify(service, never()).addComment(any(), any(), any());
    }

    @Test
    void d3Sec001KeepsCommentingBehindAuthentication() throws Exception {
        mockMvc.perform(post("/v1/community/posts/{postId}/comments", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markdown\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).addComment(any(), any(), any());
    }

    @Test
    void d3Sec001KeepsHandleSearchBehindAuthentication() throws Exception {
        mockMvc.perform(get("/v1/community/profiles").param("handle", "ali"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(service, never()).searchProfilesByHandle(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void d3Stat001SearchesProfilesByHandleWithKeysetForAuthenticatedCallers() throws Exception {
        when(service.searchProfilesByHandle("ali", Optional.empty(), 1))
                .thenReturn(new CommunityService.ProfilePage(
                        List.of(new CommunityService.ProfileView(USER_ID, "alice", 1450, "GOLD")),
                        "next-profile"));

        mockMvc.perform(get("/v1/community/profiles")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .param("handle", "ali")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.profiles[0].handle").value("alice"))
                .andExpect(jsonPath("$.profiles[0].publicRating").value(1450))
                .andExpect(jsonPath("$.profiles[0].tier").value("GOLD"))
                .andExpect(jsonPath("$.nextCursor").value("next-profile"))
                // Privacy: no identity secrets or internal source versions cross the public boundary.
                .andExpect(jsonPath("$.profiles[0].email").doesNotExist())
                .andExpect(jsonPath("$.profiles[0].displayName").doesNotExist())
                .andExpect(jsonPath("$.profiles[0].identitySourceVersion").doesNotExist());
    }

    @Test
    void d3Stat001RejectsABlankHandleQuery() throws Exception {
        when(service.searchProfilesByHandle(org.mockito.ArgumentMatchers.eq("   "), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalArgumentException("handle query must not be blank"));

        mockMvc.perform(get("/v1/community/profiles")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString())))
                        .param("handle", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
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
