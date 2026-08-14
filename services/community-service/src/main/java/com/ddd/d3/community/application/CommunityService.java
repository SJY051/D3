package com.ddd.d3.community.application;

import com.ddd.d3.community.adapter.persistence.JdbcCommunityRepository;
import com.ddd.d3.community.domain.MarkdownPolicy;
import com.ddd.d3.community.domain.PostVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class CommunityService {

    private final JdbcCommunityRepository repository;
    private final MarkdownPolicy markdownPolicy;
    private final Supplier<UUID> ids;
    private final int proseLimit;
    private final int markdownLimit;

    public CommunityService(
            JdbcCommunityRepository repository,
            MarkdownPolicy markdownPolicy,
            Supplier<UUID> ids,
            int proseLimit,
            int markdownLimit) {
        this.repository = repository;
        this.markdownPolicy = markdownPolicy;
        this.ids = ids;
        this.proseLimit = proseLimit;
        this.markdownLimit = markdownLimit;
    }

    public PostView createPublicPost(UUID authorUserId, String markdown, PostVisibility visibility) {
        PostVisibility requestedVisibility = visibility == null ? PostVisibility.PUBLIC : visibility;
        if (requestedVisibility != PostVisibility.PUBLIC) {
            throw new IllegalArgumentException("only public posts are enabled in P0");
        }
        if (markdown.length() > markdownLimit) {
            throw new IllegalArgumentException("post markdown exceeds the configured limit");
        }
        int proseCharacters = markdownPolicy.proseCharacterCount(markdown);
        if (proseCharacters > proseLimit) {
            throw new IllegalArgumentException("post prose exceeds the configured limit");
        }
        return repository.insertPost(new NewPost(
                ids.get(),
                authorUserId,
                PostVisibility.PUBLIC,
                markdown,
                markdownPolicy.renderSanitizedHtml(markdown),
                proseCharacters));
    }

    public FeedPage publicFeed(Optional<String> cursor, int limit) {
        Optional<FeedCursor> decodedCursor = cursor.map(FeedCursor::decode);
        return repository.publicFeed(decodedCursor, clamp(limit, 1, 50));
    }

    public record NewPost(
            UUID id,
            UUID authorUserId,
            PostVisibility visibility,
            String markdown,
            String renderedHtml,
            int proseCharacterCount) {}

    public record PostView(
            UUID id,
            UUID authorUserId,
            PostVisibility visibility,
            String markdown,
            String renderedHtml,
            int proseCharacterCount,
            Instant createdAt) {}

    public record FeedPage(List<PostView> posts, String nextCursor) {}

    public record FeedCursor(Instant createdAt, UUID id) {
        public String encode() {
            String raw = createdAt + "|" + id;
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static FeedCursor decode(String cursor) {
            try {
                String raw = new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", 2);
                return new FeedCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("feed cursor is invalid", exception);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
