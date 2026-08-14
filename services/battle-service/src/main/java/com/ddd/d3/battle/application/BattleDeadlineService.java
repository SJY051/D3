package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

public final class BattleDeadlineService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleDeadlineService.class);
    private final BattleDeadlineClaimStore claims;
    private final BattleMatchRepository matches;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final BattleSnapshotPublisher snapshots;
    private final Executor snapshotExecutor;

    public BattleDeadlineService(
            BattleDeadlineClaimStore claims,
            BattleMatchRepository matches,
            Clock clock,
            TransactionOperations transactions,
            BattleSnapshotPublisher snapshots,
            Executor snapshotExecutor) {
        this.claims = Objects.requireNonNull(claims, "claims must not be null");
        this.matches = Objects.requireNonNull(matches, "matches must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.snapshotExecutor = Objects.requireNonNull(snapshotExecutor, "snapshotExecutor must not be null");
    }

    public int advanceDue(int maximumMatches) {
        if (maximumMatches <= 0) {
            throw new IllegalArgumentException("maximumMatches must be positive");
        }
        Instant cutoff = clock.instant();
        List<UUID> advancedMatches = new ArrayList<>(maximumMatches);
        while (advancedMatches.size() < maximumMatches) {
            Optional<UUID> advancedMatch = Objects.requireNonNull(
                    transactions.execute(status -> advanceNextInsideTransaction(cutoff)));
            if (advancedMatch.isEmpty()) {
                break;
            }
            advancedMatches.add(advancedMatch.orElseThrow());
        }
        advancedMatches.forEach(this::schedulePublish);
        return advancedMatches.size();
    }

    private Optional<UUID> advanceNextInsideTransaction(Instant cutoff) {
        Optional<BattleMatch.Snapshot> claimed = claims.claimNextDue(cutoff);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        BattleMatch.Snapshot loaded = claimed.orElseThrow();
        BattleMatch match = BattleMatch.restore(loaded, Clock.fixed(cutoff, clock.getZone()));
        match.handle(new BattleMatch.AdvanceTime());
        BattleMatch.Snapshot advanced = match.snapshot();
        boolean reconnectExpired = advanced.state() == BattleMatch.State.FINISHED
                && advanced.result() != null
                && advanced.result().reason() == BattleMatch.ResolutionReason.DISCONNECT_TIMEOUT;
        boolean matchExpired = advanced.state() == BattleMatch.State.JUDGING && advanced.result() == null;
        if (advanced.aggregateVersion() == loaded.aggregateVersion()
                || (!reconnectExpired && !matchExpired)) {
            throw new IllegalStateException("claimed server deadline did not advance the match");
        }
        matches.save(advanced, loaded.aggregateVersion());
        return Optional.of(UUID.fromString(advanced.matchId()));
    }

    private void publish(UUID matchId) {
        try {
            snapshots.publish(matchId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Committed deadline snapshot fan-out failed for matchId={}", matchId);
        }
    }

    private void schedulePublish(UUID matchId) {
        try {
            snapshotExecutor.execute(() -> publish(matchId));
        } catch (RuntimeException exception) {
            LOGGER.warn("Committed deadline snapshot fan-out deferred for matchId={}", matchId);
        }
    }
}
