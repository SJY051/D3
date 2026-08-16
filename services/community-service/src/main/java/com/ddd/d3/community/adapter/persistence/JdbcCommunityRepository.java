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
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcCommunityRepository {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public JdbcCommunityRepository(JdbcClient jdbc, Clock clock, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = transaction;
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

    /** A match.finished.v1 event flattened to the columns the projection owns. */
    public record MatchFinishedProjection(
            UUID eventId,
            String eventType,
            UUID aggregateId,
            long aggregateVersion,
            UUID matchId,
            String result,
            boolean ranked,
            UUID playerOne,
            UUID playerTwo) {}

    /**
     * Claims the event in the inbox and upserts the ACTIVE match projection in one transaction.
     * At-least-once delivery is made exactly-once by the {@code inbox_event.event_id} primary key;
     * out-of-order redelivery is dropped by the {@code source_version} guard on conflict.
     *
     * @return true when this delivery applied the event, false when it was already claimed.
     */
    public boolean applyMatchFinished(MatchFinishedProjection event) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Instant now = clock.instant();
            // No arbiter: skip on ANY inbox uniqueness collision. Besides the event_id primary key,
            // inbox_event is unique on (aggregate_id, aggregate_version, event_type), so a re-emitted
            // event id for an already-applied aggregate version is also idempotently ignored. A named
            // arbiter would only cover event_id and would surface the second constraint as a race.
            int claimed = jdbc.sql("""
                            insert into inbox_event (
                                event_id, event_type, aggregate_id, aggregate_version, received_at
                            )
                            values (:eventId, :eventType, :aggregateId, :aggregateVersion, :now)
                            on conflict do nothing
                            """)
                    .param("eventId", event.eventId())
                    .param("eventType", event.eventType())
                    .param("aggregateId", event.aggregateId())
                    .param("aggregateVersion", event.aggregateVersion())
                    .param("now", java.sql.Timestamp.from(now))
                    .update();
            if (claimed == 0) {
                return false;
            }
            jdbc.sql("""
                            insert into match_projection (
                                match_id, player_one_user_id, player_two_user_id, result, ranked,
                                source_version, projected_at, projection_status
                            )
                            values (
                                :matchId, :playerOne, :playerTwo, :result, :ranked,
                                :sourceVersion, :now, 'ACTIVE'
                            )
                            on conflict (match_id) do update set
                                player_one_user_id = excluded.player_one_user_id,
                                player_two_user_id = excluded.player_two_user_id,
                                result = excluded.result,
                                ranked = excluded.ranked,
                                source_version = excluded.source_version,
                                projected_at = excluded.projected_at,
                                projection_status = 'ACTIVE'
                            where match_projection.source_version <= excluded.source_version
                            """)
                    .param("matchId", event.matchId())
                    .param("playerOne", event.playerOne())
                    .param("playerTwo", event.playerTwo())
                    .param("result", event.result())
                    .param("ranked", event.ranked())
                    .param("sourceVersion", event.aggregateVersion())
                    .param("now", java.sql.Timestamp.from(now))
                    .update();
            jdbc.sql("update inbox_event set applied_at = :now where event_id = :eventId")
                    .param("now", java.sql.Timestamp.from(now))
                    .param("eventId", event.eventId())
                    .update();
            return true;
        }));
    }
}
