package com.ddd.d3.battle.domain.attack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GarbageAttackExchangeTest {

    private static final String PLAYER_ONE = "player-one";
    private static final String PLAYER_TWO = "player-two";
    private static final Instant START = Instant.parse("2026-08-14T00:00:00Z");

    private MutableClock clock;
    private GarbageAttackExchange exchange;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        exchange = new GarbageAttackExchange(
                "match-one",
                PLAYER_ONE,
                PLAYER_TWO,
                clock,
                GarbageAttackExchange.Policy.initial(),
                new AtomicLong(77)::getAndIncrement);
    }

    @Test
    void d3Btl004AwardsProgressAndPassiveEnergyOnlyOncePerServerOwnedKey() {
        assertThat(exchange.awardProgress(PLAYER_ONE, "public-case-1")).isTrue();
        assertThat(exchange.awardProgress(PLAYER_ONE, "public-case-1")).isFalse();
        assertThat(exchange.energy(PLAYER_ONE)).isEqualTo(20);

        assertThat(exchange.awardPassive(PLAYER_ONE)).isTrue();
        assertThat(exchange.awardPassive(PLAYER_ONE)).isFalse();
        assertThat(exchange.energy(PLAYER_ONE)).isEqualTo(25);

        clock.advance(Duration.ofSeconds(10));
        assertThat(exchange.awardPassive(PLAYER_ONE)).isTrue();
        assertThat(exchange.energy(PLAYER_ONE)).isEqualTo(30);
    }

    @Test
    void d3Btl004WarnsThenLetsTheTargetBlockBeforeTheDeadline() {
        grant(exchange, PLAYER_ONE, 2);
        grant(exchange, PLAYER_TWO, 1);

        var warned = exchange.launch("attack-one", PLAYER_ONE);

        assertThat(warned.phase()).isEqualTo(GarbageAttackExchange.Phase.WARNING);
        assertThat(warned.targetPlayerId()).isEqualTo(PLAYER_TWO);
        assertThat(warned.warningDeadline()).isEqualTo(START.plusSeconds(2));
        assertThat(exchange.energy(PLAYER_ONE)).isZero();

        var blocked = exchange.block("attack-one", PLAYER_TWO);

        assertThat(blocked.phase()).isEqualTo(GarbageAttackExchange.Phase.RESOLVED);
        assertThat(blocked.resolution()).isEqualTo(GarbageAttackExchange.Resolution.BLOCKED);
        assertThat(exchange.energy(PLAYER_TWO)).isZero();
        assertThat(exchange.activeOverlay()).isEmpty();
    }

    @Test
    void d3Btl004AllowsOneReflectionAndStartsANewWarningForTheOriginalActor() {
        grant(exchange, PLAYER_ONE, 4);
        grant(exchange, PLAYER_TWO, 2);
        exchange.launch("attack-one", PLAYER_ONE);
        clock.advance(Duration.ofSeconds(1));

        var reflected = exchange.reflect("attack-one", PLAYER_TWO);

        assertThat(reflected.reflected()).isTrue();
        assertThat(reflected.targetPlayerId()).isEqualTo(PLAYER_ONE);
        assertThat(reflected.warningDeadline()).isEqualTo(START.plusSeconds(3));
        assertThat(exchange.energy(PLAYER_TWO)).isEqualTo(10);
        assertThatThrownBy(() -> exchange.reflect("attack-one", PLAYER_ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already reflected");
    }

    @Test
    void d3Btl004ActivatesAndExpiresTheOverlayAtServerOwnedBoundaries() {
        grant(exchange, PLAYER_ONE, 2);
        exchange.launch("attack-one", PLAYER_ONE);

        clock.advance(Duration.ofSeconds(2));
        exchange.advanceTime();

        var overlay = exchange.activeOverlay().orElseThrow();
        assertThat(overlay.attackId()).isEqualTo("attack-one");
        assertThat(overlay.targetPlayerId()).isEqualTo(PLAYER_TWO);
        assertThat(overlay.overlaySeed()).isEqualTo(77L);
        assertThat(overlay.expiresAt()).isEqualTo(START.plusSeconds(5));

        clock.advance(Duration.ofSeconds(3));
        exchange.advanceTime();

        assertThat(exchange.currentAttack().orElseThrow().resolution())
                .isEqualTo(GarbageAttackExchange.Resolution.EXPIRED);
        assertThat(exchange.activeOverlay()).isEmpty();
    }

    @Test
    void d3Btl004RejectsInsufficientEnergyUnknownPlayersAndLateCounters() {
        assertThatThrownBy(() -> exchange.launch("attack-one", PLAYER_ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("energy");
        assertThatThrownBy(() -> exchange.awardProgress("outsider", "case"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("participant");

        grant(exchange, PLAYER_ONE, 2);
        grant(exchange, PLAYER_TWO, 2);
        exchange.launch("attack-one", PLAYER_ONE);
        clock.advance(Duration.ofSeconds(2));

        assertThatThrownBy(() -> exchange.block("attack-one", PLAYER_TWO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("warning");
    }

    @Test
    void d3Btl004ReplaysTheSameDiagnosticStateFromTheAppendOnlyEvents() {
        grant(exchange, PLAYER_ONE, 4);
        grant(exchange, PLAYER_TWO, 2);
        exchange.launch("attack-one", PLAYER_ONE);
        clock.advance(Duration.ofSeconds(1));
        exchange.reflect("attack-one", PLAYER_TWO);
        clock.advance(Duration.ofSeconds(2));
        exchange.advanceTime();

        var replayed = GarbageAttackExchange.diagnosticReplay(
                "match-one",
                PLAYER_ONE,
                PLAYER_TWO,
                clock,
                GarbageAttackExchange.Policy.initial(),
                () -> 999L,
                exchange.events());

        assertThat(replayed.energy(PLAYER_ONE)).isEqualTo(exchange.energy(PLAYER_ONE));
        assertThat(replayed.energy(PLAYER_TWO)).isEqualTo(exchange.energy(PLAYER_TWO));
        assertThat(replayed.currentAttack()).isEqualTo(exchange.currentAttack());
        assertThat(replayed.events()).isEqualTo(exchange.events());
    }
    @Test
    void d3Btl004ConsumesAProgressMarkerFirstObservedAtTheEnergyCap() {
        grant(exchange, PLAYER_ONE, 5);

        assertThat(exchange.awardProgress(PLAYER_ONE, "capped-marker")).isTrue();
        assertThat(exchange.energy(PLAYER_ONE)).isEqualTo(100);

        exchange.launch("attack-one", PLAYER_ONE);

        assertThat(exchange.awardProgress(PLAYER_ONE, "capped-marker")).isFalse();
        assertThat(exchange.energy(PLAYER_ONE)).isEqualTo(60);
    }

    @Test
    void d3Btl004ConvergesWhenOneLateTickPassesWarningAndExpiry() {
        grant(exchange, PLAYER_ONE, 2);
        exchange.launch("attack-one", PLAYER_ONE);
        clock.advance(Duration.ofSeconds(10));

        exchange.advanceTime();

        assertThat(exchange.currentAttack().orElseThrow().phase())
                .isEqualTo(GarbageAttackExchange.Phase.RESOLVED);
        assertThat(exchange.currentAttack().orElseThrow().resolution())
                .isEqualTo(GarbageAttackExchange.Resolution.EXPIRED);
        assertThat(exchange.activeOverlay()).isEmpty();
        assertThat(exchange.events())
                .extracting(GarbageAttackExchange.AttackEvent::type)
                .endsWith(
                        GarbageAttackExchange.EventType.OVERLAY_ACTIVATED,
                        GarbageAttackExchange.EventType.OVERLAY_EXPIRED);
    }

    @Test
    void d3Btl004RejectsTamperedAndNonContiguousDiagnosticEvents() {
        grant(exchange, PLAYER_ONE, 4);
        grant(exchange, PLAYER_TWO, 2);
        exchange.launch("attack-one", PLAYER_ONE);
        clock.advance(Duration.ofSeconds(1));
        exchange.reflect("attack-one", PLAYER_TWO);

        var validEvents = exchange.events();
        var reflected = validEvents.getLast();
        var tamperedEvents = new ArrayList<>(validEvents);
        tamperedEvents.set(
                tamperedEvents.size() - 1,
                new GarbageAttackExchange.AttackEvent(
                        reflected.sequence(),
                        reflected.type(),
                        reflected.playerId(),
                        reflected.key(),
                        0,
                        40,
                        reflected.attackState(),
                        reflected.occurredAt()));

        assertThatThrownBy(() -> GarbageAttackExchange.diagnosticReplay(
                        "match-one",
                        PLAYER_ONE,
                        PLAYER_TWO,
                        clock,
                        GarbageAttackExchange.Policy.initial(),
                        () -> 999L,
                        tamperedEvents))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cost");

        var first = validEvents.getFirst();
        var nonContiguousEvents = new ArrayList<>(validEvents);
        nonContiguousEvents.set(
                0,
                new GarbageAttackExchange.AttackEvent(
                        first.sequence() + 1,
                        first.type(),
                        first.playerId(),
                        first.key(),
                        first.energyDelta(),
                        first.energyAfter(),
                        first.attackState(),
                        first.occurredAt()));

        assertThatThrownBy(() -> GarbageAttackExchange.diagnosticReplay(
                        "match-one",
                        PLAYER_ONE,
                        PLAYER_TWO,
                        clock,
                        GarbageAttackExchange.Policy.initial(),
                        () -> 999L,
                        nonContiguousEvents))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }


    @Test
    void d3Btl004KeepsStoredSourceOutsideTheOverlayContract() {
        String storedSource = "public class Main { return; }";
        grant(exchange, PLAYER_ONE, 2);
        exchange.launch("attack-one", PLAYER_ONE);
        clock.advance(Duration.ofSeconds(2));
        exchange.advanceTime();

        var overlay = exchange.activeOverlay().orElseThrow();

        assertThat(storedSource).isEqualTo("public class Main { return; }");
        assertThat(overlay.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("attackId", "targetPlayerId", "overlaySeed", "expiresAt");
    }

    private static void grant(GarbageAttackExchange target, String playerId, int markers) {
        for (int index = 0; index < markers; index++) {
            target.awardProgress(playerId, "case-" + index);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
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
