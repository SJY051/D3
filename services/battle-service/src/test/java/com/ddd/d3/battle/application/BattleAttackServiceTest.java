package com.ddd.d3.battle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ddd.d3.battle.domain.BattleMatch;
import com.ddd.d3.battle.domain.attack.GarbageAttackExchange;
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
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class BattleAttackServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_ONE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLAYER_TWO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID COMMAND_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    void d3Btl003PersistsAndIdempotentlyReplaysAnAuthoritativeAttackCommand() {
        MutableClock clock = new MutableClock(NOW);
        FakeEvents events = new FakeEvents();
        FakeReceipts receipts = new FakeReceipts();
        List<UUID> published = new ArrayList<>();
        BattleAttackService service = service(clock, events, receipts, published);

        service.awardProgress(MATCH_ID, PLAYER_ONE, "accepted:one");
        service.awardProgress(MATCH_ID, PLAYER_ONE, "accepted:two");
        BattleAttackView launched = service.launch(MATCH_ID, COMMAND_ID, PLAYER_ONE, 1, "attack-one");
        BattleAttackView replayed = service.launch(MATCH_ID, COMMAND_ID, PLAYER_ONE, 1, "attack-one");

        assertEquals(launched, replayed);
        assertEquals(0, launched.selfEnergy());
        assertEquals(BattleAttackView.Target.OPPONENT, launched.attack().target());
        assertEquals(GarbageAttackExchange.Phase.WARNING, launched.attack().phase());
        assertEquals(
                List.of(
                        GarbageAttackExchange.EventType.ENERGY_GRANTED,
                        GarbageAttackExchange.EventType.ENERGY_GRANTED,
                        GarbageAttackExchange.EventType.ATTACK_WARNED),
                events.events.stream().map(GarbageAttackExchange.AttackEvent::type).toList());
        assertEquals(1, receipts.receipts.size());
        assertEquals(3, receipts.receipts.get(COMMAND_ID).aggregateVersion());
        assertEquals(List.of(MATCH_ID, MATCH_ID, MATCH_ID, MATCH_ID), published);
    }

    @Test
    void d3Btl004AccruesServerOwnedPassiveEnergyBeforeAProductionAttackCommand() {
        MutableClock clock = new MutableClock(NOW.plusSeconds(80));
        FakeEvents events = new FakeEvents();
        BattleAttackService service = service(clock, events, new FakeReceipts(), new ArrayList<>());

        BattleAttackView launched = service.launch(MATCH_ID, COMMAND_ID, PLAYER_ONE, 1, "attack-one");

        assertEquals(0, launched.selfEnergy());
        assertEquals(GarbageAttackExchange.Phase.WARNING, launched.attack().phase());
        assertEquals(17, events.events.size());
        assertEquals(
                16,
                events.events.stream()
                        .filter(event -> event.type() == GarbageAttackExchange.EventType.ENERGY_GRANTED)
                        .count());
        assertEquals(GarbageAttackExchange.EventType.ATTACK_WARNED, events.events.getLast().type());
    }

    @Test
    void d3Sec001RejectsAnAttackFromASupersededConnectionGeneration() {
        MutableClock clock = new MutableClock(NOW);
        FakeEvents events = new FakeEvents();
        FakeReceipts receipts = new FakeReceipts();
        BattleAttackService service = service(clock, events, receipts, new ArrayList<>());
        service.awardProgress(MATCH_ID, PLAYER_ONE, "accepted:one");
        service.awardProgress(MATCH_ID, PLAYER_ONE, "accepted:two");

        assertThrows(
                IllegalStateException.class,
                () -> service.launch(MATCH_ID, COMMAND_ID, PLAYER_ONE, 99, "attack-one"));

        assertEquals(2, events.events.size());
        assertEquals(0, receipts.receipts.size());
    }

    @Test
    void d3Btl003ProjectsTheSameAttackRelativeToEachViewer() {
        MutableClock clock = new MutableClock(NOW);
        FakeEvents events = new FakeEvents();
        BattleAttackService service = service(clock, events, new FakeReceipts(), new ArrayList<>());
        service.awardProgress(MATCH_ID, PLAYER_ONE, "accepted:one");
        service.awardProgress(MATCH_ID, PLAYER_ONE, "accepted:two");
        BattleAttackView actor = service.launch(MATCH_ID, COMMAND_ID, PLAYER_ONE, 1, "attack-one");

        BattleAttackView target = service.read(MATCH_ID, PLAYER_TWO);

        assertEquals(BattleAttackView.Target.OPPONENT, actor.attack().target());
        assertEquals(BattleAttackView.Target.SELF, target.attack().target());
        assertEquals(actor.attack().overlaySeed(), target.attack().overlaySeed());
    }

    @Test
    void d3Btl004PersistsAFiniteTerminalStateAfterASingleLateServerTick() {
        MutableClock clock = new MutableClock(NOW);
        FakeEvents events = new FakeEvents();
        BattleAttackService service = service(clock, events, new FakeReceipts(), new ArrayList<>());
        service.awardProgress(MATCH_ID, PLAYER_ONE, "accepted:one");
        service.awardProgress(MATCH_ID, PLAYER_ONE, "accepted:two");
        service.launch(MATCH_ID, COMMAND_ID, PLAYER_ONE, 1, "attack-one");

        clock.set(NOW.plus(Duration.ofMinutes(1)));
        BattleAttackView expired = service.read(MATCH_ID, PLAYER_ONE);

        assertEquals(GarbageAttackExchange.Phase.RESOLVED, expired.attack().phase());
        assertEquals(GarbageAttackExchange.Resolution.EXPIRED, expired.attack().resolution());
        assertEquals(
                1L,
                events.events.stream()
                        .filter(event -> event.type() == GarbageAttackExchange.EventType.OVERLAY_EXPIRED)
                        .count());
        assertEquals(expired.sequence(), events.events.getLast().sequence());
    }

    private static BattleAttackService service(
            Clock clock, FakeEvents events, FakeReceipts receipts, List<UUID> published) {
        return new BattleAttackService(
                new FakeMatches(runningSnapshot()),
                events,
                receipts,
                clock,
                GarbageAttackExchange.Policy.initial(),
                () -> 923L,
                DirectTransactions.INSTANCE,
                published::add);
    }

    private static BattleMatch.Snapshot runningSnapshot() {
        BattleMatch match = new BattleMatch(
                MATCH_ID.toString(), PLAYER_ONE.toString(), PLAYER_TWO.toString(), Clock.fixed(NOW, ZoneOffset.UTC));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE.toString(), 1));
        match.handle(new BattleMatch.Reconnect(PLAYER_TWO.toString(), 2));
        match.handle(new BattleMatch.Ready(PLAYER_ONE.toString()));
        match.handle(new BattleMatch.Ready(PLAYER_TWO.toString()));
        match.handle(new BattleMatch.Start(Duration.ofMinutes(10)));
        return match.snapshot();
    }

    private record FakeMatches(BattleMatch.Snapshot snapshot) implements BattleMatchRepository {
        @Override
        public Optional<BattleMatch.Snapshot> findById(UUID matchId) {
            return MATCH_ID.equals(matchId) ? Optional.of(snapshot) : Optional.empty();
        }

        @Override
        public void save(BattleMatch.Snapshot saved, long expectedVersion) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeEvents implements GarbageAttackEventStore {
        private final List<GarbageAttackExchange.AttackEvent> events = new ArrayList<>();

        @Override
        public void lock(UUID matchId) {
            if (!MATCH_ID.equals(matchId)) {
                throw new IllegalArgumentException("unknown match");
            }
        }

        @Override
        public List<GarbageAttackExchange.AttackEvent> findByMatchId(UUID matchId) {
            return List.copyOf(events);
        }

        @Override
        public void append(UUID matchId, List<GarbageAttackExchange.AttackEvent> appended) {
            long expected = events.size() + 1L;
            for (GarbageAttackExchange.AttackEvent event : appended) {
                if (event.sequence() != expected++) {
                    throw new IllegalArgumentException("non-contiguous test event");
                }
                events.add(event);
            }
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
                throw new IllegalStateException("duplicate receipt");
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

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

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
            return instant;
        }
    }
}
