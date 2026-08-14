package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.application.BattleMatchViewService;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

@Component
final class BattleWebSocketSessionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleWebSocketSessionRegistry.class);
    private final BattleMatchViewService views;
    private final BattleDisconnectRetryQueue disconnects;
    private final ObjectMapper objectMapper;
    private final Map<String, Registration> sessions = new ConcurrentHashMap<>();

    BattleWebSocketSessionRegistry(
            BattleMatchViewService views,
            BattleDisconnectRetryQueue disconnects,
            ObjectMapper objectMapper) {
        this.views = Objects.requireNonNull(views, "views must not be null");
        this.disconnects = Objects.requireNonNull(disconnects, "disconnects must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    void register(WebSocketSession session, long generation) throws IOException {
        UUID matchId = requiredAttribute(
                session, BattleWebSocketHandshakeInterceptor.MATCH_ID_ATTRIBUTE, UUID.class);
        UUID viewerId = requiredAttribute(
                session, BattleWebSocketHandshakeInterceptor.VIEWER_ID_ATTRIBUTE, UUID.class);
        Registration registration = new Registration(session, matchId, viewerId, generation);
        sessions.put(session.getId(), registration);
        try {
            sendLatest(registration);
        } catch (IOException | RuntimeException exception) {
            evict(registration);
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            throw exception;
        }
    }

    void publish(UUID matchId) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        sessions.values().stream()
                .filter(registration -> registration.matchId.equals(matchId))
                .forEach(this::sendLatestQuietly);
    }

    Set<UUID> activeMatchIds() {
        return sessions.values().stream()
                .map(registration -> registration.matchId)
                .collect(Collectors.toUnmodifiableSet());
    }

    void close(WebSocketSession session, CloseStatus status) {
        Registration registration = sessions.remove(session.getId());
        closeQuietly(session, status);
        if (registration != null) {
            disconnectQuietly(registration);
        }
    }

    void remove(WebSocketSession session) {
        Registration registration = sessions.remove(session.getId());
        if (registration != null) {
            disconnectQuietly(registration);
        }
    }

    long requiredGeneration(WebSocketSession session) {
        Registration registration = sessions.get(session.getId());
        if (registration == null || registration.session != session) {
            throw new IllegalStateException("WebSocket session is not registered");
        }
        return registration.generation;
    }

    private void sendLatestQuietly(Registration registration) {
        try {
            sendLatest(registration);
        } catch (IOException | RuntimeException exception) {
            evict(registration);
            LOGGER.warn(
                    "Battle snapshot delivery failed; matchId={} sessionId={}",
                    registration.matchId,
                    registration.session.getId());
            closeQuietly(registration.session, CloseStatus.SERVER_ERROR);
        }
    }

    private void sendLatest(Registration registration) throws IOException {
        synchronized (registration) {
            if (!registration.session.isOpen()) {
                evict(registration);
                return;
            }
            var view = views.read(registration.matchId, registration.viewerId);
            if (view.aggregateVersion() <= registration.lastSequence) {
                return;
            }
            String payload = objectMapper.writeValueAsString(BattleSnapshotMessageV2.from(view));
            registration.session.sendMessage(new TextMessage(payload));
            registration.lastSequence = view.aggregateVersion();
        }
    }

    static <T> T requiredAttribute(WebSocketSession session, String name, Class<T> type) {
        Object value = session.getAttributes().get(name);
        if (!type.isInstance(value)) {
            throw new IllegalStateException("authorized WebSocket attribute is missing");
        }
        return type.cast(value);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
            // The session is already unusable; no committed match state depends on transport cleanup.
        }
    }

    private void evict(Registration registration) {
        if (sessions.remove(registration.session.getId(), registration)) {
            disconnectQuietly(registration);
        }
    }

    private void disconnectQuietly(Registration registration) {
        try {
            disconnects.disconnect(
                    registration.matchId,
                    registration.viewerId,
                    registration.generation);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Battle transport disconnect update failed; matchId={} sessionId={}",
                    registration.matchId,
                    registration.session.getId());
        }
    }

    private static final class Registration {
        private final WebSocketSession session;
        private final UUID matchId;
        private final UUID viewerId;
        private final long generation;
        private long lastSequence = -1;

        private Registration(WebSocketSession session, UUID matchId, UUID viewerId, long generation) {
            if (generation <= 0) {
                throw new IllegalArgumentException("generation must be positive");
            }
            this.session = session;
            this.matchId = matchId;
            this.viewerId = viewerId;
            this.generation = generation;
        }
    }
}
