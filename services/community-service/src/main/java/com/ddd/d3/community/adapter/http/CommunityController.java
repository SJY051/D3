package com.ddd.d3.community.adapter.http;

import com.ddd.d3.community.application.CommunityService;
import com.ddd.d3.community.domain.PostVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/community")
public final class CommunityController {

    private final CommunityService service;

    public CommunityController(CommunityService service) {
        this.service = service;
    }

    @PostMapping("/posts")
    CommunityService.PostView createPost(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePostRequest request) {
        return service.createPublicPost(UUID.fromString(jwt.getSubject()), request.markdown(), request.visibility());
    }

    @GetMapping("/feed")
    CommunityService.FeedPage feed(
            @RequestParam Optional<String> cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return service.publicFeed(cursor, limit);
    }

    @GetMapping("/profiles")
    CommunityService.ProfilePage searchProfiles(
            @RequestParam("handle") String handle,
            @RequestParam Optional<String> cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return service.searchProfilesByHandle(handle, cursor, limit);
    }

    @PostMapping("/follows")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void follow(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FollowRequest request) {
        service.follow(UUID.fromString(jwt.getSubject()), request.followedUserId());
    }

    @DeleteMapping("/follows/{followedUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID followedUserId) {
        service.unfollow(UUID.fromString(jwt.getSubject()), followedUserId);
    }

    @GetMapping("/profiles/{userId}/follow-state")
    CommunityService.FollowState followState(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        return service.followState(userId, Optional.of(UUID.fromString(jwt.getSubject())));
    }

    @PostMapping("/posts/{postId}/likes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void like(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
        service.like(UUID.fromString(jwt.getSubject()), postId);
    }

    @DeleteMapping("/posts/{postId}/likes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlike(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
        service.unlike(UUID.fromString(jwt.getSubject()), postId);
    }

    @GetMapping("/posts/{postId}/like-state")
    CommunityService.LikeState likeState(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
        return service.likeState(postId, Optional.of(UUID.fromString(jwt.getSubject())));
    }

    @PostMapping("/posts/{postId}/comments")
    CommunityService.CommentView addComment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID postId,
            @Valid @RequestBody CreateCommentRequest request) {
        return service.addComment(UUID.fromString(jwt.getSubject()), postId, request.markdown());
    }

    @GetMapping("/posts/{postId}/comments")
    CommunityService.CommentPage comments(
            @PathVariable UUID postId,
            @RequestParam Optional<String> cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return service.postComments(postId, cursor, limit);
    }

    @DeleteMapping("/posts/{postId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID postId,
            @PathVariable UUID commentId) {
        service.deleteComment(UUID.fromString(jwt.getSubject()), postId, commentId);
    }

    @GetMapping("/matches/{matchId}")
    CommunityService.MatchRecordView matchRecord(@PathVariable UUID matchId) {
        return service.matchRecord(matchId).orElseThrow(CommunityMatchRecordNotFoundException::new);
    }

    @GetMapping("/players/{playerId}/matches")
    CommunityService.MatchRecordPage playerMatches(
            @PathVariable UUID playerId,
            @RequestParam Optional<String> cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return service.playerMatches(playerId, cursor, limit);
    }

    record CreatePostRequest(@NotBlank String markdown, PostVisibility visibility) {}

    record FollowRequest(@NotNull UUID followedUserId) {}

    record CreateCommentRequest(@NotBlank String markdown) {}
}
