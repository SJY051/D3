package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.application.BattleMatchCommandService;
import com.ddd.d3.battle.application.BattleConnectionService;
import com.ddd.d3.battle.application.BattleMatchNotFoundException;
import com.ddd.d3.battle.application.CommandIdConflictException;
import com.ddd.d3.battle.application.OptimisticMatchConflictException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
final class BattleWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    static final String APPLICATION_PROTOCOL = "d3.battle.v2";
    private static final int MAX_COMMAND_BYTES = 4096;
    private final BattleWebSocketSessionRegistry sessions;
    private final BattleConnectionService connections;
    private final BattleMatchCommandService commands;
    private final ObjectReader commandReader;

    BattleWebSocketHandler(
            BattleWebSocketSessionRegistry sessions,
            BattleConnectionService connections,
            BattleMatchCommandService commands,
            ObjectMapper objectMapper) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        ObjectMapper commandMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .rebuild()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.commandReader = commandMapper
                .readerFor(BattleClientCommandV2.class)
                .with(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(APPLICATION_PROTOCOL);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID matchId = BattleWebSocketSessionRegistry.requiredAttribute(
                session, BattleWebSocketHandshakeInterceptor.MATCH_ID_ATTRIBUTE, UUID.class);
        UUID viewerId = BattleWebSocketSessionRegistry.requiredAttribute(
                session, BattleWebSocketHandshakeInterceptor.VIEWER_ID_ATTRIBUTE, UUID.class);
        BattleConnectionService.ConnectionLease lease = connections.connected(matchId, viewerId);
        sessions.register(session, lease.generation());
    }

    void publishLocal(UUID matchId) {
        sessions.publish(matchId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.asBytes().length > MAX_COMMAND_BYTES) {
            sessions.close(session, CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }

        BattleClientCommandV2 command;
        try {
            command = commandReader.readValue(message.getPayload());
        } catch (JacksonException | IllegalArgumentException exception) {
            sessions.close(session, CloseStatus.BAD_DATA);
            return;
        }

        UUID sessionMatchId;
        UUID viewerId;
        try {
            sessionMatchId = BattleWebSocketSessionRegistry.requiredAttribute(
                    session, BattleWebSocketHandshakeInterceptor.MATCH_ID_ATTRIBUTE, UUID.class);
            viewerId = BattleWebSocketSessionRegistry.requiredAttribute(
                    session, BattleWebSocketHandshakeInterceptor.VIEWER_ID_ATTRIBUTE, UUID.class);
        } catch (IllegalStateException exception) {
            sessions.close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (!sessionMatchId.equals(command.matchId())) {
            sessions.close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        try {
            long connectionGeneration = sessions.requiredGeneration(session);
            commands.handle(
                    sessionMatchId,
                    command.commandId(),
                    viewerId,
                    connectionGeneration,
                    command.toDomain(viewerId));
        } catch (BattleMatchNotFoundException
                | CommandIdConflictException
                | OptimisticMatchConflictException
                | IllegalArgumentException
                | IllegalStateException exception) {
            sessions.close(session, CloseStatus.POLICY_VIOLATION);
        } catch (RuntimeException exception) {
            sessions.close(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.close(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }
}
