package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.application.BattleAttackView;
import com.ddd.d3.battle.application.BattleMatchView;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BattleSnapshotMessageV3(
        String type, int version, UUID matchId, long sequence, Instant serverTime, Payload payload) {
    private static final String MESSAGE_TYPE = "BATTLE_SNAPSHOT";
    private static final int MESSAGE_VERSION = 3;

    public static BattleSnapshotMessageV3 from(BattleMatchView match, BattleAttackView attack) {
        Objects.requireNonNull(match);
        Objects.requireNonNull(attack);
        if (!match.matchId().equals(attack.matchId())) throw new IllegalArgumentException("snapshot matchIds differ");
        return new BattleSnapshotMessageV3(MESSAGE_TYPE, MESSAGE_VERSION, match.matchId(),
                Math.addExact(match.aggregateVersion(), attack.sequence()), attack.serverTime(),
                new Payload(BattleSnapshotMessageV2.from(match).payload(), attack(attack)));
    }

    private static Attack attack(BattleAttackView view) {
        CurrentAttack current = view.attack() == null ? null : new CurrentAttack(
                view.attack().attackId(), view.attack().phase().name(), view.attack().target().name(),
                view.attack().warningDeadline(), view.attack().overlayExpiresAt(), view.attack().overlaySeed(),
                view.attack().resolution() == null ? null : view.attack().resolution().name());
        return new Attack(view.selfEnergy(), view.opponentEnergy(), view.maximumEnergy(),
                view.attackCost(), view.blockCost(), view.reflectCost(), current);
    }

    public record Payload(BattleSnapshotMessageV2.Payload match, Attack attack) {}
    public record Attack(
            int selfEnergy, int opponentEnergy, int maximumEnergy,
            int attackCost, int blockCost, int reflectCost,
            CurrentAttack current) {}
    public record CurrentAttack(String attackId, String phase, String target, Instant warningDeadline,
            Instant overlayExpiresAt, long overlaySeed, String resolution) {}
}
