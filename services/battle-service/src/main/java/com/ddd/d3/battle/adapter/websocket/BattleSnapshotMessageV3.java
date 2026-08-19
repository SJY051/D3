package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.application.BattleAttackView;
import com.ddd.d3.battle.application.BattleMatchView;
import com.ddd.d3.battle.application.BattleJudgeReferenceStore;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BattleSnapshotMessageV3(
        String type, int version, UUID matchId, long sequence, Instant serverTime, Payload payload) {
    private static final String MESSAGE_TYPE = "BATTLE_SNAPSHOT";
    private static final int MESSAGE_VERSION = 3;

    public static BattleSnapshotMessageV3 from(
            BattleMatchView match, BattleAttackView attack, BattleJudgeReferenceStore.SubmissionVerdict submission) {
        Objects.requireNonNull(match);
        Objects.requireNonNull(attack);
        if (!match.matchId().equals(attack.matchId())) throw new IllegalArgumentException("snapshot matchIds differ");
        return new BattleSnapshotMessageV3(MESSAGE_TYPE, MESSAGE_VERSION, match.matchId(),
                Math.addExact(match.aggregateVersion(), attack.sequence()), attack.serverTime(),
                new Payload(BattleSnapshotMessageV2.from(match).payload(), attack(attack), submission(submission)));
    }

    private static Attack attack(BattleAttackView view) {
        CurrentAttack current = view.attack() == null ? null : new CurrentAttack(
                view.attack().attackId(), view.attack().phase().name(), view.attack().target().name(),
                view.attack().warningDeadline(), view.attack().overlayExpiresAt(), view.attack().overlaySeed(),
                view.attack().resolution() == null ? null : view.attack().resolution().name());
        return new Attack(view.selfEnergy(), view.opponentEnergy(), view.maximumEnergy(),
                view.attackCost(), view.blockCost(), view.reflectCost(), current);
    }

    // Null when the viewer has no settled submission; the field stays viewer-scoped so
    // an opponent frame never carries this verdict.
    private static Submission submission(BattleJudgeReferenceStore.SubmissionVerdict verdict) {
        return verdict == null ? null
                : new Submission(verdict.status(), verdict.attemptNumber(), "ACCEPTED".equals(verdict.status()));
    }

    public record Payload(BattleSnapshotMessageV2.Payload match, Attack attack, Submission submission) {}
    public record Submission(String verdict, int attemptNumber, boolean acceptedLocked) {}
    public record Attack(
            int selfEnergy, int opponentEnergy, int maximumEnergy,
            int attackCost, int blockCost, int reflectCost,
            CurrentAttack current) {}
    public record CurrentAttack(String attackId, String phase, String target, Instant warningDeadline,
            Instant overlayExpiresAt, long overlaySeed, String resolution) {}
}
