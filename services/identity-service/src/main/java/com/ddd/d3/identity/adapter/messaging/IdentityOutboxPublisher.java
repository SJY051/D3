package com.ddd.d3.identity.adapter.messaging;

import com.ddd.d3.identity.adapter.persistence.JdbcIdentityOutboxStore;
import com.ddd.d3.identity.adapter.persistence.JdbcIdentityOutboxStore.PendingProfileEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Relays committed {@code user-profile.changed.v1} outbox rows to Kafka and marks them published. Sends
 * are synchronous and each row is marked only after its send succeeds, so a crash re-publishes at least
 * once; the consumer's inbox claim makes redelivery idempotent. Single event type, so the whole loop
 * lives here rather than in a separate dispatcher and typed publisher.
 */
public final class IdentityOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdentityOutboxPublisher.class);
    private static final int BATCH_SIZE = 20;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final JdbcIdentityOutboxStore store;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final Clock clock;

    public IdentityOutboxPublisher(
            JdbcIdentityOutboxStore store,
            KafkaTemplate<String, String> kafkaTemplate,
            String topic,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
        this.topic = requireTopic(topic);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelayString = "${d3.identity.outbox-delay:500ms}")
    public void publishPendingEvents() {
        try {
            dispatchBatch();
        } catch (RuntimeException exception) {
            LOGGER.warn("Identity outbox dispatch failed with {}", exception.getClass().getSimpleName());
        }
    }

    /** @return the number of rows published in this batch. Package-visible so tests can drive one pass. */
    int dispatchBatch() {
        int published = 0;
        for (PendingProfileEvent event : store.loadUnpublished(BATCH_SIZE)) {
            send(event);
            store.markPublished(event.eventId(), clock.instant());
            published++;
        }
        return published;
    }

    private void send(PendingProfileEvent event) {
        try {
            kafkaTemplate
                    .send(topic, event.aggregateId(), event.payload())
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("identity event publication was interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("identity event publication failed", exception);
        }
    }

    private static String requireTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("identity event topic must not be blank");
        }
        return topic;
    }
}
