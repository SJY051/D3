package com.ddd.d3.battle.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class BattleDeadlineScheduler {

    private final BattleDeadlineService deadlines;
    private final int batchSize;

    public BattleDeadlineScheduler(BattleDeadlineService deadlines, int batchSize) {
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${d3.battle.deadline-driver.poll-interval:500ms}")
    public void advanceDueMatches() {
        deadlines.advanceDue(batchSize);
    }
}
