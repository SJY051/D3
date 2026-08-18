package com.ddd.d3.identity.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and marks {@code user-profile.changed} rows from {@code outbox_event}. Mirrors the Battle outbox
 * store; Identity emits a single event type, so the query is filtered to it and the publisher needs no
 * per-type topic routing.
 */
public final class JdbcIdentityOutboxStore {

    private static final String EVENT_TYPE = "user-profile.changed";

    private final JdbcClient jdbc;

    public JdbcIdentityOutboxStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public List<PendingProfileEvent> loadUnpublished(int maximumCount) {
        if (maximumCount <= 0 || maximumCount > 100) {
            throw new IllegalArgumentException("outbox batch size is out of range");
        }
        return jdbc.sql("""
                        select id, aggregate_id, payload::text
                        from outbox_event
                        where published_at is null and event_type = :eventType
                        order by occurred_at, id
                        limit :maximumCount
                        """)
                .param("eventType", EVENT_TYPE)
                .param("maximumCount", maximumCount)
                .query((rs, rowNumber) -> new PendingProfileEvent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class).toString(),
                        rs.getString("payload")))
                .list();
    }

    public int backfillMissingProfileEvents() {
        return jdbc.sql("""
                        with missing as materialized (
                            select ua.id as user_id, ua.handle, ua.updated_at, ua.profile_version, gen_random_uuid() as event_id
                            from user_account ua
                            where not exists (
                                select 1
                                from outbox_event outbox
                                where outbox.aggregate_id = ua.id
                                  and outbox.aggregate_version = ua.profile_version
                                  and outbox.event_type = :eventType
                            )
                        )
                        insert into outbox_event (
                            id, aggregate_id, aggregate_version, event_type, payload, occurred_at, published_at
                        )
                        select
                            missing.event_id,
                            missing.user_id,
                            missing.profile_version,
                            :eventType,
                            jsonb_build_object(
                                'eventId', missing.event_id::text,
                                'eventType', :eventType,
                                'version', 1,
                                'occurredAt', to_char(missing.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
                                'correlationId', missing.user_id::text,
                                'aggregateId', missing.user_id::text,
                                'aggregateVersion', missing.profile_version,
                                'data', jsonb_build_object(
                                    'userId', missing.user_id::text,
                                    'handle', missing.handle,
                                    'profileVersion', missing.profile_version
                                )
                            ),
                            missing.updated_at,
                            null
                        from missing
                        on conflict (aggregate_id, aggregate_version, event_type) do nothing
                        """)
                .param("eventType", EVENT_TYPE)
                .update();
    }

    public void markPublished(UUID eventId, Instant publishedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        int updated = jdbc.sql("""
                        update outbox_event
                        set published_at = :publishedAt
                        where id = :eventId and published_at is null and event_type = :eventType
                        """)
                .param("publishedAt", Timestamp.from(publishedAt))
                .param("eventId", eventId)
                .param("eventType", EVENT_TYPE)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("identity outbox event was not pending");
        }
    }

    /** An unpublished outbox row: the Kafka key is the aggregate id, the value is the stored envelope JSON. */
    public record PendingProfileEvent(UUID eventId, String aggregateId, String payload) {}
}
