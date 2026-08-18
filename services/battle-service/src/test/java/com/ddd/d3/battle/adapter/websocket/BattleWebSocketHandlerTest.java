package com.ddd.d3.battle.adapter.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ddd.d3.battle.application.BattleAttackService;
import com.ddd.d3.battle.application.BattleAttackView;
import com.ddd.d3.battle.application.BattleJudgeCommandService;
import com.ddd.d3.battle.application.BattleJudgeGateway;
import com.ddd.d3.battle.application.BattleMatchCommandService;
import com.ddd.d3.battle.application.BattleConnectionService;
import com.ddd.d3.battle.application.BattleMatchView;
import com.ddd.d3.battle.application.BattleMatchViewService;
import com.ddd.d3.battle.domain.BattleMatch;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
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
    private static final UUID COMMAND_ONE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID COMMAND_TWO = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void d3Btl002SendsPrivateCurrentStateAndOnlyNewerCommittedVersions() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 2, BattleMatchView.ConnectionState.DISCONNECTED))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 2, BattleMatchView.ConnectionState.DISCONNECTED));
        BattleWebSocketHandler handler = handler(views);
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
        BattleWebSocketHandler handler = handler(views);
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
        BattleWebSocketHandler handler = handler(views);
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
    void d3Sec001ReplacesTheSameParticipantSessionAndIgnoresLateClose() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 5, BattleMatchView.ConnectionState.CONNECTED));
        BattleConnectionService connections = mock(BattleConnectionService.class);
        when(connections.connected(MATCH_ID, PLAYER_ONE))
                .thenReturn(new BattleConnectionService.ConnectionLease(7))
                .thenReturn(new BattleConnectionService.ConnectionLease(8));
        BattleMatchCommandService commands = mock(BattleMatchCommandService.class);
        BattleWebSocketHandler handler = handler(views, commands, connections);
        WebSocketSession previous = session("previous", PLAYER_ONE);
        WebSocketSession replacement = session("replacement", PLAYER_ONE);

        handler.afterConnectionEstablished(previous);
        handler.afterConnectionEstablished(replacement);
        handler.afterConnectionClosed(previous, CloseStatus.GOING_AWAY);
        handler.handleTransportError(previous, new IOException("late transport error"));

        verify(previous).close(CloseStatus.NORMAL);
        verify(previous).close(CloseStatus.SERVER_ERROR);
        verify(replacement, times(1)).sendMessage(any(TextMessage.class));
        handler.handleTextMessage(replacement, new TextMessage("""
                {"type":"READY","version":2,"matchId":"%s","commandId":"%s"}
                """.formatted(MATCH_ID, COMMAND_ONE)));
        verify(commands).handle(
                MATCH_ID,
                COMMAND_ONE,
                PLAYER_ONE,
                8,
                new BattleMatch.Ready(PLAYER_ONE.toString()));
        verify(connections, never()).disconnected(MATCH_ID, PLAYER_ONE, 7);
        verify(connections, never()).disconnected(MATCH_ID, PLAYER_ONE, 8);
    }

    @Test
    void d3Sec001BoundsRegistryToOneSessionForTheSameParticipant() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));
        BattleConnectionService connections = mock(BattleConnectionService.class);
        BattleWebSocketSessionRegistry sessions = new BattleWebSocketSessionRegistry(
                views,
                new BattleDisconnectRetryQueue(
                        connections,
                        mock(ScheduledExecutorService.class),
                        Duration.ofMillis(1)),
                objectMapper);
        WebSocketSession previous = session("previous", PLAYER_ONE);
        WebSocketSession replacement = session("replacement", PLAYER_ONE);

        sessions.register(previous, 7);
        sessions.register(replacement, 8);

        assertEquals(1, sessions.activeSessionCount());
        verify(previous).close(CloseStatus.NORMAL);
    }

    @Test
    void d3Sec001RejectsAnOlderReplacementGenerationWithoutDisconnectingTheCurrentSession() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));
        BattleConnectionService connections = mock(BattleConnectionService.class);
        BattleWebSocketSessionRegistry sessions = new BattleWebSocketSessionRegistry(
                views,
                new BattleDisconnectRetryQueue(
                        connections,
                        mock(ScheduledExecutorService.class),
                        Duration.ofMillis(1)),
                objectMapper);
        WebSocketSession current = session("current", PLAYER_ONE);
        WebSocketSession stale = session("stale", PLAYER_ONE);

        sessions.register(current, 8);
        sessions.register(stale, 7);
        sessions.remove(stale);

        assertEquals(1, sessions.activeSessionCount());
        verify(stale).close(CloseStatus.NORMAL);
        verify(current, never()).close(any(CloseStatus.class));
        verify(connections, never()).disconnected(MATCH_ID, PLAYER_ONE, 7);
        verify(connections, never()).disconnected(MATCH_ID, PLAYER_ONE, 8);
    }

    @Test
    void d3Btl002MapsSocketLifecycleToAServerOwnedGeneration() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));
        BattleConnectionService connections = mock(BattleConnectionService.class);
        when(connections.connected(MATCH_ID, PLAYER_ONE))
                .thenReturn(new BattleConnectionService.ConnectionLease(7));
        BattleWebSocketHandler handler = handler(
                views,
                mock(BattleMatchCommandService.class),
                connections);
        WebSocketSession session = session("owned-generation", PLAYER_ONE);

        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.GOING_AWAY);

        verify(connections).connected(MATCH_ID, PLAYER_ONE);
        verify(connections).disconnected(MATCH_ID, PLAYER_ONE, 7);
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
        BattleWebSocketHandler handler = handler(views);
        WebSocketSession failed = session("failed", PLAYER_ONE);
        WebSocketSession healthy = session("healthy", PLAYER_TWO);
        doNothing().doThrow(new IllegalStateException("closed transport"))
                .when(failed)
                .sendMessage(any(TextMessage.class));

        handler.afterConnectionEstablished(failed);
        handler.afterConnectionEstablished(healthy);
        handler.publishLocal(MATCH_ID);

        verify(failed).close(CloseStatus.SERVER_ERROR);
        verify(healthy, times(2)).sendMessage(any(TextMessage.class));
    }

    @Test
    void d3Btl002DispatchesReadyAndSurrenderFromTheAuthenticatedSession() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));
        BattleMatchCommandService commands = mock(BattleMatchCommandService.class);
        BattleWebSocketHandler handler = handler(views, commands);
        WebSocketSession session = session("one", PLAYER_ONE);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"READY","version":2,"matchId":"%s","commandId":"%s"}
                """.formatted(MATCH_ID, COMMAND_ONE)));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"SURRENDER","version":2,"matchId":"%s","commandId":"%s"}
                """.formatted(MATCH_ID, COMMAND_TWO)));

        verify(commands).handle(
                MATCH_ID,
                COMMAND_ONE,
                PLAYER_ONE,
                1,
                new BattleMatch.Ready(PLAYER_ONE.toString()));
        verify(commands).handle(
                MATCH_ID,
                COMMAND_TWO,
                PLAYER_ONE,
                1,
                new BattleMatch.Surrender(PLAYER_ONE.toString()));
        verify(session, never()).close(CloseStatus.BAD_DATA);
        verify(session, never()).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void d3Btl003DispatchesVersionThreeAttackCommandsFromTheAuthenticatedSession() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));
        BattleAttackService attacks = mock(BattleAttackService.class);
        when(attacks.read(MATCH_ID, PLAYER_ONE)).thenReturn(attackView());
        BattleMatchCommandService commands = mock(BattleMatchCommandService.class);
        BattleConnectionService connections = mock(BattleConnectionService.class);
        when(connections.connected(MATCH_ID, PLAYER_ONE))
                .thenReturn(new BattleConnectionService.ConnectionLease(1));
        BattleWebSocketHandler handler = handler(views, commands, connections, attacks);
        WebSocketSession session = session("v3", PLAYER_ONE, BattleWebSocketHandler.V3_PROTOCOL);
        handler.afterConnectionEstablished(session);

        ArgumentCaptor<TextMessage> initial = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(initial.capture());
        JsonNode snapshot = objectMapper.readTree(initial.getValue().getPayload());
        assertEquals(3, snapshot.path("version").asInt());
        assertEquals(0, snapshot.path("payload").path("attack").path("selfEnergy").asInt());
        assertEquals(100, snapshot.path("payload").path("attack").path("maximumEnergy").asInt());
        assertEquals(40, snapshot.path("payload").path("attack").path("attackCost").asInt());
        assertEquals(20, snapshot.path("payload").path("attack").path("blockCost").asInt());
        assertEquals(30, snapshot.path("payload").path("attack").path("reflectCost").asInt());

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"ATTACK_LAUNCH","version":3,"matchId":"%s","commandId":"%s","attackId":"attack-one"}
                """.formatted(MATCH_ID, COMMAND_ONE)));

        verify(attacks).launch(MATCH_ID, COMMAND_ONE, PLAYER_ONE, 1, "attack-one");
        verifyNoInteractions(commands);
        verify(session, never()).close(CloseStatus.BAD_DATA);
        verify(session, never()).close(CloseStatus.POLICY_VIOLATION);
    }
    @Test
    void d3Btl001DispatchesPrivateRunSourceOnlyToJudgeBoundary() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));
        BattleAttackService attacks = mock(BattleAttackService.class);
        when(attacks.read(MATCH_ID, PLAYER_ONE)).thenReturn(attackView());
        BattleJudgeCommandService judges = mock(BattleJudgeCommandService.class);
        BattleMatchCommandService commands = mock(BattleMatchCommandService.class);
        BattleConnectionService connections = mock(BattleConnectionService.class);
        when(connections.connected(MATCH_ID, PLAYER_ONE))
                .thenReturn(new BattleConnectionService.ConnectionLease(1));
        BattleWebSocketHandler handler = handler(views, commands, connections, attacks, judges);
        WebSocketSession session = session("judge-v3", PLAYER_ONE, BattleWebSocketHandler.V3_PROTOCOL);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"RUN","version":3,"matchId":"%s","commandId":"%s","sourceCode":"class Main {}"}
                """.formatted(MATCH_ID, COMMAND_ONE)));

        verify(judges).handle(
                MATCH_ID,
                COMMAND_ONE,
                PLAYER_ONE,
                1,
                BattleJudgeGateway.Mode.RUN,
                "class Main {}");
        verifyNoInteractions(commands);
        verify(session, never()).close(CloseStatus.BAD_DATA);
        verify(session, never()).close(CloseStatus.POLICY_VIOLATION);
    }


    @Test
    void d3Sec001RejectsUnknownFieldsAndCrossMatchCommands() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));
        BattleMatchCommandService malformedCommands = mock(BattleMatchCommandService.class);
        BattleWebSocketHandler malformedHandler = handler(views, malformedCommands);
        WebSocketSession malformedSession = session("malformed", PLAYER_ONE);
        malformedHandler.afterConnectionEstablished(malformedSession);

        malformedHandler.handleTextMessage(malformedSession, new TextMessage("""
                {"type":"READY","version":2,"matchId":"%s","commandId":"%s","playerId":"%s"}
                """.formatted(MATCH_ID, COMMAND_ONE, PLAYER_TWO)));

        verifyNoInteractions(malformedCommands);
        verify(malformedSession).close(CloseStatus.BAD_DATA);

        BattleMatchCommandService crossMatchCommands = mock(BattleMatchCommandService.class);
        BattleWebSocketHandler crossMatchHandler = handler(views, crossMatchCommands);
        WebSocketSession crossMatchSession = session("cross-match", PLAYER_ONE);
        crossMatchHandler.afterConnectionEstablished(crossMatchSession);

        crossMatchHandler.handleTextMessage(crossMatchSession, new TextMessage("""
                {"type":"READY","version":2,"matchId":"%s","commandId":"%s"}
                """.formatted(UUID.fromString("66666666-6666-4666-8666-666666666666"), COMMAND_ONE)));

        verifyNoInteractions(crossMatchCommands);
        verify(crossMatchSession).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void d3Sec001RejectsUnsupportedAndOversizedCommandFrames() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));

        BattleMatchCommandService unsupportedCommands = mock(BattleMatchCommandService.class);
        BattleWebSocketHandler unsupportedHandler = handler(views, unsupportedCommands);
        WebSocketSession unsupportedSession = session("unsupported", PLAYER_ONE);
        unsupportedHandler.afterConnectionEstablished(unsupportedSession);
        unsupportedHandler.handleTextMessage(unsupportedSession, new TextMessage("""
                {"type":"READY","version":1,"matchId":"%s","commandId":"%s"}
                """.formatted(MATCH_ID, COMMAND_ONE)));

        verifyNoInteractions(unsupportedCommands);
        verify(unsupportedSession).close(CloseStatus.BAD_DATA);

        BattleMatchCommandService coercedCommands = mock(BattleMatchCommandService.class);
        BattleWebSocketHandler coercedHandler = handler(views, coercedCommands);
        WebSocketSession coercedSession = session("coerced", PLAYER_ONE);
        coercedHandler.afterConnectionEstablished(coercedSession);
        coercedHandler.handleTextMessage(coercedSession, new TextMessage("""
                {"type":"READY","version":"2","matchId":"%s","commandId":"%s"}
                """.formatted(MATCH_ID, COMMAND_ONE)));

        verifyNoInteractions(coercedCommands);
        verify(coercedSession).close(CloseStatus.BAD_DATA);

        BattleMatchCommandService oversizedCommands = mock(BattleMatchCommandService.class);
        BattleWebSocketHandler oversizedHandler = handler(views, oversizedCommands);
        WebSocketSession oversizedSession = session("oversized", PLAYER_ONE);
        oversizedHandler.afterConnectionEstablished(oversizedSession);
        oversizedHandler.handleTextMessage(
                oversizedSession,
                new TextMessage("x".repeat(270_001)));

        verifyNoInteractions(oversizedCommands);
        verify(oversizedSession).close(CloseStatus.TOO_BIG_TO_PROCESS);
    }

    @Test
    void d3Btl002ClosesInvalidStateWithoutExposingDomainDetails() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));
        BattleMatchCommandService commands = mock(BattleMatchCommandService.class);
        when(commands.handle(
                        MATCH_ID,
                        COMMAND_ONE,
                        PLAYER_ONE,
                        1,
                        new BattleMatch.Surrender(PLAYER_ONE.toString())))
                .thenThrow(new IllegalStateException("internal domain state must stay private"));
        BattleWebSocketHandler handler = handler(views, commands);
        WebSocketSession session = session("invalid-state", PLAYER_ONE);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"SURRENDER","version":2,"matchId":"%s","commandId":"%s"}
                """.formatted(MATCH_ID, COMMAND_ONE)));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void d3Sec001RejectsAmbiguousDuplicateCommandFields() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(PLAYER_ONE, PLAYER_TWO, 1, BattleMatchView.ConnectionState.CONNECTED));
        BattleMatchCommandService commands = mock(BattleMatchCommandService.class);
        BattleWebSocketHandler handler = handler(views, commands);
        WebSocketSession session = session("duplicate-field", PLAYER_ONE);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"READY","type":"SURRENDER","version":2,"matchId":"%s","commandId":"%s"}
                """.formatted(MATCH_ID, COMMAND_ONE)));

        verifyNoInteractions(commands);
        verify(session).close(CloseStatus.BAD_DATA);
    }

    private static WebSocketSession session(String id, UUID viewerId) {
        return session(id, viewerId, BattleWebSocketHandler.V2_PROTOCOL);
    }

    private static WebSocketSession session(String id, UUID viewerId, String protocol) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(BattleWebSocketHandshakeInterceptor.MATCH_ID_ATTRIBUTE, MATCH_ID);
        attributes.put(BattleWebSocketHandshakeInterceptor.VIEWER_ID_ATTRIBUTE, viewerId);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getAcceptedProtocol()).thenReturn(protocol);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private BattleWebSocketHandler handler(BattleMatchViewService views) {
        return handler(views, mock(BattleMatchCommandService.class));
    }

    private BattleWebSocketHandler handler(
            BattleMatchViewService views, BattleMatchCommandService commands) {
        BattleConnectionService connections = mock(BattleConnectionService.class);
        when(connections.connected(any(UUID.class), any(UUID.class)))
                .thenReturn(new BattleConnectionService.ConnectionLease(1));
        return handler(views, commands, connections);
    }

    private BattleWebSocketHandler handler(
            BattleMatchViewService views,
            BattleMatchCommandService commands,
            BattleConnectionService connections) {
        return new BattleWebSocketHandler(
                new BattleWebSocketSessionRegistry(
                        views,
                        new BattleDisconnectRetryQueue(
                                connections,
                                mock(ScheduledExecutorService.class),
                                Duration.ofMillis(1)),
                        objectMapper),
                connections,
                commands,
                objectMapper);
    }
    private BattleWebSocketHandler handler(
            BattleMatchViewService views,
            BattleMatchCommandService commands,
            BattleConnectionService connections,
            BattleAttackService attacks) {
        return handler(views, commands, connections, attacks, mock(BattleJudgeCommandService.class));
    }

    private BattleWebSocketHandler handler(
            BattleMatchViewService views,
            BattleMatchCommandService commands,
            BattleConnectionService connections,
            BattleAttackService attacks,
            BattleJudgeCommandService judges) {
        return new BattleWebSocketHandler(
                new BattleWebSocketSessionRegistry(
                        views,
                        attacks,
                        new BattleDisconnectRetryQueue(
                                connections,
                                mock(ScheduledExecutorService.class),
                                Duration.ofMillis(1)),
                        objectMapper),
                connections,
                commands,
                attacks,
                judges,
                objectMapper);
    }

    private static BattleAttackView attackView() {
        return new BattleAttackView(
                MATCH_ID,
                0,
                NOW,
                0,
                0,
                100,
                40,
                20,
                30,
                null);
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
