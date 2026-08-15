package com.ddd.d3.battle.adapter.websocket;

import java.util.Objects;
import java.util.UUID;

record BattleClientCommandV3(Type type, int version, UUID matchId, UUID commandId, String attackId) {
    private static final int CONTRACT_VERSION = 3;

    BattleClientCommandV3 {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        if (version != CONTRACT_VERSION) throw new IllegalArgumentException("unsupported Battle command version");
        if (type.isAttack()) {
            if (attackId == null || attackId.isBlank()) throw new IllegalArgumentException("attackId is required");
        } else if (attackId != null) {
            throw new IllegalArgumentException("attackId is only valid for attack commands");
        }
    }

    enum Type {
        READY, SURRENDER, ATTACK_LAUNCH, ATTACK_BLOCK, ATTACK_REFLECT;
        boolean isAttack() { return this == ATTACK_LAUNCH || this == ATTACK_BLOCK || this == ATTACK_REFLECT; }
    }
}
