package com.ddd.d3.community.adapter.messaging;

import com.ddd.d3.community.application.MatchFinishedProjectionService;
import com.ddd.d3.community.application.MatchFinishedProjectionService.MatchFinishedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

@Component
public final class CommunityMatchFinishedConsumer {

    private final MatchFinishedProjectionService projections;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommunityMatchFinishedConsumer(
            MatchFinishedProjectionService projections, ObjectMapper objectMapper, Clock clock) {
        this.projections = Objects.requireNonNull(projections, "projections");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .rebuild()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @KafkaListener(
            topics = "${d3.community.match-finished-topic:match.finished.v1}",
            groupId = "${d3.community.match-finished-group:${spring.application.name}-match-finished}")
    public void receive(String payload) {
        MatchFinishedEnvelope envelope = parse(payload);
        projections.receive(new MatchFinishedEvent(
                envelope.eventId(),
                envelope.aggregateId(),
                envelope.aggregateVersion(),
                envelope.data().matchId(),
                envelope.data().result(),
                envelope.data().ranked(),
                envelope.data().playerIds(),
                clock.instant()));
    }

    private MatchFinishedEnvelope parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("match.finished payload must not be blank");
        }
        try {
            MatchFinishedEnvelope envelope = objectMapper.readValue(payload, MatchFinishedEnvelope.class);
            if (!"match.finished".equals(envelope.eventType()) || envelope.version() != 1) {
                throw new IllegalArgumentException("unsupported match.finished contract");
            }
            if (envelope.aggregateVersion() < 0 || envelope.correlationId().isBlank()) {
                throw new IllegalArgumentException("invalid match.finished envelope");
            }
            return envelope;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("match.finished payload is malformed", exception);
        }
    }

    record MatchFinishedEnvelope(
            UUID eventId,
            String eventType,
            Integer version,
            Instant occurredAt,
            String correlationId,
            UUID aggregateId,
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
            playerIds = List.copyOf(playerIds);
        }
    }
}
