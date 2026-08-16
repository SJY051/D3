package com.ddd.d3.community.adapter.http;

import com.ddd.d3.community.application.CommunityService;
import com.ddd.d3.community.domain.PostVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
}
