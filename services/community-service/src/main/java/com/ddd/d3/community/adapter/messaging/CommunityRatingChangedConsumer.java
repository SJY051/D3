package com.ddd.d3.community.adapter.messaging;

import com.ddd.d3.community.adapter.persistence.JdbcProfileRatingStore;
import com.ddd.d3.community.adapter.persistence.JdbcProfileRatingStore.RatingChangedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code rating.changed.v1} from Battle and projects rating/RP/tier onto the matching
 * {@code profile_projection} row. The strict mapper and inbox-claim idempotency mirror
 * {@link CommunityMatchFinishedConsumer}; off-contract input is rejected at the trust boundary.
 */
@Component
public final class CommunityRatingChangedConsumer {

    private final JdbcProfileRatingStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommunityRatingChangedConsumer(
            JdbcProfileRatingStore store, ObjectMapper objectMapper, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
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
            topics = "${d3.community.rating-changed-topic:rating.changed.v1}",
            groupId = "${d3.community.rating-changed-group:${spring.application.name}-rating-changed}")
    public void receive(String payload) {
        RatingChangedEnvelope envelope = parse(payload);
        RatingChangedData data = envelope.data();
        store.apply(new RatingChangedEvent(
                envelope.eventId(),
                envelope.aggregateId(),
                envelope.aggregateVersion(),
                data.userId(),
                data.ratingAfter(),
                data.seasonRpAfter(),
                data.tierAfter(),
                clock.instant()));
    }

    private RatingChangedEnvelope parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("rating.changed payload must not be blank");
        }
        try {
            RatingChangedEnvelope envelope = objectMapper.readValue(payload, RatingChangedEnvelope.class);
            if (!"rating.changed".equals(envelope.eventType()) || envelope.version() != 1) {
                throw new IllegalArgumentException("unsupported rating.changed contract");
            }
            RatingChangedData data = envelope.data();
            if (envelope.aggregateVersion() < 0
                    || envelope.correlationId().isBlank()
                    || !envelope.aggregateId().equals(data.userId())
                    || data.seasonRpAfter() < 0
                    || data.tierAfter().isBlank()) {
                throw new IllegalArgumentException("invalid rating.changed payload");
            }
            return envelope;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("rating.changed payload is malformed", exception);
        }
    }

    record RatingChangedEnvelope(
            UUID eventId,
            String eventType,
            Integer version,
            Instant occurredAt,
            String correlationId,
            UUID aggregateId,
            Long aggregateVersion,
            RatingChangedData data) {

        RatingChangedEnvelope {
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

    record RatingChangedData(
            UUID userId,
            UUID matchId,
            Integer ratingBefore,
            Integer ratingAfter,
            Integer seasonRpAfter,
            String tierAfter) {

        RatingChangedData {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(matchId, "matchId");
            Objects.requireNonNull(ratingBefore, "ratingBefore");
            Objects.requireNonNull(ratingAfter, "ratingAfter");
            Objects.requireNonNull(seasonRpAfter, "seasonRpAfter");
            Objects.requireNonNull(tierAfter, "tierAfter");
        }
    }
}
