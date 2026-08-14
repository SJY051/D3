package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

public final class BattleReconnectExpiryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleReconnectExpiryService.class);
    private final BattleReconnectExpiryClaimStore claims;
    private final BattleMatchRepository matches;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final BattleSnapshotPublisher snapshots;

    public BattleReconnectExpiryService(
            BattleReconnectExpiryClaimStore claims,
            BattleMatchRepository matches,
            Clock clock,
            TransactionOperations transactions,
            BattleSnapshotPublisher snapshots) {
        this.claims = Objects.requireNonNull(claims, "claims must not be null");
        this.matches = Objects.requireNonNull(matches, "matches must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
    }

    public int expireDue(int maximumMatches) {
        if (maximumMatches <= 0) {
            throw new IllegalArgumentException("maximumMatches must be positive");
        }
        Instant cutoff = clock.instant();
        int expired = 0;
        while (expired < maximumMatches) {
            Optional<UUID> expiredMatch = Objects.requireNonNull(
                    transactions.execute(status -> expireNextInsideTransaction(cutoff)));
            if (expiredMatch.isEmpty()) {
                break;
            }
            publish(expiredMatch.orElseThrow());
            expired++;
        }
        return expired;
    }

    private Optional<UUID> expireNextInsideTransaction(Instant cutoff) {
        Optional<BattleMatch.Snapshot> claimed = claims.claimNextExpired(cutoff);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        BattleMatch.Snapshot loaded = claimed.orElseThrow();
        BattleMatch match = BattleMatch.restore(loaded, Clock.fixed(cutoff, clock.getZone()));
        match.handle(new BattleMatch.AdvanceTime());
        BattleMatch.Snapshot expired = match.snapshot();
        if (expired.aggregateVersion() == loaded.aggregateVersion()
                || expired.state() != BattleMatch.State.FINISHED
                || expired.result() == null
                || expired.result().reason() != BattleMatch.ResolutionReason.DISCONNECT_TIMEOUT) {
            throw new IllegalStateException("claimed reconnect expiry did not finish the match");
        }
        matches.save(expired, loaded.aggregateVersion());
        return Optional.of(UUID.fromString(expired.matchId()));
    }

    private void publish(UUID matchId) {
        try {
            snapshots.publish(matchId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Committed reconnect expiry snapshot fan-out failed for matchId={}", matchId);
        }
    }
}
