package com.ddd.d3.community.adapter.persistence;

import com.ddd.d3.community.application.CommunityService.FeedCursor;
import com.ddd.d3.community.application.CommunityService.FeedPage;
import com.ddd.d3.community.application.CommunityService.NewPost;
import com.ddd.d3.community.application.CommunityService.PostView;
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
                            :id, :authorUserId, cast(:visibility as community_post_visibility), :markdown,
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
                post.visibility(),
                post.markdown(),
                post.renderedHtml(),
                post.proseCharacterCount(),
                now);
    }

    public FeedPage publicFeed(Optional<FeedCursor> cursor, int limit) {
        String where = cursor.isPresent()
                ? "where visibility = 'PUBLIC' and (created_at, id) < (:cursorCreatedAt, :cursorId)"
                : "where visibility = 'PUBLIC'";
        var query = jdbc.sql("""
                        select id, author_user_id, visibility::text, prose_markdown, rendered_html,
                               prose_character_count, created_at
                        from post
                        %s
                        order by created_at desc, id desc
                        limit :limit
                        """.formatted(where))
                .param("limit", limit + 1);
        cursor.ifPresent(value -> query
                .param("cursorCreatedAt", java.sql.Timestamp.from(value.createdAt()))
                .param("cursorId", value.id()));

        List<PostView> rows = query.query((rs, rowNum) -> new PostView(
                rs.getObject("id", UUID.class),
                rs.getObject("author_user_id", UUID.class),
                PostVisibility.valueOf(rs.getString("visibility")),
                rs.getString("prose_markdown"),
                rs.getString("rendered_html"),
                rs.getInt("prose_character_count"),
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
}
