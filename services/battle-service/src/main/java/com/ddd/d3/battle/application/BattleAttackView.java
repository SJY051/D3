package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.attack.GarbageAttackExchange;
import java.time.Instant;
import java.util.UUID;

public record BattleAttackView(
        UUID matchId,
        long sequence,
        Instant serverTime,
        int selfEnergy,
        int opponentEnergy,
        Attack attack) {

    public enum Target {
        SELF,
        OPPONENT
    }

    public record Attack(
            String attackId,
            GarbageAttackExchange.Phase phase,
            Target target,
            Instant warningDeadline,
            Instant overlayExpiresAt,
            long overlaySeed,
            GarbageAttackExchange.Resolution resolution) {}
}
