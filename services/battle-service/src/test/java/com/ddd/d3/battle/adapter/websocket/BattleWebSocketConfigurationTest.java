package com.ddd.d3.battle.adapter.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ddd.d3.battle.application.RetryingBattleSnapshotPublisher;
import com.ddd.d3.battle.infrastructure.redis.RedisBattleSnapshotChannel;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

class BattleWebSocketConfigurationTest {

    @Test
    void d3Sec001RegistersOneExactOriginAndTheParticipantHandshakeGuard() {
        BattleWebSocketHandler handler = mock(BattleWebSocketHandler.class);
        BattleWebSocketHandshakeInterceptor interceptor = mock(BattleWebSocketHandshakeInterceptor.class);
        BattleWebSocketConfiguration configuration =
                new BattleWebSocketConfiguration(handler, interceptor, "http://localhost:5173");
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(handler, BattleWebSocketConfiguration.MATCH_PATH)).thenReturn(registration);
        when(registration.addInterceptors(interceptor)).thenReturn(registration);
        when(registration.setAllowedOrigins("http://localhost:5173")).thenReturn(registration);

        configuration.registerWebSocketHandlers(registry);

        verify(registry).addHandler(handler, BattleWebSocketConfiguration.MATCH_PATH);
        verify(registration).addInterceptors(interceptor);
        verify(registration).setAllowedOrigins("http://localhost:5173");
    }

    @Test
    void d3Sec001RejectsWildcardOrPathBasedOrigins() {
        BattleWebSocketHandler handler = mock(BattleWebSocketHandler.class);
        BattleWebSocketHandshakeInterceptor interceptor = mock(BattleWebSocketHandshakeInterceptor.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BattleWebSocketConfiguration(handler, interceptor, "https://*.example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BattleWebSocketConfiguration(handler, interceptor, "https://example.com/path"));
    }

    @Test
    void d3Btl002BoundsAsynchronousCrossInstanceFanoutWork() {
        BattleSnapshotFanoutConfiguration configuration = new BattleSnapshotFanoutConfiguration();
        ThreadPoolTaskExecutor executor = configuration.battleSnapshotFanoutExecutor();
        executor.initialize();
        try {
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(4, executor.getMaxPoolSize());
            assertEquals(256, executor.getThreadPoolExecutor().getQueue().remainingCapacity());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void d3Btl002BuildsTheRetryingPublisherWithoutEagerlyResolvingWebSocketSessions() {
        BattleSnapshotFanoutConfiguration configuration = new BattleSnapshotFanoutConfiguration();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BattleWebSocketSessionRegistry> sessions = mock(ObjectProvider.class);
        RedisBattleSnapshotChannel channel = configuration.battleSnapshotChannel(
                redis,
                sessions,
                Runnable::run,
                BattleSnapshotFanoutConfiguration.DEFAULT_SNAPSHOT_TOPIC);

        var publisher = configuration.battleSnapshotPublisher(
                channel, mock(ScheduledExecutorService.class), Duration.ofMillis(250));

        assertInstanceOf(RetryingBattleSnapshotPublisher.class, publisher);
        verifyNoInteractions(sessions);
    }
}
