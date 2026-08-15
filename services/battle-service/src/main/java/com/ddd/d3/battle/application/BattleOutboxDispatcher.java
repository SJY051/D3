package com.ddd.d3.battle.application;

import java.time.Clock;
import java.util.Objects;

public final class BattleOutboxDispatcher {

    private static final int BATCH_SIZE = 20;

    private final BattleOutboxStore store;
    private final BattleEventPublisher publisher;
    private final Clock clock;

    public BattleOutboxDispatcher(BattleOutboxStore store, BattleEventPublisher publisher, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int dispatchBatch() {
        int published = 0;
        for (PendingBattleEvent event : store.loadUnpublished(BATCH_SIZE)) {
            publisher.publish(event);
            store.markPublished(event.eventId(), clock.instant());
            published++;
        }
        return published;
    }
}
