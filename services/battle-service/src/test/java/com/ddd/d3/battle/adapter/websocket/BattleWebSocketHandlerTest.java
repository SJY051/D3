package com.ddd.d3.battle.adapter.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ddd.d3.battle.application.BattleMatchView;
import com.ddd.d3.battle.application.BattleMatchViewService;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class BattleWebSocketHandlerTest {

    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_ONE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLAYER_TWO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void d3Btl002SendsPrivateCurrentStateAndOnlyNewerCommittedVersions() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 2, BattleMatchView.ConnectionState.DISCONNECTED))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 2, BattleMatchView.ConnectionState.DISCONNECTED));
        BattleWebSocketHandler handler = new BattleWebSocketHandler(views, objectMapper);
        WebSocketSession session = session("one", PLAYER_ONE);

        handler.afterConnectionEstablished(session);
        handler.publishLocal(MATCH_ID);
        handler.publishLocal(MATCH_ID);

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(messages.capture());
        JsonNode initial = objectMapper.readTree(messages.getAllValues().get(0).getPayload());
        JsonNode latest = objectMapper.readTree(messages.getAllValues().get(1).getPayload());
        assertEquals(1, initial.path("sequence").asLong());
        assertEquals(2, latest.path("sequence").asLong());
        assertEquals(PLAYER_ONE.toString(), latest.path("payload").path("self").path("playerId").asText());
        assertEquals(
                "DISCONNECTED",
                latest.path("payload").path("opponent").path("connectionState").asText());
        assertFalse(latest.toString().contains(PLAYER_TWO.toString()));
    }

    @Test
    void d3Sec001ProjectsTheSameMatchSeparatelyForBothParticipants() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 4, BattleMatchView.ConnectionState.CONNECTED));
        when(views.read(MATCH_ID, PLAYER_TWO))
                .thenReturn(view(PLAYER_TWO, PLAYER_ONE, 4, BattleMatchView.ConnectionState.CONNECTED));
        BattleWebSocketHandler handler = new BattleWebSocketHandler(views, objectMapper);
        WebSocketSession first = session("one", PLAYER_ONE);
        WebSocketSession second = session("two", PLAYER_TWO);

        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);

        ArgumentCaptor<TextMessage> firstMessage = ArgumentCaptor.forClass(TextMessage.class);
        ArgumentCaptor<TextMessage> secondMessage = ArgumentCaptor.forClass(TextMessage.class);
        verify(first).sendMessage(firstMessage.capture());
        verify(second).sendMessage(secondMessage.capture());
        JsonNode firstJson = objectMapper.readTree(firstMessage.getValue().getPayload());
        JsonNode secondJson = objectMapper.readTree(secondMessage.getValue().getPayload());
        assertEquals(PLAYER_ONE.toString(), firstJson.path("payload").path("self").path("playerId").asText());
        assertEquals(PLAYER_TWO.toString(), secondJson.path("payload").path("self").path("playerId").asText());
        assertFalse(firstJson.toString().contains(PLAYER_TWO.toString()));
        assertFalse(secondJson.toString().contains(PLAYER_ONE.toString()));
    }

    @Test
    void d3Btl002ReplaysTheLatestAuthoritativeSnapshotAfterReconnect() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 5, BattleMatchView.ConnectionState.CONNECTED));
        BattleWebSocketHandler handler = new BattleWebSocketHandler(views, objectMapper);
        WebSocketSession disconnected = session("old", PLAYER_ONE);
        WebSocketSession reconnected = session("new", PLAYER_ONE);

        handler.afterConnectionEstablished(disconnected);
        handler.afterConnectionClosed(disconnected, CloseStatus.GOING_AWAY);
        handler.afterConnectionEstablished(reconnected);

        ArgumentCaptor<TextMessage> replay = ArgumentCaptor.forClass(TextMessage.class);
        verify(reconnected).sendMessage(replay.capture());
        assertEquals(5, objectMapper.readTree(replay.getValue().getPayload()).path("sequence").asLong());
    }

    @Test
    void d3Btl002IsolatesAFailedSessionFromTheOtherParticipant() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 2, BattleMatchView.ConnectionState.CONNECTED));
        when(views.read(MATCH_ID, PLAYER_TWO))
                .thenReturn(view(PLAYER_TWO, PLAYER_ONE, 1, BattleMatchView.ConnectionState.CONNECTED))
                .thenReturn(view(PLAYER_TWO, PLAYER_ONE, 2, BattleMatchView.ConnectionState.CONNECTED));
        BattleWebSocketHandler handler = new BattleWebSocketHandler(views, objectMapper);
        WebSocketSession failed = session("failed", PLAYER_ONE);
        WebSocketSession healthy = session("healthy", PLAYER_TWO);
        doNothing().doThrow(new IOException("closed transport")).when(failed).sendMessage(any(TextMessage.class));

        handler.afterConnectionEstablished(failed);
        handler.afterConnectionEstablished(healthy);
        handler.publishLocal(MATCH_ID);

        verify(failed).close(CloseStatus.SERVER_ERROR);
        verify(healthy, times(2)).sendMessage(any(TextMessage.class));
    }

    @Test
    void d3Sec001RejectsClientMessagesUntilACommandContractIsActivated() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));
        BattleWebSocketHandler handler = new BattleWebSocketHandler(views, objectMapper);
        WebSocketSession session = session("one", PLAYER_ONE);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"UNDECLARED_COMMAND\"}"));

        verify(session).close(CloseStatus.NOT_ACCEPTABLE);
    }

    private static WebSocketSession session(String id, UUID viewerId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(BattleWebSocketHandshakeInterceptor.MATCH_ID_ATTRIBUTE, MATCH_ID);
        attributes.put(BattleWebSocketHandshakeInterceptor.VIEWER_ID_ATTRIBUTE, viewerId);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static BattleMatchView view(
            UUID self, UUID opponent, long version, BattleMatchView.ConnectionState opponentConnection) {
        return new BattleMatchView(
                MATCH_ID,
                version,
                NOW,
                BattleMatchView.State.RUNNING,
                NOW,
                NOW.plusSeconds(600),
                new BattleMatchView.Participant(
                        self, true, BattleMatchView.ConnectionState.CONNECTED, null),
                new BattleMatchView.Participant(
                        opponent,
                        true,
                        opponentConnection,
                        opponentConnection == BattleMatchView.ConnectionState.DISCONNECTED
                                ? NOW.plusSeconds(30)
                                : null),
                null);
    }
}
