package com.ddd.d3.battle.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class BattleReconnectExpiryScheduler {

    private final BattleReconnectExpiryService expiries;
    private final int batchSize;

    public BattleReconnectExpiryScheduler(BattleReconnectExpiryService expiries, int batchSize) {
        this.expiries = Objects.requireNonNull(expiries, "expiries must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${d3.battle.reconnect-expiry.poll-interval:500ms}")
    public void expireDueReconnects() {
        expiries.expireDue(batchSize);
    }
}
