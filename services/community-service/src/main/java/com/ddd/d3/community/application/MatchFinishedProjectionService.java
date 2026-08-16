package com.ddd.d3.community.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

public final class MatchFinishedProjectionService {

    private static final Set<String> RESULTS = Set.of(
            "PLAYER_ONE_WIN", "PLAYER_TWO_WIN", "DRAW", "VOIDED");

    private final Store store;
    private final TransactionOperations transactions;

    public MatchFinishedProjectionService(Store store, TransactionOperations transactions) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public boolean receive(MatchFinishedEvent event) {
        Objects.requireNonNull(event, "event");
        return Boolean.TRUE.equals(transactions.execute(status -> store.apply(event)));
    }

    public interface Store {
        boolean apply(MatchFinishedEvent event);
    }

    public record MatchFinishedEvent(
            UUID eventId,
            UUID aggregateId,
            long aggregateVersion,
            UUID matchId,
            String result,
            boolean ranked,
            List<UUID> playerIds,
            Instant receivedAt) {

        public MatchFinishedEvent {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(matchId, "matchId");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(playerIds, "playerIds");
            Objects.requireNonNull(receivedAt, "receivedAt");
            playerIds = List.copyOf(playerIds);
            if (aggregateVersion < 0) {
                throw new IllegalArgumentException("aggregateVersion must be non-negative");
            }
            if (!aggregateId.equals(matchId)) {
                throw new IllegalArgumentException("aggregateId must match matchId");
            }
            if (!RESULTS.contains(result)) {
                throw new IllegalArgumentException("unsupported match result");
            }
            if (playerIds.size() != 2
                    || playerIds.get(0) == null
                    || playerIds.get(1) == null
                    || playerIds.get(0).equals(playerIds.get(1))) {
                throw new IllegalArgumentException("playerIds must contain two distinct seat-ordered users");
            }
        }
    }
}
