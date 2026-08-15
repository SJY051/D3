package com.ddd.d3.battle.infrastructure.persistence;

import com.ddd.d3.battle.application.BattleOutboxStore;
import com.ddd.d3.battle.application.PendingBattleEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBattleOutboxStore implements BattleOutboxStore {

    private final JdbcClient jdbc;

    public JdbcBattleOutboxStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public List<PendingBattleEvent> loadUnpublished(int maximumCount) {
        if (maximumCount <= 0 || maximumCount > 100) {
            throw new IllegalArgumentException("outbox batch size is out of range");
        }
        return jdbc.sql("""
                        select id, event_type, aggregate_id, payload::text
                        from outbox_event
                        where published_at is null
                          and event_type in ('match.finished', 'rating.changed')
                        order by occurred_at, id
                        limit :maximumCount
                        """)
                .param("maximumCount", maximumCount)
                .query((resultSet, rowNumber) -> new PendingBattleEvent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getObject("aggregate_id", UUID.class).toString(),
                        resultSet.getString("payload")))
                .list();
    }

    @Override
    public void markPublished(UUID eventId, Instant publishedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        int updated = jdbc.sql("""
                        update outbox_event
                        set published_at = :publishedAt
                        where id = :eventId
                          and published_at is null
                          and event_type in ('match.finished', 'rating.changed')
                        """)
                .param("publishedAt", Timestamp.from(publishedAt))
                .param("eventId", eventId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Battle outbox event was not pending");
        }
    }
}
