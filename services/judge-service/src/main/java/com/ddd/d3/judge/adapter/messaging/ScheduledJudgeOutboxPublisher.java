package com.ddd.d3.judge.adapter.messaging;

import com.ddd.d3.judge.application.JudgeOutboxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class ScheduledJudgeOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledJudgeOutboxPublisher.class);

    private final JudgeOutboxDispatcher dispatcher;

    public ScheduledJudgeOutboxPublisher(JudgeOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${d3.judge.outbox-delay:500ms}")
    public void publishPendingEvents() {
        try {
            dispatcher.dispatchBatch();
        } catch (RuntimeException exception) {
            LOGGER.warn("Judge outbox dispatch failed with {}", exception.getClass().getSimpleName());
        }
    }
}
