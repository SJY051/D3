package com.ddd.d3.judge.adapter.messaging;

import com.ddd.d3.judge.application.JudgeEventPublisher;
import com.ddd.d3.judge.application.PendingJudgeEvent;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;

public final class KafkaJudgeEventPublisher implements JudgeEventPublisher {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaJudgeEventPublisher(KafkaTemplate<String, String> kafkaTemplate, String topic) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    @Override
    public void publish(PendingJudgeEvent event) {
        try {
            kafkaTemplate
                    .send(topic, event.aggregateId(), event.payload())
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("judge event publication was interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("judge event publication failed", exception);
        }
    }
}
