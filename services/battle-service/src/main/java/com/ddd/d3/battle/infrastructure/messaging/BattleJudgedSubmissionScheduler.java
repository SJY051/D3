package com.ddd.d3.battle.infrastructure.messaging;

import com.ddd.d3.battle.application.BattleJudgedSubmissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class BattleJudgedSubmissionScheduler {

    private final BattleJudgedSubmissionService submissions;
    private final int batchSize;

    public BattleJudgedSubmissionScheduler(
            BattleJudgedSubmissionService submissions,
            @Value("${d3.battle.judge-result-batch-size:25}") int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        this.submissions = submissions;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${d3.battle.judge-result-retry-delay:500ms}")
    public void applyPendingResults() {
        submissions.processPending(batchSize);
    }
}
