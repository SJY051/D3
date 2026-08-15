package com.ddd.d3.battle.infrastructure.messaging;

import com.ddd.d3.battle.application.BattleEventPublisher;
import com.ddd.d3.battle.application.PendingBattleEvent;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;

public final class KafkaBattleEventPublisher implements BattleEventPublisher {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Map<String, String> topics;

    public KafkaBattleEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            String matchFinishedTopic,
            String ratingChangedTopic) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
        this.topics = Map.of(
                "match.finished", requireTopic(matchFinishedTopic),
                "rating.changed", requireTopic(ratingChangedTopic));
    }

    @Override
    public void publish(PendingBattleEvent event) {
        String topic = topics.get(event.eventType());
        if (topic == null) {
            throw new IllegalArgumentException("unsupported Battle event type " + event.eventType());
        }
        try {
            kafkaTemplate
                    .send(topic, event.aggregateId(), event.payload())
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("battle event publication was interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("battle event publication failed", exception);
        }
    }

    private static String requireTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Battle event topic must not be blank");
        }
        return topic;
    }
}
