package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.application.BattleMatchView;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BattleSnapshotMessageV1(
        String type,
        int version,
        UUID matchId,
        long sequence,
        Instant serverTime,
        Payload payload) {

    private static final String MESSAGE_TYPE = "MATCH_SNAPSHOT";
    private static final int MESSAGE_VERSION = 1;

    public static BattleSnapshotMessageV1 from(BattleMatchView view) {
        Objects.requireNonNull(view, "view must not be null");
        return new BattleSnapshotMessageV1(
                MESSAGE_TYPE,
                MESSAGE_VERSION,
                view.matchId(),
                view.aggregateVersion(),
                view.serverTime(),
                new Payload(
                        view.state().name(),
                        view.startedAt(),
                        view.matchDeadline(),
                        participant(view.self()),
                        participant(view.opponent()),
                        result(view.result())));
    }

    private static Participant participant(BattleMatchView.Participant participant) {
        return new Participant(
                participant.playerId(),
                participant.ready(),
                participant.connectionState().name(),
                participant.reconnectDeadline());
    }

    private static Result result(BattleMatchView.Result result) {
        if (result == null) {
            return null;
        }
        return new Result(
                result.outcome().name(),
                result.winnerId(),
                result.reason().name(),
                result.resolvedAt());
    }

    public record Payload(
            String state,
            Instant startedAt,
            Instant matchDeadline,
            Participant self,
            Participant opponent,
            Result result) {}

    public record Participant(
            UUID playerId,
            boolean ready,
            String connectionState,
            Instant reconnectDeadline) {}

    public record Result(
            String outcome,
            UUID winnerId,
            String reason,
            Instant resolvedAt) {}
}
