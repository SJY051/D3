package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.application.BattleAttackService;
import com.ddd.d3.battle.application.BattleConnectionService;
import com.ddd.d3.battle.application.BattleJudgeCommandService;
import com.ddd.d3.battle.application.BattleJudgeGateway;
import com.ddd.d3.battle.application.BattleMatchCommandService;
import com.ddd.d3.battle.application.BattleMatchNotFoundException;
import com.ddd.d3.battle.application.CommandIdConflictException;
import com.ddd.d3.battle.application.OptimisticMatchConflictException;
import com.ddd.d3.battle.domain.BattleMatch;
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
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectReader;

@Component
final class BattleWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {
    static final String V2_PROTOCOL = "d3.battle.v2";
    static final String V3_PROTOCOL = "d3.battle.v3";
    static final String APPLICATION_PROTOCOL = V2_PROTOCOL;
    private static final int MAX_COMMAND_BYTES = 270_000;
    private final BattleWebSocketSessionRegistry sessions;
    private final BattleConnectionService connections;
    private final BattleMatchCommandService commands;
    private final BattleAttackService attacks;
    private final BattleJudgeCommandService judges;
    private final ObjectReader v2Reader;
    private final ObjectReader v3Reader;

    BattleWebSocketHandler(BattleWebSocketSessionRegistry sessions, BattleConnectionService connections,
            BattleMatchCommandService commands, ObjectMapper objectMapper) {
        this(sessions, connections, commands, null, null, objectMapper);
    }

    @Autowired
    BattleWebSocketHandler(
            BattleWebSocketSessionRegistry sessions,
            BattleConnectionService connections,
            BattleMatchCommandService commands,
            BattleAttackService attacks,
            BattleJudgeCommandService judges,
            ObjectMapper objectMapper) {
        this.sessions = Objects.requireNonNull(sessions);
        this.connections = Objects.requireNonNull(connections);
        this.commands = Objects.requireNonNull(commands);
        this.attacks = attacks;
        this.judges = judges;
        ObjectMapper strict = Objects.requireNonNull(objectMapper).rebuild()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        this.v2Reader = strict.readerFor(BattleClientCommandV2.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.v3Reader = strict.readerFor(BattleClientCommandV3.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override public List<String> getSubProtocols() { return List.of(V2_PROTOCOL, V3_PROTOCOL); }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String protocol = session.getAcceptedProtocol();
        if (!V2_PROTOCOL.equals(protocol) && !V3_PROTOCOL.equals(protocol)) {
            sessions.close(session, CloseStatus.PROTOCOL_ERROR);
            return;
        }
        UUID matchId = BattleWebSocketSessionRegistry.requiredAttribute(session,
                BattleWebSocketHandshakeInterceptor.MATCH_ID_ATTRIBUTE, UUID.class);
        UUID viewerId = BattleWebSocketSessionRegistry.requiredAttribute(session,
                BattleWebSocketHandshakeInterceptor.VIEWER_ID_ATTRIBUTE, UUID.class);
        var lease = connections.connected(matchId, viewerId);
        sessions.register(session, lease.generation());
    }

    void publishLocal(UUID matchId) { sessions.publish(matchId); }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.asBytes().length > MAX_COMMAND_BYTES) { sessions.close(session, CloseStatus.TOO_BIG_TO_PROCESS); return; }
        try {
            UUID matchId = BattleWebSocketSessionRegistry.requiredAttribute(session,
                    BattleWebSocketHandshakeInterceptor.MATCH_ID_ATTRIBUTE, UUID.class);
            UUID viewerId = BattleWebSocketSessionRegistry.requiredAttribute(session,
                    BattleWebSocketHandshakeInterceptor.VIEWER_ID_ATTRIBUTE, UUID.class);
            long generation = sessions.requiredGeneration(session);
            if (V2_PROTOCOL.equals(session.getAcceptedProtocol())) {
                BattleClientCommandV2 command = v2Reader.readValue(message.getPayload());
                requireMatch(matchId, command.matchId());
                commands.handle(matchId, command.commandId(), viewerId, generation, command.toDomain(viewerId));
            } else if (V3_PROTOCOL.equals(session.getAcceptedProtocol())) {
                BattleClientCommandV3 command = v3Reader.readValue(message.getPayload());
                requireMatch(matchId, command.matchId());
                handleV3(matchId, viewerId, generation, command);
            } else {
                sessions.close(session, CloseStatus.PROTOCOL_ERROR);
            }
        } catch (JacksonException exception) {
            sessions.close(session, CloseStatus.BAD_DATA);
        } catch (BattleMatchNotFoundException | CommandIdConflictException | OptimisticMatchConflictException
                | IllegalArgumentException | IllegalStateException exception) {
            sessions.close(session, CloseStatus.POLICY_VIOLATION);
        } catch (RuntimeException exception) {
            sessions.close(session, CloseStatus.SERVER_ERROR);
        }
    }

    private void handleV3(UUID matchId, UUID viewerId, long generation, BattleClientCommandV3 command) {
        switch (command.type()) {
            case HEARTBEAT -> {
                // Browser WebSocket clients cannot emit protocol ping frames. This no-op keeps
                // the authenticated transport active without changing match state or snapshots.
            }
            case READY -> commands.handle(matchId, command.commandId(), viewerId, generation,
                    new BattleMatch.Ready(viewerId.toString()));
            case SURRENDER -> commands.handle(matchId, command.commandId(), viewerId, generation,
                    new BattleMatch.Surrender(viewerId.toString()));
            case RUN -> judges.handle(
                    matchId,
                    command.commandId(),
                    viewerId,
                    generation,
                    BattleJudgeGateway.Mode.RUN,
                    command.sourceCode());
            case SUBMIT -> judges.handle(
                    matchId,
                    command.commandId(),
                    viewerId,
                    generation,
                    BattleJudgeGateway.Mode.SUBMIT,
                    command.sourceCode());
            case ATTACK_LAUNCH -> attacks.launch(matchId, command.commandId(), viewerId, generation, command.attackId());
            case ATTACK_BLOCK -> attacks.block(matchId, command.commandId(), viewerId, generation, command.attackId());
            case ATTACK_REFLECT -> attacks.reflect(matchId, command.commandId(), viewerId, generation, command.attackId());
        }
    }

    private static void requireMatch(UUID expected, UUID actual) {
        if (!expected.equals(actual)) throw new IllegalArgumentException("command match does not match session");
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.close(session, CloseStatus.SERVER_ERROR);
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session); }
}
