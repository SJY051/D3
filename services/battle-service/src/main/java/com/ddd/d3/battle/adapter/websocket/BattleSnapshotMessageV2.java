package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.application.BattleMatchView;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BattleSnapshotMessageV2(
        String type,
        int version,
        UUID matchId,
        long sequence,
        Instant serverTime,
        Payload payload) {

    private static final String MESSAGE_TYPE = "MATCH_SNAPSHOT";
    private static final int MESSAGE_VERSION = 2;

    public static BattleSnapshotMessageV2 from(BattleMatchView view) {
        Objects.requireNonNull(view, "view must not be null");
        return new BattleSnapshotMessageV2(
                MESSAGE_TYPE,
                MESSAGE_VERSION,
                view.matchId(),
                view.aggregateVersion(),
                view.serverTime(),
                new Payload(
                        view.state().name(),
                        view.startedAt(),
                        view.matchDeadline(),
                        selfParticipant(view.self()),
                        opponentParticipant(view.opponent()),
                        result(view.result())));
    }

    private static SelfParticipant selfParticipant(BattleMatchView.Participant participant) {
        return new SelfParticipant(
                participant.playerId(),
                participant.ready(),
                participant.connectionState().name(),
                participant.reconnectDeadline());
    }

    private static OpponentParticipant opponentParticipant(BattleMatchView.Participant participant) {
        return new OpponentParticipant(
                participant.ready(), participant.connectionState().name(), participant.reconnectDeadline());
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
            SelfParticipant self,
            OpponentParticipant opponent,
            Result result) {}

    public record SelfParticipant(
            UUID playerId,
            boolean ready,
            String connectionState,
            Instant reconnectDeadline) {}

    public record OpponentParticipant(
            boolean ready,
            String connectionState,
            Instant reconnectDeadline) {}

    public record Result(
            String outcome,
            UUID winnerId,
            String reason,
            Instant resolvedAt) {}
}
