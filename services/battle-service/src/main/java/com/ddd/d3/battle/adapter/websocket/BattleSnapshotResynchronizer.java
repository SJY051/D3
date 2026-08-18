package com.ddd.d3.battle.adapter.websocket;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class BattleSnapshotResynchronizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleSnapshotResynchronizer.class);
    private final BattleWebSocketSessionRegistry sessions;
    private final Executor executor;
    // FIFO so a rejected match keeps its place instead of being re-granted behind fresh arrivals;
    // guarded by its own monitor.
    private final Set<UUID> pending = new LinkedHashSet<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    BattleSnapshotResynchronizer(
            BattleWebSocketSessionRegistry sessions,
            @Qualifier("battleSnapshotFanoutExecutor") Executor executor) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Scheduled(fixedDelayString = "${d3.battle.snapshot-resync-interval:1s}")
    public void resynchronize() {
        Set<UUID> activeMatchIds = sessions.activeMatchIds();
        synchronized (pending) {
            pending.retainAll(activeMatchIds);
        }
        activeMatchIds.forEach(this::schedule);
    }

    private void schedule(UUID matchId) {
        synchronized (pending) {
            pending.add(matchId);
        }
        pump();
    }

    // Submit the oldest waiting match that is not already delivering. Called on every notification
    // and after every completion, so a freed executor slot drains the longest-waiting match first
    // rather than letting an accepted match resubmit itself ahead of the rejected backlog.
    private void pump() {
        UUID matchId;
        synchronized (pending) {
            matchId = null;
            for (UUID candidate : pending) {
                if (!inFlight.contains(candidate)) {
                    matchId = candidate;
                    break;
                }
            }
            if (matchId == null) {
                return;
            }
            inFlight.add(matchId);
        }
        submit(matchId);
    }

    private void submit(UUID matchId) {
        try {
            executor.execute(() -> {
                try {
                    synchronized (pending) {
                        pending.remove(matchId);
                    }
                    sessions.publish(matchId);
                } finally {
                    inFlight.remove(matchId);
                    pump();
                }
            });
        } catch (RuntimeException exception) {
            // Leave the match in pending; the next completion's pump or the periodic scan retries it.
            inFlight.remove(matchId);
            LOGGER.warn("Battle snapshot resynchronization retained after executor rejection; matchId={}", matchId);
        }
    }
}
