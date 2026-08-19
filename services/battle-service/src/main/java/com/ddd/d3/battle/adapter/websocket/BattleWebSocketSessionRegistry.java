package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.application.BattleAttackService;
import com.ddd.d3.battle.application.BattleMatchViewService;
import com.ddd.d3.battle.application.BattleSubmissionViewService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

@Component
final class BattleWebSocketSessionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleWebSocketSessionRegistry.class);
    private final BattleMatchViewService views;
    private final BattleAttackService attacks;
    private final BattleSubmissionViewService submissions;
    private final BattleDisconnectRetryQueue disconnects;
    private final ObjectMapper objectMapper;
    private final Map<String, Registration> sessionsById = new ConcurrentHashMap<>();
    private final Map<ParticipantKey, Registration> sessionsByParticipant = new ConcurrentHashMap<>();

    BattleWebSocketSessionRegistry(
            BattleMatchViewService views,
            BattleDisconnectRetryQueue disconnects,
            ObjectMapper objectMapper) {
        this(views, null, null, disconnects, objectMapper);
    }

    BattleWebSocketSessionRegistry(
            BattleMatchViewService views,
            BattleAttackService attacks,
            BattleDisconnectRetryQueue disconnects,
            ObjectMapper objectMapper) {
        this(views, attacks, null, disconnects, objectMapper);
    }

    BattleWebSocketSessionRegistry(BattleMatchViewService views, BattleAttackService attacks,
            BattleSubmissionViewService submissions, BattleDisconnectRetryQueue disconnects, ObjectMapper objectMapper) {
        this.views = Objects.requireNonNull(views, "views must not be null");
        this.attacks = attacks;
        this.submissions = submissions;
        this.disconnects = Objects.requireNonNull(disconnects, "disconnects must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Autowired
    BattleWebSocketSessionRegistry(
            BattleMatchViewService views,
            BattleAttackService attacks,
            BattleSubmissionViewService submissions,
            BattleDisconnectRetryQueue disconnects,
            ObjectMapper objectMapper,
            MeterRegistry meters) {
        this(views, attacks, submissions, disconnects, objectMapper);
        Gauge.builder("d3.battle.websocket.sessions.active", sessionsByParticipant, Map::size)
                .description("Current participant-owned Battle WebSocket sessions on this instance")
                .register(Objects.requireNonNull(meters, "meters must not be null"));
    }

    void register(WebSocketSession session, long generation) throws IOException {
        UUID matchId = requiredAttribute(
                session, BattleWebSocketHandshakeInterceptor.MATCH_ID_ATTRIBUTE, UUID.class);
        UUID viewerId = requiredAttribute(
                session, BattleWebSocketHandshakeInterceptor.VIEWER_ID_ATTRIBUTE, UUID.class);
        Registration registration = new Registration(session, matchId, viewerId, generation);
        AtomicReference<Registration> replaced = new AtomicReference<>();
        AtomicBoolean registered = new AtomicBoolean();
        sessionsByParticipant.compute(registration.participant, (ignored, current) -> {
            if (current != null && current.generation >= registration.generation) {
                return current;
            }
            if (current != null) {
                sessionsById.remove(current.session.getId(), current);
                replaced.set(current);
            }
            sessionsById.put(session.getId(), registration);
            registered.set(true);
            return registration;
        });
        if (!registered.get()) {
            closeQuietly(session, CloseStatus.NORMAL);
            return;
        }
        Registration previous = replaced.get();
        if (previous != null) {
            closeQuietly(previous.session, CloseStatus.NORMAL);
        }
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
        sessionsByParticipant.values().stream()
                .filter(registration -> registration.matchId.equals(matchId))
                .forEach(this::sendLatestQuietly);
    }

    Set<UUID> activeMatchIds() {
        return sessionsByParticipant.values().stream()
                .map(registration -> registration.matchId)
                .collect(Collectors.toUnmodifiableSet());
    }

    int activeSessionCount() {
        return sessionsByParticipant.size();
    }

    void close(WebSocketSession session, CloseStatus status) {
        Registration registration = registrationFor(session);
        closeQuietly(session, status);
        if (registration != null && unregister(registration)) {
            disconnectQuietly(registration);
        }
    }

    void remove(WebSocketSession session) {
        Registration registration = registrationFor(session);
        if (registration != null && unregister(registration)) {
            disconnectQuietly(registration);
        }
    }

    long requiredGeneration(WebSocketSession session) {
        Registration registration = registrationFor(session);
        if (registration == null || sessionsByParticipant.get(registration.participant) != registration) {
            throw new IllegalStateException("WebSocket session is not registered");
        }
        return registration.generation;
    }

    private void sendLatestQuietly(Registration registration) {
        try {
            sendLatest(registration);
        } catch (SnapshotPreparationException exception) {
            LOGGER.warn(
                    "Battle snapshot read deferred; matchId={} sessionId={}",
                    registration.matchId,
                    registration.session.getId());
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
            if (sessionsByParticipant.get(registration.participant) != registration) {
                return;
            }
            if (!registration.session.isOpen()) {
                evict(registration);
                return;
            }
            PreparedSnapshot prepared = prepareLatest(registration);
            if (prepared == null) {
                return;
            }
            registration.session.sendMessage(prepared.message());
            registration.lastSequence = prepared.sequence();
            registration.lastSubmissionId = prepared.submissionId();
        }
    }

    private PreparedSnapshot prepareLatest(Registration registration) {
        try {
            var view = views.read(registration.matchId, registration.viewerId);
            if (BattleWebSocketHandler.V3_PROTOCOL.equals(registration.session.getAcceptedProtocol())) {
                if (attacks == null) {
                    throw new IllegalStateException("attack service is unavailable");
                }
                var attack = attacks.read(registration.matchId, registration.viewerId);
                var submission = submissions == null ? null : submissions.read(registration.matchId, registration.viewerId).orElse(null);
                long sequence = Math.addExact(view.aggregateVersion(), attack.sequence());
                UUID submissionId = submission == null ? null : submission.submissionId();
                if (sequence <= registration.lastSequence && Objects.equals(submissionId, registration.lastSubmissionId)) {
                    return null;
                }
                sequence = Math.max(sequence, registration.lastSequence + 1);
                String payload = objectMapper.writeValueAsString(
                        BattleSnapshotMessageV3.from(view, attack, submission, sequence));
                return new PreparedSnapshot(sequence, submissionId, new TextMessage(payload));
            }
            if (view.aggregateVersion() <= registration.lastSequence) {
                return null;
            }
            String payload = objectMapper.writeValueAsString(BattleSnapshotMessageV2.from(view));
            return new PreparedSnapshot(view.aggregateVersion(), null, new TextMessage(payload));
        } catch (RuntimeException exception) {
            throw new SnapshotPreparationException(exception);
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
        if (unregister(registration)) {
            disconnectQuietly(registration);
        }
    }

    private Registration registrationFor(WebSocketSession session) {
        Registration registration = sessionsById.get(session.getId());
        return registration != null && registration.session == session ? registration : null;
    }

    private boolean unregister(Registration registration) {
        sessionsById.remove(registration.session.getId(), registration);
        return sessionsByParticipant.remove(registration.participant, registration);
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
        private final ParticipantKey participant;
        private long lastSequence = -1;
        private UUID lastSubmissionId;

        private Registration(WebSocketSession session, UUID matchId, UUID viewerId, long generation) {
            if (generation <= 0) {
                throw new IllegalArgumentException("generation must be positive");
            }
            this.session = session;
            this.matchId = matchId;
            this.viewerId = viewerId;
            this.generation = generation;
            this.participant = new ParticipantKey(matchId, viewerId);
        }
    }

    private record ParticipantKey(UUID matchId, UUID viewerId) {}

    private record PreparedSnapshot(long sequence, UUID submissionId, TextMessage message) {}

    private static final class SnapshotPreparationException extends RuntimeException {

        private SnapshotPreparationException(Exception cause) {
            super(cause);
        }
    }
}
