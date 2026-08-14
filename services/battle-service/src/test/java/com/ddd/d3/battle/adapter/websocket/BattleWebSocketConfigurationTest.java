package com.ddd.d3.battle.adapter.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
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
}
