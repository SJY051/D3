package com.ddd.d3.battle.adapter.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ddd.d3.battle.application.BattleConnectionService;
import com.ddd.d3.battle.application.BattleMatchView;
import com.ddd.d3.battle.application.BattleMatchViewService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class BattleSnapshotResynchronizerTest {

    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_ONE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLAYER_TWO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    @Test
    void d3Btl002RecoversAMissedRedisNotificationFromAuthoritativePostgres() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(1))
                .thenThrow(new IllegalStateException("postgres unavailable"))
                .thenReturn(view(2));
        BattleConnectionService connections = mock(BattleConnectionService.class);
        BattleWebSocketSessionRegistry sessions = new BattleWebSocketSessionRegistry(
                views,
                new BattleDisconnectRetryQueue(
                        connections,
                        mock(ScheduledExecutorService.class),
                        Duration.ofMillis(1)),
                new ObjectMapper());
        WebSocketSession session = session();
        sessions.register(session, 1);
        BattleSnapshotResynchronizer resynchronizer =
                new BattleSnapshotResynchronizer(sessions, Runnable::run);

        resynchronizer.resynchronize();
        resynchronizer.resynchronize();

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(messages.capture());
        ObjectMapper objectMapper = new ObjectMapper();
        assertEquals(1, objectMapper.readTree(messages.getAllValues().get(0).getPayload())
                .path("sequence")
                .asLong());
        assertEquals(2, objectMapper.readTree(messages.getAllValues().get(1).getPayload())
                .path("sequence")
                .asLong());
        verify(session, never()).close(org.springframework.web.socket.CloseStatus.SERVER_ERROR);
    }

    private static WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(BattleWebSocketHandshakeInterceptor.MATCH_ID_ATTRIBUTE, MATCH_ID);
        attributes.put(BattleWebSocketHandshakeInterceptor.VIEWER_ID_ATTRIBUTE, PLAYER_ONE);
        when(session.getId()).thenReturn("local-session");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static BattleMatchView view(long version) {
        return new BattleMatchView(
                MATCH_ID,
                version,
                NOW,
                BattleMatchView.State.RUNNING,
                NOW,
                NOW.plusSeconds(600),
                new BattleMatchView.Participant(
                        PLAYER_ONE, true, BattleMatchView.ConnectionState.CONNECTED, null),
                new BattleMatchView.Participant(
                        PLAYER_TWO, true, BattleMatchView.ConnectionState.CONNECTED, null),
                null);
    }
}
