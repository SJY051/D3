package com.ddd.d3.battle.infrastructure.messaging;

import com.ddd.d3.battle.application.BattleOutboxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class ScheduledBattleOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledBattleOutboxPublisher.class);

    private final BattleOutboxDispatcher dispatcher;

    public ScheduledBattleOutboxPublisher(BattleOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${d3.battle.outbox-delay:500ms}")
    public void publishPendingEvents() {
        try {
            dispatcher.dispatchBatch();
        } catch (RuntimeException exception) {
            LOGGER.warn("Battle outbox dispatch failed with {}", exception.getClass().getSimpleName());
        }
    }
}
