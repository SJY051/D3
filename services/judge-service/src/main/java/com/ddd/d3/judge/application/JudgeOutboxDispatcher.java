package com.ddd.d3.judge.application;

import java.time.Clock;
import java.util.Objects;

public final class JudgeOutboxDispatcher {

    private static final int BATCH_SIZE = 20;

    private final JudgeOutboxStore store;
    private final JudgeEventPublisher publisher;
    private final Clock clock;

    public JudgeOutboxDispatcher(JudgeOutboxStore store, JudgeEventPublisher publisher, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int dispatchBatch() {
        int published = 0;
        for (PendingJudgeEvent event : store.loadUnpublished(BATCH_SIZE)) {
            publisher.publish(event);
            store.markPublished(event.eventId(), clock.instant());
            published++;
        }
        return published;
    }
}
