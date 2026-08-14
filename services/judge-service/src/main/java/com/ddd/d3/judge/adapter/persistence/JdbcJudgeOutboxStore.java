package com.ddd.d3.judge.adapter.persistence;

import com.ddd.d3.judge.application.JudgeOutboxStore;
import com.ddd.d3.judge.application.PendingJudgeEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcJudgeOutboxStore implements JudgeOutboxStore {

    private final JdbcClient jdbcClient;

    public JdbcJudgeOutboxStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public List<PendingJudgeEvent> loadUnpublished(int maximumCount) {
        if (maximumCount <= 0 || maximumCount > 100) {
            throw new IllegalArgumentException("outbox batch size is out of range");
        }
        return jdbcClient.sql("""
                        select id, aggregate_id, payload::text
                        from outbox_event
                        where published_at is null
                        order by occurred_at, id
                        limit :maximumCount
                        """)
                .param("maximumCount", maximumCount)
                .query((resultSet, rowNumber) -> new PendingJudgeEvent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("aggregate_id", UUID.class).toString(),
                        resultSet.getString("payload")))
                .list();
    }

    @Override
    public void markPublished(UUID eventId, Instant publishedAt) {
        int updated = jdbcClient.sql("""
                        update outbox_event set published_at = :publishedAt
                        where id = :eventId and published_at is null
                        """)
                .param("eventId", eventId)
                .param("publishedAt", Timestamp.from(publishedAt))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("outbox event was not pending");
        }
    }
}
