package com.ddd.d3.battle.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BattleOutboxStore {
    List<PendingBattleEvent> loadUnpublished(int maximumCount);

    void markPublished(UUID eventId, Instant publishedAt);
}
