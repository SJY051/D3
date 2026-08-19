package com.ddd.d3.battle.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BattleSubmissionViewService {

    private final BattleJudgeReferenceStore references;

    public BattleSubmissionViewService(BattleJudgeReferenceStore references) {
        this.references = Objects.requireNonNull(references, "references must not be null");
    }

    public Optional<BattleJudgeReferenceStore.SubmissionVerdict> read(UUID matchId, UUID viewerId) {
        return references.findLatestSubmissionVerdict(matchId, viewerId);
    }
}
