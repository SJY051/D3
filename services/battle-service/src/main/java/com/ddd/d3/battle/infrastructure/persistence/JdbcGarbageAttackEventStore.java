package com.ddd.d3.battle.infrastructure.persistence;

import com.ddd.d3.battle.application.GarbageAttackEventStore;
import com.ddd.d3.battle.domain.attack.GarbageAttackExchange;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public final class JdbcGarbageAttackEventStore implements GarbageAttackEventStore {

    private static final int CURRENT_PAYLOAD_VERSION = 1;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcGarbageAttackEventStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void lock(UUID matchId) {
        requireMatchId(matchId);
        boolean exists = jdbc.sql("select id from match where id = :matchId for update")
                .param("matchId", matchId)
                .query(UUID.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw new IllegalArgumentException("match does not exist: " + matchId);
        }
    }

    @Override
    public List<GarbageAttackExchange.AttackEvent> findByMatchId(UUID matchId) {
        requireMatchId(matchId);
        return jdbc.sql("""
                        select payload_version, event_payload::text
                        from attack_event
                        where match_id = :matchId
                        order by sequence
                        """)
                .param("matchId", matchId)
                .query((resultSet, rowNumber) -> deserialize(
                        resultSet.getInt("payload_version"), resultSet.getString("event_payload")))
                .list();
    }

    @Override
    public void append(UUID matchId, List<GarbageAttackExchange.AttackEvent> events) {
        requireMatchId(matchId);
        Objects.requireNonNull(events, "events must not be null");
        if (events.isEmpty()) {
            return;
        }

        List<UUID> participants = jdbc.sql("""
                        select user_id
                        from match_player
                        where match_id = :matchId
                        order by seat
                        """)
                .param("matchId", matchId)
                .query(UUID.class)
                .list();
        if (participants.size() != 2) {
            throw new IllegalArgumentException("match must have exactly two participants: " + matchId);
        }

        long expectedSequence = jdbc.sql("""
                        select coalesce(max(sequence), 0) + 1
                        from attack_event
                        where match_id = :matchId
                        """)
                .param("matchId", matchId)
                .query(Long.class)
                .single();

        List<UUID> actorIds = new ArrayList<>(events.size());
        for (GarbageAttackExchange.AttackEvent event : events) {
            Objects.requireNonNull(event, "events must not contain null");
            if (event.sequence() != expectedSequence) {
                throw new IllegalArgumentException(
                        "attack event sequence must be contiguous; expected "
                                + expectedSequence
                                + " but was "
                                + event.sequence());
            }
            actorIds.add(parseParticipant(event.playerId(), participants));
            expectedSequence++;
        }

        for (int index = 0; index < events.size(); index++) {
            UUID actorId = actorIds.get(index);
            UUID targetId = participants.get(0).equals(actorId) ? participants.get(1) : participants.get(0);
            insert(matchId, actorId, targetId, events.get(index));
        }
    }

    private void insert(
            UUID matchId,
            UUID actorId,
            UUID targetId,
            GarbageAttackExchange.AttackEvent event) {
        jdbc.sql("""
                        insert into attack_event (
                            id, match_id, sequence, actor_user_id, target_user_id,
                            attack_type, resolution, energy_cost, occurred_at,
                            payload_version, event_payload
                        ) values (
                            :id, :matchId, :sequence, :actorId, :targetId,
                            :attackType, :resolution, :energyCost, :occurredAt,
                            :payloadVersion, cast(:eventPayload as jsonb)
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("matchId", matchId)
                .param("sequence", event.sequence())
                .param("actorId", actorId)
                .param("targetId", targetId)
                .param("attackType", event.type().name())
                .param("resolution", diagnosticResolution(event))
                .param("energyCost", Math.max(0, -event.energyDelta()))
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .param("payloadVersion", CURRENT_PAYLOAD_VERSION)
                .param("eventPayload", serialize(event))
                .update();
    }

    private UUID parseParticipant(String playerId, List<UUID> participants) {
        final UUID parsed;
        try {
            parsed = UUID.fromString(playerId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("attack event playerId must be a UUID", exception);
        }
        if (!participants.contains(parsed)) {
            throw new IllegalArgumentException("attack event player is not a match participant: " + playerId);
        }
        return parsed;
    }

    private String diagnosticResolution(GarbageAttackExchange.AttackEvent event) {
        GarbageAttackExchange.AttackState state = event.attackState();
        if (state == null) {
            return event.type().name();
        }
        if (state.resolution() != null) {
            return state.resolution().name();
        }
        return state.phase().name();
    }

    private String serialize(GarbageAttackExchange.AttackEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("attack event could not be serialized", exception);
        }
    }

    private GarbageAttackExchange.AttackEvent deserialize(int payloadVersion, String payload) {
        if (payloadVersion != CURRENT_PAYLOAD_VERSION) {
            throw new IllegalStateException("unsupported attack event payload version: " + payloadVersion);
        }
        try {
            return objectMapper.readValue(payload, GarbageAttackExchange.AttackEvent.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("attack event payload could not be deserialized", exception);
        }
    }

    private static void requireMatchId(UUID matchId) {
        Objects.requireNonNull(matchId, "matchId must not be null");
    }
}
