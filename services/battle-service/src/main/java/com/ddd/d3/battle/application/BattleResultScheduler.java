package com.ddd.d3.battle.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class BattleResultScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleResultScheduler.class);

    private final BattleResultService results;
    private final int batchSize;

    public BattleResultScheduler(BattleResultService results, int batchSize) {
        this.results = results;
        if (batchSize <= 0 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${d3.battle.result-driver.poll-interval:500ms}")
    public void completeReadyMatches() {
        try {
            results.completeBatch(batchSize);
        } catch (RuntimeException exception) {
            LOGGER.warn("Battle result completion deferred with {}", exception.getClass().getSimpleName());
        }
    }
}
