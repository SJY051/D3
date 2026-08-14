package com.ddd.d3.battle.adapter.websocket;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.ddd.d3.battle.application.BattleConnectionService;
import com.ddd.d3.battle.application.BattleMatchNotFoundException;
import com.ddd.d3.battle.application.OptimisticMatchConflictException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
final class BattleDisconnectRetryQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleDisconnectRetryQueue.class);
    private final BattleConnectionService connections;
    private final ScheduledExecutorService scheduler;
    private final long retryDelayNanos;
    private final Map<ConnectionKey, Long> pending = new ConcurrentHashMap<>();
    private final Set<ConnectionKey> scheduled = ConcurrentHashMap.newKeySet();

    BattleDisconnectRetryQueue(
            BattleConnectionService connections,
            @Qualifier("battleDisconnectRetryScheduler") ScheduledExecutorService scheduler,
            @Value("${d3.battle.connection.retry-delay:50ms}") Duration retryDelay) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        Objects.requireNonNull(retryDelay, "retryDelay must not be null");
        if (retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must be positive");
        }
        this.retryDelayNanos = retryDelay.toNanos();
    }

    void disconnect(UUID matchId, UUID playerId, long generation) {
        ConnectionKey key = new ConnectionKey(matchId, playerId);
        try {
            connections.disconnected(matchId, playerId, generation);
            discardCommitted(key, generation);
        } catch (RuntimeException exception) {
            if (!isRetryable(exception)) {
                throw exception;
            }
            pending.merge(key, generation, Math::max);
            scheduleIfNeeded(key);
            LOGGER.warn("Battle transport disconnect retained for retry; matchId={}", matchId);
        }
    }

    int pendingCount() {
        return pending.size();
    }

    private void retry(ConnectionKey key) {
        Long generation = pending.get(key);
        if (generation == null) {
            scheduled.remove(key);
            return;
        }
        try {
            connections.disconnected(key.matchId, key.playerId, generation);
            discardCommitted(key, generation);
        } catch (BattleMatchNotFoundException | IllegalArgumentException | IllegalStateException exception) {
            pending.remove(key, generation);
            LOGGER.warn("Battle transport disconnect retry rejected by authoritative state; matchId={}", key.matchId);
        } catch (RuntimeException exception) {
            if (!isRetryable(exception)) {
                pending.remove(key, generation);
                LOGGER.warn("Battle transport disconnect retry failed permanently; matchId={}", key.matchId);
            }
        } finally {
            scheduled.remove(key);
            if (pending.containsKey(key)) {
                scheduleIfNeeded(key);
            }
        }
    }

    private void discardCommitted(ConnectionKey key, long generation) {
        pending.computeIfPresent(key, (ignored, retained) -> retained <= generation ? null : retained);
    }

    private void scheduleIfNeeded(ConnectionKey key) {
        if (!scheduled.add(key)) {
            return;
        }
        try {
            scheduler.schedule(() -> retry(key), retryDelayNanos, NANOSECONDS);
        } catch (RuntimeException exception) {
            scheduled.remove(key);
            LOGGER.warn("Battle transport disconnect retry could not be scheduled; matchId={}", key.matchId);
        }
    }

    private static boolean isRetryable(RuntimeException exception) {
        return exception instanceof OptimisticMatchConflictException
                || exception instanceof DataAccessException;
    }

    private record ConnectionKey(UUID matchId, UUID playerId) {

        private ConnectionKey {
            Objects.requireNonNull(matchId, "matchId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
        }
    }
}
