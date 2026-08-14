package com.ddd.d3.battle.infrastructure.redis;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.ddd.d3.battle.application.BattleSnapshotPublisher;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisBattleSnapshotChannel implements BattleSnapshotPublisher, MessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisBattleSnapshotChannel.class);
    private final StringRedisTemplate redis;
    private final Consumer<UUID> localDelivery;
    private final Executor localExecutor;
    private final String topic;

    public RedisBattleSnapshotChannel(
            StringRedisTemplate redis,
            Consumer<UUID> localDelivery,
            Executor localExecutor,
            String topic) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.localDelivery = Objects.requireNonNull(localDelivery, "localDelivery must not be null");
        this.localExecutor = Objects.requireNonNull(localExecutor, "localExecutor must not be null");
        this.topic = requireTopic(topic);
    }

    @Override
    public void publish(UUID matchId) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        try {
            Long subscribers = redis.convertAndSend(topic, matchId.toString());
            if (subscribers == null || subscribers == 0) {
                scheduleLocal(matchId);
            }
        } catch (RuntimeException exception) {
            scheduleLocal(matchId);
            throw exception;
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            localDelivery.accept(UUID.fromString(new String(message.getBody(), UTF_8)));
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Ignored malformed Battle snapshot notification");
        }
    }

    private void scheduleLocal(UUID matchId) {
        localExecutor.execute(() -> localDelivery.accept(matchId));
    }

    private static String requireTopic(String topic) {
        Objects.requireNonNull(topic, "topic must not be null");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        return topic;
    }
}
