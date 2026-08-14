package com.ddd.d3.battle.infrastructure.redis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisBattleSnapshotChannelTest {

    private static final String TOPIC = "d3:battle:snapshot:committed:v2";
    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void d3Btl002PublishesOnlyTheCommittedMatchIdentifier() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisBattleSnapshotChannel channel =
                new RedisBattleSnapshotChannel(redis, ignored -> {}, Runnable::run, TOPIC);

        channel.publish(MATCH_ID);

        verify(redis).convertAndSend(TOPIC, MATCH_ID.toString());
    }

    @Test
    void d3Btl002DeliversAValidCrossInstanceNotificationLocally() {
        AtomicReference<UUID> delivered = new AtomicReference<>();
        RedisBattleSnapshotChannel channel =
                new RedisBattleSnapshotChannel(
                        mock(StringRedisTemplate.class), delivered::set, Runnable::run, TOPIC);
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(MATCH_ID.toString().getBytes(UTF_8));

        channel.onMessage(message, TOPIC.getBytes(UTF_8));

        assertEquals(MATCH_ID, delivered.get());
    }

    @Test
    void d3Sec001IgnoresMalformedInternalNotifications() {
        AtomicReference<UUID> delivered = new AtomicReference<>();
        RedisBattleSnapshotChannel channel =
                new RedisBattleSnapshotChannel(
                        mock(StringRedisTemplate.class), delivered::set, Runnable::run, TOPIC);
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("not-a-match-id".getBytes(UTF_8));

        channel.onMessage(message, TOPIC.getBytes(UTF_8));

        assertNull(delivered.get());
    }
}
