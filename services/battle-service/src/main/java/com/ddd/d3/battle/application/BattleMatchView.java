package com.ddd.d3.battle.application;

import java.time.Instant;
import java.util.UUID;

public record BattleMatchView(
        UUID matchId,
        long aggregateVersion,
        Instant serverTime,
        State state,
        Instant startedAt,
        Instant matchDeadline,
        Participant self,
        Participant opponent,
        Result result) {

    public enum State {
        LOBBY,
        READY,
        RUNNING,
        JUDGING,
        FINISHED
    }

    public enum ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    public enum Outcome {
        WIN,
        DRAW,
        VOIDED
    }

    public enum ResolutionReason {
        SURRENDER,
        DISCONNECT_TIMEOUT,
        PLATFORM_INCIDENT,
        LEGACY_IMPORT
    }

    public record Participant(
            UUID playerId,
            boolean ready,
            ConnectionState connectionState,
            Instant reconnectDeadline) {}

    public record Result(
            Outcome outcome,
            UUID winnerId,
            ResolutionReason reason,
            Instant resolvedAt) {}
}
