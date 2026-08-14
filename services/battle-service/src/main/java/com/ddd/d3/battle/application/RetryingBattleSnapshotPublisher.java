package com.ddd.d3.battle.application;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RetryingBattleSnapshotPublisher implements BattleSnapshotPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetryingBattleSnapshotPublisher.class);
    private final BattleSnapshotPublisher delegate;
    private final ScheduledExecutorService scheduler;
    private final long retryDelayNanos;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();

    public RetryingBattleSnapshotPublisher(
            BattleSnapshotPublisher delegate,
            ScheduledExecutorService scheduler,
            Duration retryDelay) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        Objects.requireNonNull(retryDelay, "retryDelay must not be null");
        if (retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must be positive");
        }
        this.retryDelayNanos = retryDelay.toNanos();
    }

    @Override
    public void publish(UUID matchId) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        try {
            delegate.publish(matchId);
            pending.remove(matchId);
        } catch (RuntimeException exception) {
            pending.add(matchId);
            scheduleIfNeeded(matchId);
            LOGGER.warn("Committed Battle snapshot retained for fan-out retry; matchId={}", matchId);
        }
    }

    int pendingCount() {
        return pending.size();
    }

    private void retry(UUID matchId) {
        if (!pending.contains(matchId)) {
            scheduled.remove(matchId);
            return;
        }
        try {
            delegate.publish(matchId);
            pending.remove(matchId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Committed Battle snapshot fan-out retry remains pending; matchId={}", matchId);
        } finally {
            scheduled.remove(matchId);
            if (pending.contains(matchId)) {
                scheduleIfNeeded(matchId);
            }
        }
    }

    private void scheduleIfNeeded(UUID matchId) {
        if (!scheduled.add(matchId)) {
            return;
        }
        try {
            scheduler.schedule(() -> retry(matchId), retryDelayNanos, NANOSECONDS);
        } catch (RuntimeException exception) {
            scheduled.remove(matchId);
            LOGGER.warn("Committed Battle snapshot retry could not be scheduled; matchId={}", matchId);
        }
    }
}
