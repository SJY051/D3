package com.ddd.d3.community.adapter.persistence;

import com.ddd.d3.community.application.CommunityService.FeedCursor;
import com.ddd.d3.community.application.CommunityService.FollowState;
import com.ddd.d3.community.application.CommunityService.FeedPage;
import com.ddd.d3.community.application.CommunityService.MatchRecordCursor;
import com.ddd.d3.community.application.CommunityService.MatchRecordPage;
import com.ddd.d3.community.application.CommunityService.MatchRecordView;
import com.ddd.d3.community.application.CommunityService.NewPost;
import com.ddd.d3.community.application.CommunityService.NewResultPost;
import com.ddd.d3.community.application.CommunityService.PostView;
import com.ddd.d3.community.application.CommunityService.ProfileCursor;
import com.ddd.d3.community.application.CommunityService.ProfilePage;
import com.ddd.d3.community.application.CommunityService.ProfileView;
import com.ddd.d3.community.domain.PostVisibility;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcCommunityRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcCommunityRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public PostView insertPost(NewPost post) {
        Instant now = clock.instant();
        jdbc.sql("""
                        insert into post (
                            id, author_user_id, visibility, prose_markdown, rendered_html,
                            prose_character_count, created_at, updated_at
                        )
                        values (
                            :id, :authorUserId, :visibility, :markdown,
                            :renderedHtml, :proseCharacterCount, :createdAt, :updatedAt
                        )
                        """)
                .param("id", post.id())
                .param("authorUserId", post.authorUserId())
                .param("visibility", post.visibility().name())
                .param("markdown", post.markdown())
                .param("renderedHtml", post.renderedHtml())
                .param("proseCharacterCount", post.proseCharacterCount())
                .param("createdAt", java.sql.Timestamp.from(now))
                .param("updatedAt", java.sql.Timestamp.from(now))
                .update();
        return new PostView(
                post.id(),
                post.authorUserId(),
                authorHandle(post.authorUserId()),
                post.visibility(),
                post.markdown(),
                post.renderedHtml(),
                post.proseCharacterCount(),
                null,
                now);
    }

    public boolean insertResultPost(NewResultPost post) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        insert into post (
                            id, author_user_id, visibility, prose_markdown, rendered_html,
                            prose_character_count, match_projection_id, post_kind,
                            match_source_version, created_at, updated_at
                        )
                        values (
                            :id, :authorUserId, 'PUBLIC', :markdown, :renderedHtml,
                            :proseCharacterCount, :matchId, 'MATCH_RESULT',
                            :sourceVersion, :createdAt, :updatedAt
                        )
                        on conflict (match_projection_id) where post_kind = 'MATCH_RESULT' do nothing
                        """)
                .param("id", post.id())
                .param("authorUserId", post.authorUserId())
                .param("markdown", post.markdown())
                .param("renderedHtml", post.renderedHtml())
                .param("proseCharacterCount", post.proseCharacterCount())
                .param("matchId", post.matchId())
                .param("sourceVersion", post.sourceVersion())
                .param("createdAt", java.sql.Timestamp.from(now))
                .param("updatedAt", java.sql.Timestamp.from(now))
                .update() == 1;
    }

    public FeedPage publicFeed(Optional<FeedCursor> cursor, int limit) {
        String where = cursor.isPresent()
                ? "where post.visibility = 'PUBLIC' and (post.created_at, post.id) < (:cursorCreatedAt, :cursorId)"
                : "where post.visibility = 'PUBLIC'";
        var query = jdbc.sql("""
                        select post.id, post.author_user_id, profile.handle, post.visibility::text,
                               post.prose_markdown, post.rendered_html, post.prose_character_count,
                               post.match_projection_id, post.created_at
                        from post
                        left join profile_projection profile on profile.user_id = post.author_user_id
                        %s
                        order by post.created_at desc, post.id desc
                        limit :limit
                        """.formatted(where))
                .param("limit", limit + 1);
        cursor.ifPresent(value -> query
                .param("cursorCreatedAt", java.sql.Timestamp.from(value.createdAt()))
                .param("cursorId", value.id()));

        List<PostView> rows = query.query((rs, rowNum) -> new PostView(
                rs.getObject("id", UUID.class),
                rs.getObject("author_user_id", UUID.class),
                rs.getString("handle"),
                PostVisibility.valueOf(rs.getString("visibility")),
                rs.getString("prose_markdown"),
                rs.getString("rendered_html"),
                rs.getInt("prose_character_count"),
                rs.getObject("match_projection_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()))
                .list();
        String nextCursor = null;
        if (rows.size() > limit) {
            rows.removeLast();
            PostView last = rows.getLast();
            nextCursor = new FeedCursor(last.createdAt(), last.id()).encode();
        }
        return new FeedPage(rows, nextCursor);
    }

    private String authorHandle(UUID authorUserId) {
        return jdbc.sql("""
                        select handle
                        from profile_projection
                        where user_id = :authorUserId
                        """)
                .param("authorUserId", authorUserId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public void insertFollow(UUID followerUserId, UUID followedUserId) {
        jdbc.sql("""
                        insert into follow (follower_user_id, followed_user_id, created_at)
                        values (:follower, :followed, :createdAt)
                        on conflict (follower_user_id, followed_user_id) do nothing
                        """)
                .param("follower", followerUserId)
                .param("followed", followedUserId)
                .param("createdAt", java.sql.Timestamp.from(clock.instant()))
                .update();
    }

    public void deleteFollow(UUID followerUserId, UUID followedUserId) {
        jdbc.sql("delete from follow where follower_user_id = :follower and followed_user_id = :followed")
                .param("follower", followerUserId)
                .param("followed", followedUserId)
                .update();
    }

    public FollowState followState(UUID targetUserId, Optional<UUID> viewerUserId) {
        long followers = count("select count(*) from follow where followed_user_id = :id", targetUserId);
        long following = count("select count(*) from follow where follower_user_id = :id", targetUserId);
        boolean viewerFollowing = viewerUserId.map(viewer -> jdbc.sql("""
                        select exists (
                            select 1 from follow
                            where follower_user_id = :viewer and followed_user_id = :target
                        )
                        """)
                .param("viewer", viewer)
                .param("target", targetUserId)
                .query(Boolean.class)
                .single())
                .orElse(false);
        return new FollowState(followers, following, viewerFollowing);
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    public ProfilePage searchProfilesByHandle(String handlePrefix, Optional<ProfileCursor> cursor, int limit) {
        // Only rows the identity projection has filled (handle not null) are searchable; a rating-first row
        // without a handle is not yet a discoverable profile. Keyset ascends (handle, user_id).
        String cursorClause = cursor.isPresent() ? "and (handle, user_id) > (:cursorHandle, :cursorUserId)" : "";
        var query = jdbc.sql("""
                        select user_id, handle, public_rating, tier
                        from profile_projection
                        where handle is not null
                          and handle like :prefix escape '\\'
                        %s
                        order by handle asc, user_id asc
                        limit :limit
                        """.formatted(cursorClause))
                .param("prefix", likePrefix(handlePrefix))
                .param("limit", limit + 1);
        cursor.ifPresent(value -> query
                .param("cursorHandle", value.handle())
                .param("cursorUserId", value.userId()));

        List<ProfileView> rows = query.query((rs, rowNum) -> new ProfileView(
                rs.getObject("user_id", UUID.class),
                rs.getString("handle"),
                (Integer) rs.getObject("public_rating"),
                rs.getString("tier")))
                .list();
        String nextCursor = null;
        if (rows.size() > limit) {
            rows.removeLast();
            ProfileView last = rows.getLast();
            nextCursor = new ProfileCursor(last.handle(), last.userId()).encode();
        }
        return new ProfilePage(rows, nextCursor);
    }

    /** Escapes LIKE metacharacters so a caller's handle prefix is matched literally, then appends the wildcard. */
    private static String likePrefix(String prefix) {
        String escaped = prefix
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return escaped + "%";
    }

    public Optional<MatchRecordView> matchRecord(UUID matchId) {
        return jdbc.sql("""
                        select match_id, player_one_user_id, player_two_user_id, result,
                               ranked, source_version, projected_at
                        from match_projection
                        where match_id = :matchId
                          and projection_status = 'ACTIVE'
                        """)
                .param("matchId", matchId)
                .query(JdbcCommunityRepository::mapMatchRecord)
                .optional();
    }

    public MatchRecordPage playerMatches(UUID playerId, Optional<MatchRecordCursor> cursor, int limit) {
        String cursorClause = cursor.isPresent()
                ? "and (projected_at, match_id) < (:cursorProjectedAt, :cursorMatchId)"
                : "";
        var query = jdbc.sql("""
                        select match_id, player_one_user_id, player_two_user_id, result,
                               ranked, source_version, projected_at
                        from match_projection
                        where projection_status = 'ACTIVE'
                          and (player_one_user_id = :playerId or player_two_user_id = :playerId)
                        %s
                        order by projected_at desc, match_id desc
                        limit :limit
                        """.formatted(cursorClause))
                .param("playerId", playerId)
                .param("limit", limit + 1);
        cursor.ifPresent(value -> query
                .param("cursorProjectedAt", java.sql.Timestamp.from(value.projectedAt()))
                .param("cursorMatchId", value.matchId()));

        List<MatchRecordView> rows = query.query(JdbcCommunityRepository::mapMatchRecord).list();
        String nextCursor = null;
        if (rows.size() > limit) {
            rows.removeLast();
            MatchRecordView last = rows.getLast();
            nextCursor = new MatchRecordCursor(last.projectedAt(), last.matchId()).encode();
        }
        return new MatchRecordPage(rows, nextCursor);
    }

    private static MatchRecordView mapMatchRecord(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new MatchRecordView(
                resultSet.getObject("match_id", UUID.class),
                resultSet.getObject("player_one_user_id", UUID.class),
                resultSet.getObject("player_two_user_id", UUID.class),
                resultSet.getString("result"),
                resultSet.getBoolean("ranked"),
                resultSet.getLong("source_version"),
                resultSet.getTimestamp("projected_at").toInstant());
    }
}
