package com.ddd.d3.battle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BattleMatchViewServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_ONE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLAYER_TWO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OUTSIDER = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void d3Btl002ProjectsOneAuthoritativeParticipantScopedSnapshot() {
        BattleMatch match = runningMatch();
        match.handle(new BattleMatch.Reconnect(PLAYER_TWO.toString(), 17));
        match.handle(new BattleMatch.Disconnect(PLAYER_TWO.toString(), 17));
        BattleMatchViewService service = service(match.snapshot());

        BattleMatchView view = service.read(MATCH_ID, PLAYER_ONE);

        assertEquals(MATCH_ID, view.matchId());
        assertEquals(5, view.aggregateVersion());
        assertEquals(NOW, view.serverTime());
        assertEquals(BattleMatchView.State.RUNNING, view.state());
        assertEquals(NOW, view.startedAt());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), view.matchDeadline());
        assertEquals(PLAYER_ONE, view.self().playerId());
        assertEquals(BattleMatchView.ConnectionState.CONNECTED, view.self().connectionState());
        assertNull(view.self().reconnectDeadline());
        assertEquals(PLAYER_TWO, view.opponent().playerId());
        assertEquals(BattleMatchView.ConnectionState.DISCONNECTED, view.opponent().connectionState());
        assertEquals(NOW.plusSeconds(30), view.opponent().reconnectDeadline());
        assertNull(view.result());
    }

    @Test
    void d3Sec001ReturnsNotFoundForANonParticipant() {
        BattleMatchViewService service = service(runningMatch().snapshot());

        assertThrows(BattleMatchNotFoundException.class, () -> service.read(MATCH_ID, OUTSIDER));
    }

    @Test
    void d3Btl002MapsVoidWithoutExposingTheIncidentReference() {
        BattleMatch match = runningMatch();
        match.handle(new BattleMatch.PlatformIncident("private-provider-incident"));
        BattleMatchViewService service = service(match.snapshot());

        BattleMatchView view = service.read(MATCH_ID, PLAYER_TWO);

        assertEquals(BattleMatchView.State.FINISHED, view.state());
        assertEquals(BattleMatchView.Outcome.VOIDED, view.result().outcome());
        assertNull(view.result().winnerId());
        assertEquals(BattleMatchView.ResolutionReason.PLATFORM_INCIDENT, view.result().reason());
        assertEquals(NOW, view.result().resolvedAt());
    }

    private static BattleMatch runningMatch() {
        BattleMatch match = new BattleMatch(
                MATCH_ID.toString(), PLAYER_ONE.toString(), PLAYER_TWO.toString(), CLOCK);
        match.handle(new BattleMatch.Ready(PLAYER_ONE.toString()));
        match.handle(new BattleMatch.Ready(PLAYER_TWO.toString()));
        match.handle(new BattleMatch.Start(Duration.ofMinutes(10)));
        return match;
    }

    private static BattleMatchViewService service(BattleMatch.Snapshot snapshot) {
        BattleMatchRepository matches = new BattleMatchRepository() {
            @Override
            public Optional<BattleMatch.Snapshot> findById(UUID matchId) {
                return MATCH_ID.equals(matchId) ? Optional.of(snapshot) : Optional.empty();
            }

            @Override
            public void save(BattleMatch.Snapshot saved, long expectedVersion) {
                throw new UnsupportedOperationException("view service must remain read-only");
            }
        };
        return new BattleMatchViewService(matches, CLOCK);
    }
}
