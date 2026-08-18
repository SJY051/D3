package com.ddd.d3.community.adapter.messaging;

import com.ddd.d3.community.adapter.persistence.JdbcProfileIdentityStore;
import com.ddd.d3.community.adapter.persistence.JdbcProfileIdentityStore.UserProfileChangedEvent;
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
 * Consumes {@code user-profile.changed.v1} from Identity and projects handle/identity source version onto
 * the matching {@code profile_projection} row. The strict mapper and inbox-claim idempotency mirror
 * {@link CommunityRatingChangedConsumer}; off-contract input is rejected at the trust boundary.
 */
@Component
public final class CommunityUserProfileChangedConsumer {

    private final JdbcProfileIdentityStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommunityUserProfileChangedConsumer(
            JdbcProfileIdentityStore store, ObjectMapper objectMapper, Clock clock) {
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
            topics = "${d3.community.user-profile-changed-topic:user-profile.changed.v1}",
            groupId = "${d3.community.user-profile-changed-group:${spring.application.name}-user-profile-changed}")
    public void receive(String payload) {
        UserProfileChangedEnvelope envelope = parse(payload);
        UserProfileChangedData data = envelope.data();
        store.apply(new UserProfileChangedEvent(
                envelope.eventId(),
                envelope.aggregateId(),
                envelope.aggregateVersion(),
                data.userId(),
                data.handle(),
                clock.instant()));
    }

    private UserProfileChangedEnvelope parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("user-profile.changed payload must not be blank");
        }
        try {
            UserProfileChangedEnvelope envelope =
                    objectMapper.readValue(payload, UserProfileChangedEnvelope.class);
            if (!"user-profile.changed".equals(envelope.eventType()) || envelope.version() != 1) {
                throw new IllegalArgumentException("unsupported user-profile.changed contract");
            }
            UserProfileChangedData data = envelope.data();
            if (envelope.aggregateVersion() < 0
                    || envelope.correlationId().isBlank()
                    || !envelope.aggregateId().equals(data.userId())
                    || !envelope.aggregateVersion().equals(data.profileVersion())
                    || data.handle().isBlank()) {
                throw new IllegalArgumentException("invalid user-profile.changed payload");
            }
            return envelope;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("user-profile.changed payload is malformed", exception);
        }
    }

    record UserProfileChangedEnvelope(
            UUID eventId,
            String eventType,
            Integer version,
            Instant occurredAt,
            String correlationId,
            UUID aggregateId,
            Long aggregateVersion,
            UserProfileChangedData data) {

        UserProfileChangedEnvelope {
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

    record UserProfileChangedData(UUID userId, String handle, Long profileVersion) {

        UserProfileChangedData {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(profileVersion, "profileVersion");
        }
    }
}
