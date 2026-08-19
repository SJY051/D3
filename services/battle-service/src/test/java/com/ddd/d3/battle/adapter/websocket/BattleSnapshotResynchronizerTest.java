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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void d3Jdg001PublishesAVerdictOnlyFrameWithTheBumpedSerializedSequence() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE)).thenReturn(view(4));
        com.ddd.d3.battle.application.BattleAttackService attacks =
                mock(com.ddd.d3.battle.application.BattleAttackService.class);
        when(attacks.read(MATCH_ID, PLAYER_ONE)).thenReturn(new com.ddd.d3.battle.application.BattleAttackView(
                MATCH_ID, 3, NOW, 50, 50, 100, 40, 20, 30, null));
        com.ddd.d3.battle.application.BattleSubmissionViewService submissions =
                mock(com.ddd.d3.battle.application.BattleSubmissionViewService.class);
        UUID submissionId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        when(submissions.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(java.util.Optional.empty())
                .thenReturn(java.util.Optional.of(
                        new com.ddd.d3.battle.application.BattleJudgeReferenceStore.SubmissionVerdict(
                                submissionId, "WRONG_ANSWER", 1, NOW.plusSeconds(1))))
                .thenReturn(java.util.Optional.of(
                        new com.ddd.d3.battle.application.BattleJudgeReferenceStore.SubmissionVerdict(
                                submissionId, "WRONG_ANSWER", 1, NOW.plusSeconds(1))));
        BattleWebSocketSessionRegistry sessions = new BattleWebSocketSessionRegistry(
                views,
                attacks,
                submissions,
                new BattleDisconnectRetryQueue(
                        mock(BattleConnectionService.class),
                        mock(ScheduledExecutorService.class),
                        Duration.ofMillis(1)),
                new ObjectMapper());
        WebSocketSession session = session();
        when(session.getAcceptedProtocol()).thenReturn(BattleWebSocketHandler.V3_PROTOCOL);
        sessions.register(session, 1);

        sessions.publish(MATCH_ID);
        sessions.publish(MATCH_ID);
        sessions.publish(MATCH_ID);

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(messages.capture());
        ObjectMapper objectMapper = new ObjectMapper();
        var first = objectMapper.readTree(messages.getAllValues().get(0).getPayload());
        var second = objectMapper.readTree(messages.getAllValues().get(1).getPayload());
        assertEquals(7, first.path("sequence").asLong());
        assertEquals(true, first.path("payload").path("submission").isNull());
        assertEquals(8, second.path("sequence").asLong(),
                "a verdict-only change must serialize the bumped sequence into the frame");
        assertEquals("WRONG_ANSWER", second.path("payload").path("submission").path("verdict").asString());
        assertEquals(1, second.path("payload").path("submission").path("attemptNumber").asInt());
        assertEquals(false, second.path("payload").path("submission").path("acceptedLocked").asBoolean());
    }

    @Test
    void d3Btl003DeliversTheNextAuthoritativeFrameAfterAVerdictOnlyBump() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(4))
                .thenReturn(view(4))
                .thenReturn(view(5));
        com.ddd.d3.battle.application.BattleAttackService attacks =
                mock(com.ddd.d3.battle.application.BattleAttackService.class);
        when(attacks.read(MATCH_ID, PLAYER_ONE)).thenReturn(new com.ddd.d3.battle.application.BattleAttackView(
                MATCH_ID, 3, NOW, 50, 50, 100, 40, 20, 30, null));
        com.ddd.d3.battle.application.BattleSubmissionViewService submissions =
                mock(com.ddd.d3.battle.application.BattleSubmissionViewService.class);
        UUID submissionId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        when(submissions.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(java.util.Optional.empty())
                .thenReturn(java.util.Optional.of(
                        new com.ddd.d3.battle.application.BattleJudgeReferenceStore.SubmissionVerdict(
                                submissionId, "ACCEPTED", 1, NOW.plusSeconds(1))))
                .thenReturn(java.util.Optional.of(
                        new com.ddd.d3.battle.application.BattleJudgeReferenceStore.SubmissionVerdict(
                                submissionId, "ACCEPTED", 1, NOW.plusSeconds(1))));
        BattleWebSocketSessionRegistry sessions = new BattleWebSocketSessionRegistry(
                views,
                attacks,
                submissions,
                new BattleDisconnectRetryQueue(
                        mock(BattleConnectionService.class),
                        mock(ScheduledExecutorService.class),
                        Duration.ofMillis(1)),
                new ObjectMapper());
        WebSocketSession session = session();
        when(session.getAcceptedProtocol()).thenReturn(BattleWebSocketHandler.V3_PROTOCOL);
        sessions.register(session, 1);

        sessions.publish(MATCH_ID); // baseline: natural sequence 7
        sessions.publish(MATCH_ID); // verdict-only change: bumped to 8
        sessions.publish(MATCH_ID); // authoritative version bump (e.g. FINISHED): must NOT be swallowed

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(3)).sendMessage(messages.capture());
        ObjectMapper objectMapper = new ObjectMapper();
        long[] sequences = messages.getAllValues().stream()
                .mapToLong(message -> objectMapper.readTree(message.getPayload()).path("sequence").asLong())
                .toArray();
        assertEquals(7, sequences[0]);
        assertEquals(8, sequences[1]);
        assertEquals(9, sequences[2],
                "the verdict bump must persist as an offset so the next authoritative frame is delivered");
    }

    @Test
    void d3Btl002KeepsTheSequenceStreamMonotonicAcrossAReconnect() throws Exception {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(view(4))
                .thenReturn(view(4))
                .thenReturn(view(4))
                .thenReturn(view(5));
        com.ddd.d3.battle.application.BattleAttackService attacks =
                mock(com.ddd.d3.battle.application.BattleAttackService.class);
        when(attacks.read(MATCH_ID, PLAYER_ONE)).thenReturn(new com.ddd.d3.battle.application.BattleAttackView(
                MATCH_ID, 3, NOW, 50, 50, 100, 40, 20, 30, null));
        com.ddd.d3.battle.application.BattleSubmissionViewService submissions =
                mock(com.ddd.d3.battle.application.BattleSubmissionViewService.class);
        UUID submissionId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        when(submissions.read(MATCH_ID, PLAYER_ONE))
                .thenReturn(java.util.Optional.empty())
                .thenReturn(java.util.Optional.of(
                        new com.ddd.d3.battle.application.BattleJudgeReferenceStore.SubmissionVerdict(
                                submissionId, "ACCEPTED", 1, NOW.plusSeconds(1))))
                .thenReturn(java.util.Optional.of(
                        new com.ddd.d3.battle.application.BattleJudgeReferenceStore.SubmissionVerdict(
                                submissionId, "ACCEPTED", 1, NOW.plusSeconds(1))));
        BattleWebSocketSessionRegistry sessions = new BattleWebSocketSessionRegistry(
                views,
                attacks,
                submissions,
                new BattleDisconnectRetryQueue(
                        mock(BattleConnectionService.class),
                        mock(ScheduledExecutorService.class),
                        Duration.ofMillis(1)),
                new ObjectMapper());
        WebSocketSession first = session();
        when(first.getAcceptedProtocol()).thenReturn(BattleWebSocketHandler.V3_PROTOCOL);
        sessions.register(first, 1);
        sessions.publish(MATCH_ID); // baseline 7 on the first transport
        sessions.publish(MATCH_ID); // verdict-only bump to 8 on the first transport

        WebSocketSession second = session();
        when(second.getId()).thenReturn("local-session-2");
        when(second.getAcceptedProtocol()).thenReturn(BattleWebSocketHandler.V3_PROTOCOL);
        sessions.register(second, 2); // reconnect supersedes the first transport
        sessions.publish(MATCH_ID);   // authoritative bump: natural 8 + inherited offset

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(second, org.mockito.Mockito.atLeastOnce()).sendMessage(messages.capture());
        ObjectMapper objectMapper = new ObjectMapper();
        long last = objectMapper.readTree(messages.getAllValues()
                .get(messages.getAllValues().size() - 1).getPayload()).path("sequence").asLong();
        assertEquals(9, last,
                "a reconnected transport must inherit the verdict-bump offset so its frames pass the client's stale filter");
    }

    @Test
    void d3Btl002RetainsRejectedMatchesUntilTheBoundedExecutorRecovers() {
        BattleWebSocketSessionRegistry sessions = mock(BattleWebSocketSessionRegistry.class);
        UUID secondMatchId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        when(sessions.activeMatchIds()).thenReturn(Set.of(MATCH_ID, secondMatchId));
        ManualExecutor executor = new ManualExecutor();
        executor.reject = true;
        BattleSnapshotResynchronizer resynchronizer = new BattleSnapshotResynchronizer(sessions, executor);

        resynchronizer.resynchronize();
        executor.reject = false;
        resynchronizer.resynchronize();
        executor.runAll();

        verify(sessions).publish(MATCH_ID);
        verify(sessions).publish(secondMatchId);
    }

    @Test
    void d3Btl002DrainsARejectedMatchWithoutWaitingForTheNextScan() {
        BattleWebSocketSessionRegistry sessions = mock(BattleWebSocketSessionRegistry.class);
        UUID secondMatchId = UUID.fromString("44444444-4444-4444-8444-444444444444");
        when(sessions.activeMatchIds())
                .thenReturn(new java.util.LinkedHashSet<>(java.util.List.of(MATCH_ID, secondMatchId)));
        ManualExecutor executor = new ManualExecutor();
        executor.capacity = 1;
        BattleSnapshotResynchronizer resynchronizer = new BattleSnapshotResynchronizer(sessions, executor);

        resynchronizer.resynchronize();
        executor.runAll();

        verify(sessions).publish(MATCH_ID);
        verify(sessions).publish(secondMatchId);
    }

    @Test
    void d3Btl002ReschedulesOneLatestReadWhenANotificationArrivesDuringDelivery() {
        BattleWebSocketSessionRegistry sessions = mock(BattleWebSocketSessionRegistry.class);
        when(sessions.activeMatchIds()).thenReturn(Set.of(MATCH_ID));
        ManualExecutor executor = new ManualExecutor();
        BattleSnapshotResynchronizer resynchronizer = new BattleSnapshotResynchronizer(sessions, executor);
        AtomicBoolean firstDelivery = new AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(ignored -> {
            if (firstDelivery.getAndSet(false)) {
                resynchronizer.resynchronize();
            }
            return null;
        }).when(sessions).publish(MATCH_ID);

        resynchronizer.resynchronize();
        resynchronizer.resynchronize();
        executor.runNext();
        executor.runAll();

        assertEquals(2, executor.executed);
        verify(sessions, times(2)).publish(MATCH_ID);
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

    private static final class ManualExecutor implements Executor {
        private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();
        private boolean reject;
        private int capacity;
        private int executed;

        @Override
        public void execute(Runnable command) {
            if (reject || (capacity > 0 && tasks.size() >= capacity)) {
                throw new RejectedExecutionException("executor is saturated");
            }
            tasks.add(command);
        }

        private void runNext() {
            Runnable task = tasks.remove();
            executed++;
            task.run();
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }
    }
}
