package com.ddd.d3.battle.adapter.websocket;

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
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    BattleSnapshotResynchronizer(
            BattleWebSocketSessionRegistry sessions,
            @Qualifier("battleSnapshotFanoutExecutor") Executor executor) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Scheduled(fixedDelayString = "${d3.battle.snapshot-resync-interval:1s}")
    public void resynchronize() {
        sessions.activeMatchIds().forEach(this::schedule);
    }

    private void schedule(UUID matchId) {
        if (!inFlight.add(matchId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    sessions.publish(matchId);
                } finally {
                    inFlight.remove(matchId);
                }
            });
        } catch (RuntimeException exception) {
            inFlight.remove(matchId);
            LOGGER.warn("Battle snapshot resynchronization deferred; matchId={}", matchId);
        }
    }
}
