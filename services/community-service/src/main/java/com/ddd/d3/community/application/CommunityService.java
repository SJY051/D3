package com.ddd.d3.community.application;

import com.ddd.d3.community.adapter.persistence.JdbcCommunityRepository;
import com.ddd.d3.community.application.MatchFinishedProjectionService.PlayerRecordEvidence;
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

    public void createResultPost(MatchFinishedProjectionService.MatchFinishedEvent event) {
        String markdown = """
                Ranked match %s
                Player one: %s
                Player two: %s
                Result: %s
                Ranked: %s"""
                .formatted(
                        event.matchId(),
                        event.playerIds().get(0),
                        event.playerIds().get(1),
                        event.result(),
                        event.ranked());
        int proseCharacters = markdownPolicy.proseCharacterCount(markdown);
        if (markdown.length() > markdownLimit || proseCharacters > proseLimit) {
            throw new IllegalStateException("generated result post exceeds the configured limit");
        }
        repository.insertResultPost(new NewResultPost(
                ids.get(),
                event.matchId(),
                event.aggregateVersion(),
                event.playerIds().get(0),
                markdown,
                markdownPolicy.renderSanitizedHtml(markdown),
                proseCharacters));
    }

    public Optional<MatchRecordView> matchRecord(UUID matchId) {
        return repository.matchRecord(matchId);
    }

    public MatchRecordPage playerMatches(UUID playerId, Optional<String> cursor, int limit) {
        Optional<MatchRecordCursor> decodedCursor = cursor.map(MatchRecordCursor::decode);
        return repository.playerMatches(playerId, decodedCursor, clamp(limit, 1, 50));
    }

    public FeedPage publicFeed(Optional<String> cursor, int limit) {
        Optional<FeedCursor> decodedCursor = cursor.map(FeedCursor::decode);
        return repository.publicFeed(decodedCursor, clamp(limit, 1, 50));
    }

    public ProfilePage searchProfilesByHandle(String handlePrefix, Optional<String> cursor, int limit) {
        if (handlePrefix == null || handlePrefix.isBlank()) {
            throw new IllegalArgumentException("handle query must not be blank");
        }
        Optional<ProfileCursor> decodedCursor = cursor.map(ProfileCursor::decode);
        return repository.searchProfilesByHandle(handlePrefix.trim(), decodedCursor, clamp(limit, 1, 50));
    }

    public record NewPost(
            UUID id,
            UUID authorUserId,
            PostVisibility visibility,
            String markdown,
            String renderedHtml,
            int proseCharacterCount) {}

    public record NewResultPost(
            UUID id,
            UUID matchId,
            long sourceVersion,
            UUID authorUserId,
            String markdown,
            String renderedHtml,
            int proseCharacterCount) {}

    public record PostView(
            UUID id,
            UUID authorUserId,
            String authorHandle,
            PostVisibility visibility,
            String markdown,
            String renderedHtml,
            int proseCharacterCount,
            UUID matchId,
            Instant createdAt) {}

    public record FeedPage(List<PostView> posts, String nextCursor) {}

    public record MatchRecordView(
            UUID matchId,
            UUID playerOneUserId,
            UUID playerTwoUserId,
            String result,
            boolean ranked,
            long sourceVersion,
            Instant projectedAt,
            List<PlayerRecordEvidence> players) {

        public MatchRecordView(
                UUID matchId, UUID playerOneUserId, UUID playerTwoUserId, String result, boolean ranked,
                long sourceVersion, Instant projectedAt) {
            this(matchId, playerOneUserId, playerTwoUserId, result, ranked, sourceVersion, projectedAt, List.of());
        }
    }

    public record MatchRecordPage(List<MatchRecordView> matches, String nextCursor) {}

    /** Privacy-safe public profile projection: handle plus authoritative rating/tier when present. */
    public record ProfileView(UUID userId, String handle, Integer publicRating, String tier) {}

    public record ProfilePage(List<ProfileView> profiles, String nextCursor) {}

    public record ProfileCursor(String handle, UUID userId) {
        public String encode() {
            String raw = handle + "|" + userId;
            return java.util.Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static ProfileCursor decode(String cursor) {
            try {
                String raw = new String(
                        java.util.Base64.getUrlDecoder().decode(cursor),
                        java.nio.charset.StandardCharsets.UTF_8);
                // handle is the keyset column and may itself contain '|', so split off only the trailing id.
                int split = raw.lastIndexOf('|');
                return new ProfileCursor(raw.substring(0, split), UUID.fromString(raw.substring(split + 1)));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("profile cursor is invalid", exception);
            }
        }
    }

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

    public record MatchRecordCursor(Instant projectedAt, UUID matchId) {
        public String encode() {
            String raw = projectedAt + "|" + matchId;
            return java.util.Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static MatchRecordCursor decode(String cursor) {
            try {
                String raw = new String(
                        java.util.Base64.getUrlDecoder().decode(cursor),
                        java.nio.charset.StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", 2);
                return new MatchRecordCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("match record cursor is invalid", exception);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
