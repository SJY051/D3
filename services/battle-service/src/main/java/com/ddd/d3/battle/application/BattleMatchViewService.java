package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class BattleMatchViewService {

    private final BattleMatchRepository matches;
    private final Clock clock;

    public BattleMatchViewService(BattleMatchRepository matches, Clock clock) {
        this.matches = Objects.requireNonNull(matches, "matches must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public BattleMatchView read(UUID matchId, UUID viewerId) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(viewerId, "viewerId must not be null");
        BattleMatch.Snapshot snapshot = matches.findById(matchId)
                .orElseThrow(BattleMatchNotFoundException::new);
        BattleMatch.PlayerSnapshot self = player(snapshot, viewerId);
        BattleMatch.PlayerSnapshot opponent = snapshot.players().stream()
                .filter(player -> !player.playerId().equals(viewerId.toString()))
                .findFirst()
                .orElseThrow(BattleMatchNotFoundException::new);

        return new BattleMatchView(
                UUID.fromString(snapshot.matchId()),
                snapshot.aggregateVersion(),
                clock.instant(),
                BattleMatchView.State.valueOf(snapshot.state().name()),
                snapshot.startedAt(),
                snapshot.matchDeadline(),
                participant(self),
                participant(opponent),
                result(snapshot.result()));
    }

    private static BattleMatch.PlayerSnapshot player(BattleMatch.Snapshot snapshot, UUID playerId) {
        return snapshot.players().stream()
                .filter(player -> player.playerId().equals(playerId.toString()))
                .findFirst()
                .orElseThrow(BattleMatchNotFoundException::new);
    }

    private static BattleMatchView.Participant participant(BattleMatch.PlayerSnapshot player) {
        return new BattleMatchView.Participant(
                UUID.fromString(player.playerId()),
                player.ready(),
                BattleMatchView.ConnectionState.valueOf(player.connectionState().name()),
                player.reconnectDeadline());
    }

    private static BattleMatchView.Result result(BattleMatch.Result result) {
        if (result == null) {
            return null;
        }
        BattleMatchView.Outcome outcome = switch (result.outcome()) {
            case WIN -> BattleMatchView.Outcome.WIN;
            case DRAW -> BattleMatchView.Outcome.DRAW;
            case VOID -> BattleMatchView.Outcome.VOIDED;
        };
        return new BattleMatchView.Result(
                outcome,
                result.winnerId() == null ? null : UUID.fromString(result.winnerId()),
                BattleMatchView.ResolutionReason.valueOf(result.reason().name()),
                result.resolvedAt());
    }
}
