package com.ddd.d3.community.adapter.messaging;

import com.ddd.d3.community.adapter.persistence.JdbcCommunityRepository;
import com.ddd.d3.community.adapter.persistence.JdbcCommunityRepository.MatchFinishedProjection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code match.finished.v1} envelopes from Battle and projects the ACTIVE match record.
 * The envelope is bound to typed records so a malformed or off-contract message is rejected at the
 * trust boundary instead of coercing bad values; idempotency and out-of-order handling live in
 * {@link JdbcCommunityRepository#applyMatchFinished}.
 */
@Component
public final class MatchFinishedListener {

    private static final Set<String> RESULTS =
            Set.of("PLAYER_ONE_WIN", "PLAYER_TWO_WIN", "DRAW", "VOIDED");

    private final JdbcCommunityRepository repository;
    private final ObjectMapper objectMapper;

    public MatchFinishedListener(JdbcCommunityRepository repository, ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @KafkaListener(
            topics = "${d3.community.match-finished-topic:match.finished.v1}",
            groupId = "${spring.kafka.consumer.group-id:community-service}")
    public void onMatchFinished(String payload) {
        repository.applyMatchFinished(parse(payload, objectMapper));
    }

    static MatchFinishedProjection parse(String payload, ObjectMapper objectMapper) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("match.finished payload must not be blank");
        }
        MatchFinishedEnvelope envelope;
        try {
            envelope = objectMapper.readValue(payload, MatchFinishedEnvelope.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("match.finished payload is malformed", exception);
        }
        if (!"match.finished".equals(envelope.eventType()) || envelope.version() != 1) {
            throw new IllegalArgumentException("unsupported match.finished contract");
        }
        MatchFinishedData data = envelope.data();
        UUID aggregateId = UUID.fromString(envelope.aggregateId());
        List<UUID> playerIds = data.playerIds();
        if (envelope.aggregateVersion() < 0
                || envelope.correlationId().isBlank()
                || !aggregateId.equals(data.matchId())
                || !RESULTS.contains(data.result())
                || playerIds.size() != 2
                || playerIds.get(0) == null
                || playerIds.get(1) == null
                || playerIds.get(0).equals(playerIds.get(1))) {
            throw new IllegalArgumentException("invalid match.finished payload");
        }
        return new MatchFinishedProjection(
                envelope.eventId(),
                envelope.eventType(),
                aggregateId,
                envelope.aggregateVersion(),
                data.matchId(),
                data.result(),
                data.ranked(),
                playerIds.get(0),
                playerIds.get(1));
    }

    record MatchFinishedEnvelope(
            UUID eventId,
            String eventType,
            Integer version,
            Instant occurredAt,
            String correlationId,
            String aggregateId,
            Long aggregateVersion,
            MatchFinishedData data) {
        MatchFinishedEnvelope {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(aggregateVersion, "aggregateVersion");
            Objects.requireNonNull(data, "data");
        }
    }

    record MatchFinishedData(UUID matchId, String result, Boolean ranked, List<UUID> playerIds) {
        MatchFinishedData {
            Objects.requireNonNull(matchId, "matchId");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(ranked, "ranked");
            Objects.requireNonNull(playerIds, "playerIds");
        }
    }
}
