package com.ddd.d3.battle.adapter.websocket;

import java.util.Objects;
import java.util.UUID;

record BattleClientCommandV3(
        Type type,
        int version,
        UUID matchId,
        UUID commandId,
        String attackId,
        String sourceCode) {
    private static final int CONTRACT_VERSION = 3;

    BattleClientCommandV3 {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        if (version != CONTRACT_VERSION) throw new IllegalArgumentException("unsupported Battle command version");
        if (type.isAttack()) {
            if (attackId == null || attackId.isBlank()) throw new IllegalArgumentException("attackId is required");
            if (sourceCode != null) throw new IllegalArgumentException("sourceCode is only valid for Judge commands");
        } else if (type.isJudge()) {
            if (attackId != null) throw new IllegalArgumentException("attackId is only valid for attack commands");
            if (sourceCode == null || sourceCode.isBlank() || sourceCode.length() > 65_536) {
                throw new IllegalArgumentException("sourceCode is required for Judge commands");
            }
        } else if (attackId != null || sourceCode != null) {
            throw new IllegalArgumentException("command payload is not valid for this command type");
        }
    }

    enum Type {
        READY, SURRENDER, RUN, SUBMIT, ATTACK_LAUNCH, ATTACK_BLOCK, ATTACK_REFLECT;
        boolean isAttack() { return this == ATTACK_LAUNCH || this == ATTACK_BLOCK || this == ATTACK_REFLECT; }
        boolean isJudge() { return this == RUN || this == SUBMIT; }
    }
}
