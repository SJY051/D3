package com.ddd.d3.community.adapter.persistence;

import com.ddd.d3.community.application.MatchFinishedProjectionService.ApplyResult;
import com.ddd.d3.community.application.MatchFinishedProjectionService.MatchFinishedEvent;
import com.ddd.d3.community.application.MatchFinishedProjectionService.Store;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class JdbcMatchProjectionStore implements Store {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcMatchProjectionStore(JdbcClient jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public JdbcMatchProjectionStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ApplyResult apply(MatchFinishedEvent event) {
        Objects.requireNonNull(event, "event");
        if (!claim(event)) {
            return ApplyResult.DUPLICATE_EVENT;
        }

        int projected = jdbc.sql("""
                        insert into match_projection (
                            match_id, player_one_user_id, player_two_user_id, projection_status,
                            result, ranked, player_records, source_version, projected_at
                        ) values (
                            :matchId, :playerOneId, :playerTwoId, 'ACTIVE',
                            :result, :ranked, cast(:playerRecords as jsonb), :sourceVersion, :projectedAt
                        )
                        on conflict (match_id) do update
                        set player_one_user_id = excluded.player_one_user_id,
                            player_two_user_id = excluded.player_two_user_id,
                            projection_status = 'ACTIVE',
                            result = excluded.result,
                            ranked = excluded.ranked,
                            player_records = excluded.player_records,
                            source_version = excluded.source_version,
                            projected_at = excluded.projected_at
                        where excluded.source_version > match_projection.source_version
                           or (match_projection.projection_status = 'REBUILD_REQUIRED'
                               and excluded.source_version = match_projection.source_version)
                        """)
                .param("matchId", event.matchId())
                .param("playerOneId", event.playerIds().get(0))
                .param("playerTwoId", event.playerIds().get(1))
                .param("result", event.result())
                .param("ranked", event.ranked())
                .param("playerRecords", serialize(event.players()))
                .param("sourceVersion", event.aggregateVersion())
                .param("projectedAt", Timestamp.from(event.receivedAt()))
                .update();

        if (projected == 1) {
            jdbc.sql("delete from match_projection_rebuild_queue where match_id = :matchId")
                    .param("matchId", event.matchId())
                    .update();
        }

        int applied = jdbc.sql("""
                        update inbox_event
                        set applied_at = :appliedAt
                        where event_id = :eventId and applied_at is null
                        """)
                .param("appliedAt", Timestamp.from(event.receivedAt()))
                .param("eventId", event.eventId())
                .update();
        if (applied != 1) {
            throw new IllegalStateException("Claimed match.finished event was not pending");
        }
        return projected == 1 ? ApplyResult.PROJECTION_APPLIED : ApplyResult.EVENT_APPLIED;
    }

    private boolean claim(MatchFinishedEvent event) {
        return jdbc.sql("""
                        insert into inbox_event (
                            event_id, event_type, aggregate_id, aggregate_version, received_at, applied_at
                        ) values (
                            :eventId, 'match.finished', :aggregateId, :aggregateVersion, :receivedAt, null
                        )
                        on conflict do nothing
                        """)
                .param("eventId", event.eventId())
                .param("aggregateId", event.aggregateId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("receivedAt", Timestamp.from(event.receivedAt()))
                .update() == 1;
    }

    private String serialize(List<?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("match record evidence could not be serialized", exception);
        }
    }
}
