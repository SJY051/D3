package com.ddd.d3.community.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.community.application.CommunityService.NewComment;
import com.ddd.d3.community.application.CommunityService.NewPost;
import com.ddd.d3.community.application.CommunityService.NewResultPost;
import com.ddd.d3.community.domain.PostVisibility;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class JdbcCommunityRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    private static final UUID USER_ONE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER_TWO = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID POST_ONE = UUID.fromString("33333333-3333-4333-8333-333333333331");
    private static final UUID MATCH_ONE = UUID.fromString("44444444-4444-4444-8444-444444444441");
    private static final UUID MATCH_TWO = UUID.fromString("44444444-4444-4444-8444-444444444442");
    private static final UUID MATCH_REBUILD = UUID.fromString("44444444-4444-4444-8444-444444444443");
    private static final UUID POST_TWO = UUID.fromString("33333333-3333-4333-8333-333333333332");
    private JdbcClient jdbc;
    private JdbcCommunityRepository repository;

    @BeforeEach
    void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcCommunityRepository(
                jdbc,
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void d3Com001RecognizesAllAudiencesButFeedsOnlyPublicPostsWithKeysetPagination() {
        repository.insertPost(new NewPost(POST_ONE, USER_ONE, PostVisibility.PUBLIC, "one", "<p>one</p>", 3));
        repository.insertPost(new NewPost(POST_TWO, USER_TWO, PostVisibility.PUBLIC, "two", "<p>two</p>", 3));
        jdbc.sql("""
                        insert into post (
                            id, author_user_id, visibility, prose_markdown, rendered_html,
                            prose_character_count, created_at, updated_at
                        )
                        values (:id, :authorUserId, 'PRIVATE', 'hidden', '<p>hidden</p>', 6, now(), now())
                        """)
                .param("id", UUID.fromString("33333333-3333-4333-8333-333333333333"))
                .param("authorUserId", USER_ONE)
                .update();

        var firstPage = repository.publicFeed(Optional.empty(), 1);
        assertEquals(1, firstPage.posts().size());
        assertEquals(POST_TWO, firstPage.posts().getFirst().id());
        assertFalse(firstPage.nextCursor().isBlank());

        var secondPage = repository.publicFeed(Optional.of(new com.ddd.d3.community.application.CommunityService.FeedCursor(
                firstPage.posts().getFirst().createdAt(), firstPage.posts().getFirst().id())), 2);
        assertEquals(List.of(POST_ONE), secondPage.posts().stream().map(post -> post.id()).toList());
        assertNull(secondPage.nextCursor());
    }

    @Test
    void d3Com001ProjectsAuthorHandlesForRegularAndResultPostsWithoutAnIdentityDatabaseJoin() {
        seedProfile(USER_ONE, "alice", 1450, "GOLD", 1L);
        insertMatch(MATCH_ONE, USER_ONE, USER_TWO, "PLAYER_ONE_WIN", "ACTIVE", 7, "2026-08-14T00:00:00Z");
        repository.insertPost(new NewPost(POST_ONE, USER_ONE, PostVisibility.PUBLIC, "regular", "<p>regular</p>", 7));
        repository.insertResultPost(new NewResultPost(
                POST_TWO, MATCH_ONE, 7, USER_ONE, "result", "<p>result</p>", 6));

        var posts = repository.publicFeed(Optional.empty(), 10).posts();
        assertEquals(2, posts.size());
        assertEquals("alice", posts.get(0).authorHandle());
        assertEquals("alice", posts.get(1).authorHandle());
        assertEquals(MATCH_ONE, posts.get(0).matchId());
        assertNull(posts.get(1).matchId());
    }

    @Test
    void d3Stat001ReadsOnlyActivePlayerMatchesWithStableKeysetPagination() {
        insertMatch(MATCH_ONE, USER_ONE, USER_TWO, "PLAYER_ONE_WIN", "ACTIVE", 7, "2026-08-16T01:00:00Z");
        insertMatch(MATCH_TWO, USER_TWO, USER_ONE, "DRAW", "ACTIVE", 8, "2026-08-16T02:00:00Z");
        insertMatch(MATCH_REBUILD, null, null, "VOIDED", "REBUILD_REQUIRED", 9, "2026-08-16T03:00:00Z");

        var firstPage = repository.playerMatches(USER_ONE, Optional.empty(), 1);
        assertEquals(List.of(MATCH_TWO), firstPage.matches().stream().map(match -> match.matchId()).toList());
        assertFalse(firstPage.nextCursor().isBlank());

        var cursor = new com.ddd.d3.community.application.CommunityService.MatchRecordCursor(
                firstPage.matches().getFirst().projectedAt(),
                firstPage.matches().getFirst().matchId());
        var secondPage = repository.playerMatches(USER_ONE, Optional.of(cursor), 1);
        assertEquals(List.of(MATCH_ONE), secondPage.matches().stream().map(match -> match.matchId()).toList());
        assertNull(secondPage.nextCursor());

        assertEquals(MATCH_ONE, repository.matchRecord(MATCH_ONE).orElseThrow().matchId());
        assertFalse(repository.matchRecord(MATCH_REBUILD).isPresent());
    }

    private void insertMatch(
            UUID matchId,
            UUID playerOne,
            UUID playerTwo,
            String result,
            String status,
            long sourceVersion,
            String projectedAt) {
        jdbc.sql("""
                        insert into match_projection (
                            match_id, player_one_user_id, player_two_user_id, projection_status,
                            result, ranked, source_version, projected_at
                        ) values (
                            :matchId, :playerOne, :playerTwo, :status,
                            :result, true, :sourceVersion, :projectedAt
                        )
                        """)
                .param("matchId", matchId)
                .param("playerOne", playerOne)
                .param("playerTwo", playerTwo)
                .param("status", status)
                .param("result", result)
                .param("sourceVersion", sourceVersion)
                .param("projectedAt", java.sql.Timestamp.from(Instant.parse(projectedAt)))
                .update();
    }

    @Test
    void d3Stat001SearchesHandlesByPrefixWithKeysetAndExcludesRatingFirstRows() {
        seedProfile(USER_ONE, "alice", 1450, "GOLD", 1L);
        seedProfile(USER_TWO, "alan", 1600, "PLATINUM", 1L);
        seedProfile(UUID.fromString("55555555-5555-4555-8555-555555555551"), "bob", 1200, "SILVER", 1L);
        // A rating-first row without a handle is not yet a discoverable profile.
        jdbc.sql("""
                        insert into profile_projection (user_id, public_rating, rp, tier, rating_source_version, projected_at)
                        values (:id, 1300, 30, 'SILVER', 2, now())
                        """)
                .param("id", UUID.fromString("55555555-5555-4555-8555-555555555552"))
                .update();

        var first = repository.searchProfilesByHandle("al", Optional.empty(), 1);
        assertEquals(1, first.profiles().size());
        // Ascending (handle, user_id): "alan" sorts before "alice".
        var top = first.profiles().getFirst();
        assertEquals("alan", top.handle());
        assertEquals(1600, top.publicRating());
        assertFalse(first.nextCursor().isBlank());

        var cursor = new com.ddd.d3.community.application.CommunityService.ProfileCursor(top.handle(), top.userId());
        var second = repository.searchProfilesByHandle("al", Optional.of(cursor), 10);
        assertEquals(List.of("alice"), second.profiles().stream().map(p -> p.handle()).toList());
        assertNull(second.nextCursor());
    }

    @Test
    void d3Sec001TreatsHandleWildcardsAsLiteralText() {
        seedProfile(USER_ONE, "a_b", 1000, "BRONZE", 1L);
        seedProfile(USER_TWO, "axb", 1000, "BRONZE", 1L);

        // '_' is a LIKE wildcard; escaped, "a_" must match only the literal "a_b", not "axb".
        var page = repository.searchProfilesByHandle("a_", Optional.empty(), 10);
        assertEquals(List.of("a_b"), page.profiles().stream().map(p -> p.handle()).toList());
    }

    @Test
    void d3Com001FollowIsIdempotentAndReportsDirectionalCountsAndViewerState() {
        UUID userThree = UUID.fromString("55555555-5555-4555-8555-555555555553");
        repository.insertFollow(USER_ONE, USER_TWO);
        repository.insertFollow(USER_ONE, USER_TWO); // idempotent: no duplicate, no error
        repository.insertFollow(userThree, USER_TWO);

        var followed = repository.followState(USER_TWO, Optional.of(USER_ONE));
        assertEquals(2, followed.followerCount());
        assertEquals(0, followed.followingCount());
        assertTrue(followed.viewerFollowing());

        var follower = repository.followState(USER_ONE, Optional.of(USER_TWO));
        assertEquals(0, follower.followerCount());
        assertEquals(1, follower.followingCount());
        assertFalse(follower.viewerFollowing());

        repository.deleteFollow(USER_ONE, USER_TWO);
        repository.deleteFollow(USER_ONE, USER_TWO); // idempotent delete
        var afterUnfollow = repository.followState(USER_TWO, Optional.of(USER_ONE));
        assertEquals(1, afterUnfollow.followerCount());
        assertFalse(afterUnfollow.viewerFollowing());
    }

    @Test
    void d3Com001LikeIsIdempotentAndCountsPerViewer() {
        repository.insertPost(new NewPost(POST_ONE, USER_ONE, PostVisibility.PUBLIC, "post", "<p>post</p>", 4));
        assertTrue(repository.publicPostExists(POST_ONE));

        repository.insertLike(USER_TWO, POST_ONE);
        repository.insertLike(USER_TWO, POST_ONE); // idempotent
        repository.insertLike(USER_ONE, POST_ONE);

        var state = repository.likeState(POST_ONE, Optional.of(USER_TWO));
        assertEquals(2, state.likeCount());
        assertTrue(state.viewerLiked());
        assertFalse(repository.likeState(POST_ONE, Optional.empty()).viewerLiked());

        repository.deleteLike(USER_TWO, POST_ONE);
        repository.deleteLike(USER_TWO, POST_ONE); // idempotent
        assertEquals(1, repository.likeState(POST_ONE, Optional.of(USER_TWO)).likeCount());
    }

    @Test
    void d3Com001TreatsOnlyPublicPostsAsLikeable() {
        jdbc.sql("""
                        insert into post (
                            id, author_user_id, visibility, prose_markdown, rendered_html,
                            prose_character_count, created_at, updated_at
                        )
                        values (:id, :author, 'PRIVATE', 'hidden', '<p>hidden</p>', 6, now(), now())
                        """)
                .param("id", POST_ONE)
                .param("author", USER_ONE)
                .update();
        assertFalse(repository.publicPostExists(POST_ONE));
        assertFalse(repository.publicPostExists(POST_TWO)); // absent post
    }

    @Test
    void d3Com001CommentsReadOldestFirstWithKeysetAndProjectedHandle() {
        repository.insertPost(new NewPost(POST_ONE, USER_ONE, PostVisibility.PUBLIC, "post", "<p>post</p>", 4));
        seedProfile(USER_TWO, "bob", 1200, "SILVER", 1L);
        UUID commentOne = UUID.fromString("66666666-6666-4666-8666-666666666661");
        UUID commentTwo = UUID.fromString("66666666-6666-4666-8666-666666666662");
        repository.insertComment(new NewComment(commentOne, POST_ONE, USER_TWO, "first", "<p>first</p>"));
        repository.insertComment(new NewComment(commentTwo, POST_ONE, USER_ONE, "second", "<p>second</p>"));

        var firstPage = repository.postComments(POST_ONE, Optional.empty(), 1);
        assertEquals(1, firstPage.comments().size());
        assertEquals(commentOne, firstPage.comments().getFirst().id());
        assertEquals("bob", firstPage.comments().getFirst().authorHandle());
        assertEquals("<p>first</p>", firstPage.comments().getFirst().renderedHtml());
        assertFalse(firstPage.nextCursor().isBlank());

        var cursor = new com.ddd.d3.community.application.CommunityService.CommentCursor(
                firstPage.comments().getFirst().createdAt(), firstPage.comments().getFirst().id());
        var secondPage = repository.postComments(POST_ONE, Optional.of(cursor), 10);
        assertEquals(List.of(commentTwo), secondPage.comments().stream().map(c -> c.id()).toList());
        assertNull(secondPage.nextCursor());
    }

    @Test
    void d3Com001DeletesOnlyTheAuthorsOwnComment() {
        repository.insertPost(new NewPost(POST_ONE, USER_ONE, PostVisibility.PUBLIC, "post", "<p>post</p>", 4));
        UUID commentId = UUID.fromString("66666666-6666-4666-8666-666666666663");
        repository.insertComment(new NewComment(commentId, POST_ONE, USER_ONE, "mine", "<p>mine</p>"));

        assertFalse(repository.deleteComment(commentId, USER_TWO)); // not the author
        assertTrue(repository.deleteComment(commentId, USER_ONE)); // author
        assertFalse(repository.deleteComment(commentId, USER_ONE)); // already gone
    }

    private void seedProfile(UUID userId, String handle, int rating, String tier, long identityVersion) {
        jdbc.sql("""
                        insert into profile_projection (
                            user_id, handle, public_rating, rp, tier,
                            identity_source_version, rating_source_version, projected_at
                        ) values (
                            :id, :handle, :rating, 0, :tier, :identityVersion, 1, now()
                        )
                        """)
                .param("id", userId)
                .param("handle", handle)
                .param("rating", rating)
                .param("tier", tier)
                .param("identityVersion", identityVersion)
                .update();
    }
}
