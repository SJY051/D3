package com.ddd.d3.battle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class BattleConnectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_ONE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLAYER_TWO = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void d3Btl002OwnsTransportGenerationAndFencesALateClose() {
        FakeMatches matches = new FakeMatches(runningSnapshot());
        FakeGenerations generations = new FakeGenerations(3, 4);
        List<UUID> published = new ArrayList<>();
        BattleConnectionService service = new BattleConnectionService(
                matches,
                generations,
                Clock.fixed(NOW, ZoneOffset.UTC),
                DirectTransactions.INSTANCE,
                published::add,
                3);

        BattleConnectionService.ConnectionLease first = service.connected(MATCH_ID, PLAYER_ONE);
        service.disconnected(MATCH_ID, PLAYER_ONE, first.generation());
        BattleConnectionService.ConnectionLease replacement = service.connected(MATCH_ID, PLAYER_ONE);
        service.disconnected(MATCH_ID, PLAYER_ONE, first.generation());

        assertEquals(3, first.generation());
        assertEquals(4, replacement.generation());
        assertFalse(playerOne(matches.snapshot).connectionState() == BattleMatch.ConnectionState.DISCONNECTED);
        assertEquals(4L, playerOne(matches.snapshot).completedConnectionGeneration());
        assertEquals(List.of(MATCH_ID, MATCH_ID, MATCH_ID), published);
        assertEquals(List.of(5L, 6L, 7L), matches.expectedVersions);
    }

    @Test
    void d3Btl002LetsTheReconnectDeadlineWinAtExactlyThirtySeconds() {
        FakeMatches matches = new FakeMatches(disconnectedSnapshot());
        List<UUID> published = new ArrayList<>();
        BattleConnectionService service = new BattleConnectionService(
                matches,
                new FakeGenerations(3),
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC),
                DirectTransactions.INSTANCE,
                published::add,
                3);

        service.connected(MATCH_ID, PLAYER_ONE);

        BattleMatch.Result result = matches.snapshot.result();
        assertEquals(BattleMatch.State.FINISHED, matches.snapshot.state());
        assertEquals(BattleMatch.ResolutionReason.DISCONNECT_TIMEOUT, result.reason());
        assertEquals(PLAYER_TWO.toString(), result.winnerId());
        assertEquals(List.of(MATCH_ID), published);
    }

    @Test
    void d3Btl002RetriesAConnectionMutationAfterAnOptimisticConflict() {
        FakeMatches matches = new FakeMatches(runningSnapshot(), 1);
        List<UUID> published = new ArrayList<>();
        BattleConnectionService service = new BattleConnectionService(
                matches,
                new FakeGenerations(3),
                Clock.fixed(NOW, ZoneOffset.UTC),
                DirectTransactions.INSTANCE,
                published::add,
                3);

        BattleConnectionService.ConnectionLease lease = service.connected(MATCH_ID, PLAYER_ONE);

        assertEquals(3, lease.generation());
        assertEquals(2, matches.saveAttempts);
        assertEquals(3L, playerOne(matches.snapshot).completedConnectionGeneration());
        assertEquals(List.of(MATCH_ID), published);
    }

    private static BattleMatch.Snapshot runningSnapshot() {
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
        return match.snapshot();
    }

    private static BattleMatch.Snapshot disconnectedSnapshot() {
        BattleMatch match = BattleMatch.restore(
                runningSnapshot(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        match.handle(new BattleMatch.Disconnect(PLAYER_ONE.toString(), 1));
        return match.snapshot();
    }

    private static BattleMatch.PlayerSnapshot playerOne(BattleMatch.Snapshot snapshot) {
        return snapshot.players().stream()
                .filter(player -> player.playerId().equals(PLAYER_ONE.toString()))
                .findFirst()
                .orElseThrow();
    }

    private static final class FakeMatches implements BattleMatchRepository {
        private final List<Long> expectedVersions = new ArrayList<>();
        private BattleMatch.Snapshot snapshot;
        private int failuresRemaining;
        private int saveAttempts;

        private FakeMatches(BattleMatch.Snapshot snapshot) {
            this(snapshot, 0);
        }

        private FakeMatches(BattleMatch.Snapshot snapshot, int failuresRemaining) {
            this.snapshot = snapshot;
            this.failuresRemaining = failuresRemaining;
        }

        @Override
        public Optional<BattleMatch.Snapshot> findById(UUID matchId) {
            return MATCH_ID.equals(matchId) ? Optional.of(snapshot) : Optional.empty();
        }

        @Override
        public void save(BattleMatch.Snapshot saved, long expectedVersion) {
            saveAttempts++;
            if (failuresRemaining-- > 0) {
                throw new OptimisticMatchConflictException();
            }
            if (snapshot.aggregateVersion() != expectedVersion) {
                throw new OptimisticMatchConflictException();
            }
            expectedVersions.add(expectedVersion);
            snapshot = saved;
        }
    }

    private static final class FakeGenerations implements BattleConnectionGenerationSource {
        private final Deque<Long> generations = new ArrayDeque<>();

        private FakeGenerations(long... values) {
            for (long value : values) {
                generations.add(value);
            }
        }

        @Override
        public long nextGeneration() {
            return generations.removeFirst();
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
