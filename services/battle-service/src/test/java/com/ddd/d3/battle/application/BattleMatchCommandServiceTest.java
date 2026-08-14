package com.ddd.d3.battle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class BattleMatchCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_ONE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLAYER_TWO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID COMMAND_ONE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID COMMAND_TWO = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final long PLAYER_ONE_GENERATION = 1;
    private static final long PLAYER_TWO_GENERATION = 2;

    @Test
    void d3Btl002CommitsReadyAndRunningAsOrderedAuthoritativeVersions() {
        FakeMatches matches = new FakeMatches(initialSnapshot());
        FakeReceipts receipts = new FakeReceipts();
        BattleMatchCommandService service = service(matches, receipts);

        BattleMatch.Snapshot firstReady = service.handle(
                MATCH_ID,
                COMMAND_ONE,
                PLAYER_ONE,
                PLAYER_ONE_GENERATION,
                new BattleMatch.Ready(PLAYER_ONE.toString()));
        BattleMatch.Snapshot running = service.handle(
                MATCH_ID,
                COMMAND_TWO,
                PLAYER_TWO,
                PLAYER_TWO_GENERATION,
                new BattleMatch.Ready(PLAYER_TWO.toString()));
        BattleMatch.Snapshot replayed = service.handle(
                MATCH_ID,
                COMMAND_TWO,
                PLAYER_TWO,
                PLAYER_TWO_GENERATION,
                new BattleMatch.Ready(PLAYER_TWO.toString()));

        assertEquals(BattleMatch.State.LOBBY, firstReady.state());
        assertEquals(BattleMatch.State.RUNNING, running.state());
        assertEquals(NOW, running.startedAt());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), running.matchDeadline());
        assertEquals(5, running.aggregateVersion());
        assertEquals(running, replayed);
        assertEquals(List.of(2L, 3L, 4L), matches.expectedVersions);
        assertEquals(2, receipts.receipts.size());
        assertEquals(5, receipts.receipts.get(COMMAND_TWO).aggregateVersion());
    }

    @Test
    void d3Btl002RejectsCommandIdReuseWithDifferentPayload() {
        FakeMatches matches = new FakeMatches(initialSnapshot());
        FakeReceipts receipts = new FakeReceipts();
        BattleMatchCommandService service = service(matches, receipts);
        service.handle(
                MATCH_ID,
                COMMAND_ONE,
                PLAYER_ONE,
                PLAYER_ONE_GENERATION,
                new BattleMatch.Ready(PLAYER_ONE.toString()));

        assertThrows(
                CommandIdConflictException.class,
                () -> service.handle(
                        MATCH_ID,
                        COMMAND_ONE,
                        PLAYER_ONE,
                        PLAYER_ONE_GENERATION,
                        new BattleMatch.Disconnect(PLAYER_ONE.toString(), 1)));
    }

    @Test
    void d3Sec001RejectsACommandForAnotherPlayer() {
        FakeMatches matches = new FakeMatches(initialSnapshot());
        FakeReceipts receipts = new FakeReceipts();
        BattleMatchCommandService service = service(matches, receipts);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.handle(
                        MATCH_ID,
                        COMMAND_ONE,
                        PLAYER_ONE,
                        PLAYER_ONE_GENERATION,
                        new BattleMatch.Ready(PLAYER_TWO.toString())));
        assertEquals(0, receipts.receipts.size());
    }

    @Test
    void d3Btl002PublishesOnlyAfterTheAuthoritativeTransactionReturns() {
        FakeMatches matches = new FakeMatches(initialSnapshot());
        FakeReceipts receipts = new FakeReceipts();
        List<String> order = new ArrayList<>();
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                order.add("transaction-start");
                T result = action.doInTransaction(null);
                order.add("transaction-return");
                return result;
            }
        };
        BattleMatchCommandService service = new BattleMatchCommandService(
                matches,
                receipts,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10),
                transactions,
                matchId -> order.add("publish-" + matchId));

        service.handle(
                MATCH_ID,
                COMMAND_ONE,
                PLAYER_ONE,
                PLAYER_ONE_GENERATION,
                new BattleMatch.Ready(PLAYER_ONE.toString()));

        assertEquals(
                List.of("transaction-start", "transaction-return", "publish-" + MATCH_ID),
                order);
    }

    @Test
    void d3Btl002DoesNotReportACommittedCommandAsFailedWhenFanoutIsUnavailable() {
        FakeMatches matches = new FakeMatches(initialSnapshot());
        FakeReceipts receipts = new FakeReceipts();
        BattleMatchCommandService service = new BattleMatchCommandService(
                matches,
                receipts,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10),
                DirectTransactions.INSTANCE,
                matchId -> {
                    throw new IllegalStateException("transport unavailable");
                });

        BattleMatch.Snapshot committed = service.handle(
                MATCH_ID,
                COMMAND_ONE,
                PLAYER_ONE,
                PLAYER_ONE_GENERATION,
                new BattleMatch.Ready(PLAYER_ONE.toString()));

        assertEquals(3, committed.aggregateVersion());
        assertEquals(1, receipts.receipts.size());
    }

    @Test
    void d3Sec001RejectsANewCommandIdThatWouldOnlyGrowReceipts() {
        FakeMatches matches = new FakeMatches(initialSnapshot());
        FakeReceipts receipts = new FakeReceipts();
        BattleMatchCommandService service = service(matches, receipts);
        service.handle(
                MATCH_ID,
                COMMAND_ONE,
                PLAYER_ONE,
                PLAYER_ONE_GENERATION,
                new BattleMatch.Ready(PLAYER_ONE.toString()));

        assertThrows(
                IllegalStateException.class,
                () -> service.handle(
                        MATCH_ID,
                        COMMAND_TWO,
                        PLAYER_ONE,
                        PLAYER_ONE_GENERATION,
                        new BattleMatch.Ready(PLAYER_ONE.toString())));

        assertEquals(1, receipts.receipts.size());
        assertEquals(3, matches.snapshot.aggregateVersion());
    }

    @Test
    void d3Btl002CommitsDeadlineAdvancementBeforeRejectingLateSurrender() {
        FakeMatches matches = new FakeMatches(runningSnapshotAtDeadline());
        FakeReceipts receipts = new FakeReceipts();
        List<UUID> published = new ArrayList<>();
        BattleMatchCommandService service = new BattleMatchCommandService(
                matches,
                receipts,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10),
                DirectTransactions.INSTANCE,
                published::add);

        assertThrows(
                IllegalStateException.class,
                () -> service.handle(
                        MATCH_ID,
                        COMMAND_ONE,
                        PLAYER_ONE,
                        PLAYER_ONE_GENERATION,
                        new BattleMatch.Surrender(PLAYER_ONE.toString())));

        assertEquals(BattleMatch.State.JUDGING, matches.snapshot.state());
        assertEquals(6, matches.snapshot.aggregateVersion());
        assertNull(matches.snapshot.result());
        assertEquals(List.of(5L), matches.expectedVersions);
        assertEquals(0, receipts.receipts.size());
        assertEquals(List.of(MATCH_ID), published);
    }

    @Test
    void d3Btl002OrdersSurrenderAgainstOneCapturedServerInstant() {
        FakeMatches matches = new FakeMatches(runningSnapshotAtDeadline());
        FakeReceipts receipts = new FakeReceipts();
        List<UUID> published = new ArrayList<>();
        AtomicInteger clockReads = new AtomicInteger();
        Clock crossingDeadline = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clockReads.getAndIncrement() == 0 ? NOW.minusNanos(1) : NOW;
            }
        };
        BattleMatchCommandService service = new BattleMatchCommandService(
                matches,
                receipts,
                crossingDeadline,
                Duration.ofMinutes(10),
                DirectTransactions.INSTANCE,
                published::add);

        BattleMatch.Snapshot surrendered = service.handle(
                MATCH_ID,
                COMMAND_ONE,
                PLAYER_ONE,
                PLAYER_ONE_GENERATION,
                new BattleMatch.Surrender(PLAYER_ONE.toString()));

        assertEquals(BattleMatch.State.FINISHED, surrendered.state());
        assertEquals(BattleMatch.ResolutionReason.SURRENDER, surrendered.result().reason());
        assertEquals(PLAYER_TWO.toString(), surrendered.result().winnerId());
        assertEquals(NOW.minusNanos(1), surrendered.result().resolvedAt());
        assertEquals(6, surrendered.aggregateVersion());
        assertEquals(1, receipts.receipts.size());
        assertEquals(List.of(MATCH_ID), published);
        assertEquals(1, clockReads.get());
    }

    @Test
    void d3Sec001RejectsACommandFromASupersededTransportGeneration() {
        FakeMatches matches = new FakeMatches(runningSnapshotWithReplacementConnection());
        FakeReceipts receipts = new FakeReceipts();
        BattleMatchCommandService service = service(matches, receipts);

        assertThrows(
                IllegalStateException.class,
                () -> service.handle(
                        MATCH_ID,
                        COMMAND_ONE,
                        PLAYER_ONE,
                        1,
                        new BattleMatch.Surrender(PLAYER_ONE.toString())));

        assertEquals(BattleMatch.State.RUNNING, matches.snapshot.state());
        assertNull(matches.snapshot.result());
        assertEquals(0, receipts.receipts.size());
    }

    @Test
    void d3Sec001ChecksTheCurrentGenerationBeforeReplayingACommandReceipt() {
        FakeMatches matches = new FakeMatches(initialSnapshot());
        FakeReceipts receipts = new FakeReceipts();
        BattleMatchCommandService service = service(matches, receipts);
        service.handle(
                MATCH_ID,
                COMMAND_ONE,
                PLAYER_ONE,
                PLAYER_ONE_GENERATION,
                new BattleMatch.Ready(PLAYER_ONE.toString()));
        BattleMatch replacement = BattleMatch.restore(
                matches.snapshot, Clock.fixed(NOW, ZoneOffset.UTC));
        replacement.handle(new BattleMatch.Reconnect(PLAYER_ONE.toString(), 3));
        matches.snapshot = replacement.snapshot();

        assertThrows(
                IllegalStateException.class,
                () -> service.handle(
                        MATCH_ID,
                        COMMAND_ONE,
                        PLAYER_ONE,
                        PLAYER_ONE_GENERATION,
                        new BattleMatch.Ready(PLAYER_ONE.toString())));

        assertEquals(1, receipts.receipts.size());
        assertEquals(3L, matches.snapshot.players().stream()
                .filter(player -> player.playerId().equals(PLAYER_ONE.toString()))
                .findFirst()
                .orElseThrow()
                .completedConnectionGeneration());
    }

    private static BattleMatchCommandService service(FakeMatches matches, FakeReceipts receipts) {
        return new BattleMatchCommandService(
                matches,
                receipts,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10),
                DirectTransactions.INSTANCE,
                matchId -> {});
    }

    private static BattleMatch.Snapshot initialSnapshot() {
        BattleMatch match = new BattleMatch(
                MATCH_ID.toString(),
                PLAYER_ONE.toString(),
                PLAYER_TWO.toString(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE.toString(), PLAYER_ONE_GENERATION));
        match.handle(new BattleMatch.Reconnect(PLAYER_TWO.toString(), PLAYER_TWO_GENERATION));
        return match.snapshot();
    }

    private static BattleMatch.Snapshot runningSnapshotAtDeadline() {
        Instant startedAt = NOW.minus(Duration.ofMinutes(10));
        BattleMatch match = new BattleMatch(
                MATCH_ID.toString(),
                PLAYER_ONE.toString(),
                PLAYER_TWO.toString(),
                Clock.fixed(startedAt, ZoneOffset.UTC));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE.toString(), PLAYER_ONE_GENERATION));
        match.handle(new BattleMatch.Reconnect(PLAYER_TWO.toString(), PLAYER_TWO_GENERATION));
        match.handle(new BattleMatch.Ready(PLAYER_ONE.toString()));
        match.handle(new BattleMatch.Ready(PLAYER_TWO.toString()));
        match.handle(new BattleMatch.Start(Duration.ofMinutes(10)));
        return match.snapshot();
    }

    private static BattleMatch.Snapshot runningSnapshotWithReplacementConnection() {
        BattleMatch match = new BattleMatch(
                MATCH_ID.toString(),
                PLAYER_ONE.toString(),
                PLAYER_TWO.toString(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE.toString(), 1));
        match.handle(new BattleMatch.Reconnect(PLAYER_TWO.toString(), 2));
        match.handle(new BattleMatch.Ready(PLAYER_ONE.toString()));
        match.handle(new BattleMatch.Ready(PLAYER_TWO.toString()));
        match.handle(new BattleMatch.Start(Duration.ofMinutes(10)));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE.toString(), 3));
        return match.snapshot();
    }

    private static final class FakeMatches implements BattleMatchRepository {
        private final List<Long> expectedVersions = new ArrayList<>();
        private BattleMatch.Snapshot snapshot;

        private FakeMatches(BattleMatch.Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Optional<BattleMatch.Snapshot> findById(UUID matchId) {
            return MATCH_ID.equals(matchId) ? Optional.of(snapshot) : Optional.empty();
        }

        @Override
        public void save(BattleMatch.Snapshot saved, long expectedVersion) {
            if (snapshot.aggregateVersion() != expectedVersion) {
                throw new OptimisticMatchConflictException();
            }
            expectedVersions.add(expectedVersion);
            snapshot = saved;
        }
    }

    private static final class FakeReceipts implements BattleCommandReceiptStore {
        private final Map<UUID, Receipt> receipts = new HashMap<>();

        @Override
        public Optional<Receipt> findByCommandId(UUID commandId) {
            return Optional.ofNullable(receipts.get(commandId));
        }

        @Override
        public void record(Receipt receipt) {
            if (receipts.putIfAbsent(receipt.commandId(), receipt) != null) {
                throw new IllegalStateException("duplicate command receipt");
            }
        }
    }

    private enum DirectTransactions implements TransactionOperations {
        INSTANCE;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }
}
