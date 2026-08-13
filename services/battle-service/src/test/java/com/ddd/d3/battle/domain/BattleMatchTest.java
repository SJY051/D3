package com.ddd.d3.battle.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BattleMatchTest {

    private static final String PLAYER_ONE = "player-one";
    private static final String PLAYER_TWO = "player-two";
    private static final Instant START = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void d3Btl002ProgressesThroughServerOwnedLifecycleStates() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = new BattleMatch("match-1", PLAYER_ONE, PLAYER_TWO, clock);

        assertEquals(BattleMatch.State.LOBBY, match.state());

        match.handle(new BattleMatch.Ready(PLAYER_ONE));
        assertEquals(BattleMatch.State.LOBBY, match.state());

        match.handle(new BattleMatch.Ready(PLAYER_TWO));
        assertEquals(BattleMatch.State.READY, match.state());

        match.handle(new BattleMatch.Start(Duration.ofMinutes(10)));
        assertEquals(BattleMatch.State.RUNNING, match.state());
        assertEquals(START, match.startedAt());
        assertEquals(START.plus(Duration.ofMinutes(10)), match.matchDeadline());

        match.handle(new BattleMatch.BeginJudging());
        assertEquals(BattleMatch.State.JUDGING, match.state());

        match.handle(new BattleMatch.PlatformIncident("incident-1"));
        assertEquals(BattleMatch.State.FINISHED, match.state());
        assertEquals(BattleMatch.Outcome.VOID, match.result().orElseThrow().outcome());
        assertNull(match.result().orElseThrow().winnerId());
    }

    @Test
    void d3Btl002RejectsCommandsThatDoNotMatchTheCurrentState() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = new BattleMatch("match-1", PLAYER_ONE, PLAYER_TWO, clock);

        assertThrows(
                IllegalStateException.class,
                () -> match.handle(new BattleMatch.Start(Duration.ofMinutes(10))));

        match.handle(new BattleMatch.Ready(PLAYER_ONE));
        match.handle(new BattleMatch.Ready(PLAYER_TWO));

        assertThrows(
                IllegalStateException.class,
                () -> match.handle(new BattleMatch.BeginJudging()));
    }

    @Test
    void d3Btl002UsesTheServerClockImmediatelyBeforeAndAtTheMatchDeadline() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = runningMatch(clock);

        clock.advance(Duration.ofMinutes(10).minusNanos(1));
        match.handle(new BattleMatch.AdvanceTime());
        assertEquals(BattleMatch.State.RUNNING, match.state());

        clock.advance(Duration.ofNanos(1));
        match.handle(new BattleMatch.AdvanceTime());
        assertEquals(BattleMatch.State.JUDGING, match.state());
    }

    @Test
    void d3Btl002MovesToJudgingAfterTheMatchDeadline() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = runningMatch(clock);

        clock.advance(Duration.ofMinutes(10).plusNanos(1));
        match.handle(new BattleMatch.AdvanceTime());

        assertEquals(BattleMatch.State.JUDGING, match.state());
    }

    @Test
    void d3Btl002ReconnectsAtTwentyNinePointNineNineNineSeconds() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = runningMatch(clock);

        match.handle(new BattleMatch.Disconnect(PLAYER_ONE));
        assertTrue(match.isDisconnected(PLAYER_ONE));
        assertEquals(START.plusSeconds(30), match.reconnectDeadline(PLAYER_ONE).orElseThrow());

        clock.advance(Duration.ofMillis(29_999));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE));

        assertFalse(match.isDisconnected(PLAYER_ONE));
        assertEquals(BattleMatch.State.RUNNING, match.state());
    }

    @Test
    void d3Btl002TreatsARepeatedSuccessfulReconnectAsAnIdempotentRetry() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = runningMatch(clock);

        match.handle(new BattleMatch.Disconnect(PLAYER_ONE));
        clock.advance(Duration.ofSeconds(1));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE));

        match.handle(new BattleMatch.Reconnect(PLAYER_ONE));

        assertFalse(match.isDisconnected(PLAYER_ONE));
        assertEquals(BattleMatch.State.RUNNING, match.state());
    }

    @Test
    void d3Btl002ReconnectAtThirtySecondsLosesToTheServerDeadline() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = runningMatch(clock);

        match.handle(new BattleMatch.Disconnect(PLAYER_ONE));
        clock.advance(Duration.ofSeconds(30));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE));

        BattleMatch.Result result = match.result().orElseThrow();
        assertEquals(BattleMatch.State.FINISHED, match.state());
        assertEquals(BattleMatch.Outcome.WIN, result.outcome());
        assertEquals(PLAYER_TWO, result.winnerId());
        assertEquals(BattleMatch.ResolutionReason.DISCONNECT_TIMEOUT, result.reason());
        assertEquals(START.plusSeconds(30), result.resolvedAt());
    }

    @Test
    void d3Btl002DisconnectTimeoutAlsoResolvesAfterTheReconnectDeadline() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = runningMatch(clock);

        match.handle(new BattleMatch.Disconnect(PLAYER_TWO));
        clock.advance(Duration.ofSeconds(30).plusNanos(1));
        match.handle(new BattleMatch.AdvanceTime());

        BattleMatch.Result result = match.result().orElseThrow();
        assertEquals(BattleMatch.State.FINISHED, match.state());
        assertEquals(PLAYER_ONE, result.winnerId());
        assertEquals(BattleMatch.ResolutionReason.DISCONNECT_TIMEOUT, result.reason());
    }

    @Test
    void d3Btl002SurrenderImmediatelyAwardsTheOpponentTheWin() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = runningMatch(clock);

        match.handle(new BattleMatch.Surrender(PLAYER_ONE));

        BattleMatch.Result result = match.result().orElseThrow();
        assertEquals(BattleMatch.State.FINISHED, match.state());
        assertEquals(BattleMatch.Outcome.WIN, result.outcome());
        assertEquals(PLAYER_TWO, result.winnerId());
        assertEquals(BattleMatch.ResolutionReason.SURRENDER, result.reason());
        assertEquals(START, result.resolvedAt());

        clock.advance(Duration.ofSeconds(1));
        match.handle(new BattleMatch.Surrender(PLAYER_ONE));
        assertSame(result, match.result().orElseThrow());
    }

    @Test
    void d3Btl002ConfirmedPlatformIncidentVoidsTheMatchExactlyOnce() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = runningMatch(clock);

        match.handle(new BattleMatch.PlatformIncident("incident-1"));

        BattleMatch.Result result = match.result().orElseThrow();
        assertEquals(BattleMatch.State.FINISHED, match.state());
        assertEquals(BattleMatch.Outcome.VOID, result.outcome());
        assertNull(result.winnerId());
        assertEquals(BattleMatch.ResolutionReason.PLATFORM_INCIDENT, result.reason());
        assertEquals("incident-1", result.incidentReference());

        match.handle(new BattleMatch.PlatformIncident("incident-1"));
        assertSame(result, match.result().orElseThrow());
    }

    @Test
    void d3Btl002RacingTerminalCommandsKeepTheFirstResolution() throws Exception {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = runningMatch(clock);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> surrender = executor.submit(() -> {
                await(start);
                match.handle(new BattleMatch.Surrender(PLAYER_ONE));
            });
            Future<?> incident = executor.submit(() -> {
                await(start);
                match.handle(new BattleMatch.PlatformIncident("incident-1"));
            });

            start.countDown();
            surrender.get(5, TimeUnit.SECONDS);
            incident.get(5, TimeUnit.SECONDS);
        }

        BattleMatch.Result result = match.result().orElseThrow();
        assertEquals(BattleMatch.State.FINISHED, match.state());
        assertTrue(Set.of(
                        BattleMatch.ResolutionReason.SURRENDER,
                        BattleMatch.ResolutionReason.PLATFORM_INCIDENT)
                .contains(result.reason()));

        match.handle(new BattleMatch.Surrender(PLAYER_ONE));
        match.handle(new BattleMatch.PlatformIncident("incident-1"));
        assertSame(result, match.result().orElseThrow());
    }

    @Test
    void d3Sec001RejectsCommandsFromPlayersOutsideTheMatch() {
        MutableClock clock = new MutableClock(START);
        BattleMatch match = runningMatch(clock);

        assertThrows(
                IllegalArgumentException.class,
                () -> match.handle(new BattleMatch.Disconnect("outsider")));
        assertThrows(
                IllegalArgumentException.class,
                () -> match.handle(new BattleMatch.Surrender("outsider")));
    }

    @Test
    void d3Btl002RequiresTwoDistinctParticipants() {
        MutableClock clock = new MutableClock(START);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BattleMatch("match-1", PLAYER_ONE, PLAYER_ONE, clock));
    }

    private static BattleMatch runningMatch(MutableClock clock) {
        BattleMatch match = new BattleMatch("match-1", PLAYER_ONE, PLAYER_TWO, clock);
        match.handle(new BattleMatch.Ready(PLAYER_ONE));
        match.handle(new BattleMatch.Ready(PLAYER_TWO));
        match.handle(new BattleMatch.Start(Duration.ofMinutes(10)));
        return match;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
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

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
