package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

public final class BattleConnectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleConnectionService.class);
    private final BattleMatchRepository matches;
    private final BattleConnectionGenerationSource generations;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final BattleSnapshotPublisher snapshots;
    private final int maximumAttempts;

    public BattleConnectionService(
            BattleMatchRepository matches,
            BattleConnectionGenerationSource generations,
            Clock clock,
            TransactionOperations transactions,
            BattleSnapshotPublisher snapshots,
            int maximumAttempts) {
        this.matches = Objects.requireNonNull(matches, "matches must not be null");
        this.generations = Objects.requireNonNull(generations, "generations must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        if (maximumAttempts <= 0) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        this.maximumAttempts = maximumAttempts;
    }

    public ConnectionLease connected(UUID matchId, UUID playerId) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(playerId, "playerId must not be null");
        long generation = generations.nextGeneration();
        if (generation <= 0) {
            throw new IllegalStateException("connection generation source must be positive");
        }
        ConnectionExecution execution = executeWithRetry(
                matchId,
                new BattleMatch.Reconnect(playerId.toString(), generation));
        publishIfChanged(matchId, execution);
        return new ConnectionLease(generation);
    }

    public void disconnected(UUID matchId, UUID playerId, long generation) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        ConnectionExecution execution = executeWithRetry(
                matchId,
                new BattleMatch.Disconnect(playerId.toString(), generation));
        publishIfChanged(matchId, execution);
    }

    private ConnectionExecution executeWithRetry(UUID matchId, BattleMatch.Command command) {
        OptimisticMatchConflictException lastConflict = null;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            try {
                return Objects.requireNonNull(transactions.execute(
                        status -> executeInsideTransaction(matchId, command)));
            } catch (OptimisticMatchConflictException conflict) {
                lastConflict = conflict;
            }
        }
        throw Objects.requireNonNull(lastConflict);
    }

    private ConnectionExecution executeInsideTransaction(UUID matchId, BattleMatch.Command command) {
        BattleMatch.Snapshot loaded = matches.findById(matchId)
                .orElseThrow(BattleMatchNotFoundException::new);
        Instant acceptedAt = clock.instant();
        BattleMatch match = BattleMatch.restore(loaded, Clock.fixed(acceptedAt, clock.getZone()));
        match.handle(command);
        BattleMatch.Snapshot updated = match.snapshot();
        boolean changed = updated.aggregateVersion() != loaded.aggregateVersion();
        if (changed) {
            matches.save(updated, loaded.aggregateVersion());
        }
        return new ConnectionExecution(updated, changed);
    }

    private void publishIfChanged(UUID matchId, ConnectionExecution execution) {
        if (!execution.changed()) {
            return;
        }
        try {
            snapshots.publish(matchId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Committed connection snapshot fan-out failed for matchId={}", matchId);
        }
    }

    public record ConnectionLease(long generation) {

        public ConnectionLease {
            if (generation <= 0) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }

    private record ConnectionExecution(BattleMatch.Snapshot snapshot, boolean changed) {}
}
