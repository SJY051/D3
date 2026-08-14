package com.ddd.d3.judge.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JudgeOutboxStore {
    List<PendingJudgeEvent> loadUnpublished(int maximumCount);

    void markPublished(UUID eventId, Instant publishedAt);
}
