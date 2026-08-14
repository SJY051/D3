package com.ddd.d3.battle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

class BattleDeadlineServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_ONE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLAYER_TWO = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void d3Btl002CommitsAnExpiredReconnectAtTheExactDeadlineBeforePublishing() {
        FakeClaims claims = new FakeClaims(disconnectedAtDeadline());
        FakeMatches matches = new FakeMatches();
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
        BattleDeadlineService service = new BattleDeadlineService(
                claims,
                matches,
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactions,
                matchId -> order.add("publish-" + matchId));

        int expired = service.advanceDue(1);

        assertEquals(1, expired);
        assertEquals(BattleMatch.State.FINISHED, matches.saved.state());
        assertEquals(BattleMatch.ResolutionReason.DISCONNECT_TIMEOUT, matches.saved.result().reason());
        assertEquals(PLAYER_TWO.toString(), matches.saved.result().winnerId());
        assertEquals(NOW, matches.saved.result().resolvedAt());
        assertEquals(List.of(disconnectedAtDeadline().aggregateVersion()), matches.expectedVersions);
        assertEquals(
                List.of("transaction-start", "transaction-return", "publish-" + MATCH_ID),
                order);
    }

    @Test
    void d3Btl002LeavesAReconnectAloneBeforeItsDeadline() {
        FakeClaims claims = new FakeClaims();
        FakeMatches matches = new FakeMatches();
        List<UUID> published = new ArrayList<>();
        BattleDeadlineService service = new BattleDeadlineService(
                claims,
                matches,
                Clock.fixed(NOW.minusNanos(1), ZoneOffset.UTC),
                DirectTransactions.INSTANCE,
                published::add);

        assertEquals(0, service.advanceDue(10));
        assertEquals(null, matches.saved);
        assertEquals(List.of(), published);
        assertEquals(List.of(NOW.minusNanos(1)), claims.cutoffs);
    }

    @Test
    void d3Btl002CommitsAnEarlierMatchDeadlineBeforeALaterReconnectDeadline() {
        FakeClaims claims = new FakeClaims(disconnectedAfterMatchDeadline());
        FakeMatches matches = new FakeMatches();
        List<UUID> published = new ArrayList<>();
        BattleDeadlineService service = new BattleDeadlineService(
                claims,
                matches,
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC),
                DirectTransactions.INSTANCE,
                published::add);

        assertEquals(1, service.advanceDue(1));
        assertEquals(BattleMatch.State.JUDGING, matches.saved.state());
        assertEquals(null, matches.saved.result());
        assertEquals(List.of(MATCH_ID), published);

        BattleMatch restored = BattleMatch.restore(
                matches.saved,
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
        restored.handle(new BattleMatch.Reconnect(PLAYER_ONE.toString(), 2));
        assertEquals(BattleMatch.State.JUDGING, restored.state());
        assertEquals(null, restored.snapshot().result());
    }

    @Test
    void d3Btl002RejectsAnUnboundedExpiryBatch() {
        BattleDeadlineService service = new BattleDeadlineService(
                new FakeClaims(),
                new FakeMatches(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                DirectTransactions.INSTANCE,
                ignored -> {});

        assertThrows(IllegalArgumentException.class, () -> service.advanceDue(0));
    }

    private static BattleMatch.Snapshot disconnectedAtDeadline() {
        Instant startedAt = NOW.minusSeconds(60);
        BattleMatch match = new BattleMatch(
                MATCH_ID.toString(),
                PLAYER_ONE.toString(),
                PLAYER_TWO.toString(),
                Clock.fixed(startedAt, ZoneOffset.UTC));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE.toString(), 1));
        match.handle(new BattleMatch.Reconnect(PLAYER_TWO.toString(), 2));
        match.handle(new BattleMatch.Ready(PLAYER_ONE.toString()));
        match.handle(new BattleMatch.Ready(PLAYER_TWO.toString()));
        match.handle(new BattleMatch.Start(Duration.ofMinutes(10)));
        BattleMatch restored = BattleMatch.restore(
                match.snapshot(),
                Clock.fixed(NOW.minusSeconds(30), ZoneOffset.UTC));
        restored.handle(new BattleMatch.Disconnect(PLAYER_ONE.toString(), 1));
        return restored.snapshot();
    }

    private static BattleMatch.Snapshot disconnectedAfterMatchDeadline() {
        Instant startedAt = NOW.minus(Duration.ofMinutes(10));
        BattleMatch match = new BattleMatch(
                MATCH_ID.toString(),
                PLAYER_ONE.toString(),
                PLAYER_TWO.toString(),
                Clock.fixed(startedAt, ZoneOffset.UTC));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE.toString(), 1));
        match.handle(new BattleMatch.Reconnect(PLAYER_TWO.toString(), 2));
        match.handle(new BattleMatch.Ready(PLAYER_ONE.toString()));
        match.handle(new BattleMatch.Ready(PLAYER_TWO.toString()));
        match.handle(new BattleMatch.Start(Duration.ofMinutes(10)));
        BattleMatch restored = BattleMatch.restore(
                match.snapshot(),
                Clock.fixed(NOW.minusSeconds(10), ZoneOffset.UTC));
        restored.handle(new BattleMatch.Disconnect(PLAYER_ONE.toString(), 1));
        return restored.snapshot();
    }

    private static final class FakeClaims implements BattleDeadlineClaimStore {
        private final Deque<BattleMatch.Snapshot> claims;
        private final List<Instant> cutoffs = new ArrayList<>();

        private FakeClaims(BattleMatch.Snapshot... claims) {
            this.claims = new ArrayDeque<>(List.of(claims));
        }

        @Override
        public Optional<BattleMatch.Snapshot> claimNextDue(Instant cutoff) {
            cutoffs.add(cutoff);
            return Optional.ofNullable(claims.pollFirst());
        }
    }

    private static final class FakeMatches implements BattleMatchRepository {
        private final List<Long> expectedVersions = new ArrayList<>();
        private BattleMatch.Snapshot saved;

        @Override
        public Optional<BattleMatch.Snapshot> findById(UUID matchId) {
            return Optional.empty();
        }

        @Override
        public void save(BattleMatch.Snapshot snapshot, long expectedVersion) {
            expectedVersions.add(expectedVersion);
            saved = snapshot;
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
