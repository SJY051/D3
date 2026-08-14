package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.domain.BattleMatch;
import java.util.Objects;
import java.util.UUID;

record BattleClientCommandV2(Type type, int version, UUID matchId, UUID commandId) {

    private static final int CONTRACT_VERSION = 2;

    BattleClientCommandV2 {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        if (version != CONTRACT_VERSION) {
            throw new IllegalArgumentException("unsupported Battle command version");
        }
    }

    BattleMatch.Command toDomain(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return switch (type) {
            case READY -> new BattleMatch.Ready(actorId.toString());
            case SURRENDER -> new BattleMatch.Surrender(actorId.toString());
        };
    }

    enum Type {
        READY,
        SURRENDER
    }
}
