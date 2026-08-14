package com.ddd.d3.battle.application;

import java.util.Objects;
import java.util.UUID;

public final class ActiveRankedMatchConflictException extends RuntimeException {

    private final UUID playerId;
    private final UUID matchId;

    public ActiveRankedMatchConflictException(UUID playerId, UUID matchId) {
        super("Player already belongs to an active ranked match");
        this.playerId = Objects.requireNonNull(playerId, "playerId must not be null");
        this.matchId = Objects.requireNonNull(matchId, "matchId must not be null");
    }

    public UUID playerId() {
        return playerId;
    }

    public UUID matchId() {
        return matchId;
    }
}
