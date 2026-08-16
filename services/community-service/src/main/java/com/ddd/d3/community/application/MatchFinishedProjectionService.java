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
    private final ResultPostPublisher resultPosts;
    private final TransactionOperations transactions;

    public MatchFinishedProjectionService(
            Store store, TransactionOperations transactions, ResultPostPublisher resultPosts) {
        this.store = Objects.requireNonNull(store, "store");
        this.resultPosts = Objects.requireNonNull(resultPosts, "resultPosts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public boolean receive(MatchFinishedEvent event) {
        Objects.requireNonNull(event, "event");
        ApplyResult result = transactions.execute(status -> {
            ApplyResult applied = store.apply(event);
            if (applied == ApplyResult.PROJECTION_APPLIED && event.ranked() && !"VOIDED".equals(event.result())) {
                resultPosts.publish(event);
            }
            return applied;
        });
        return result != null && result != ApplyResult.DUPLICATE_EVENT;
    }

    public interface Store {
        ApplyResult apply(MatchFinishedEvent event);
    }

    @FunctionalInterface
    public interface ResultPostPublisher {
        void publish(MatchFinishedEvent event);
    }

    public enum ApplyResult {
        DUPLICATE_EVENT,
        EVENT_APPLIED,
        PROJECTION_APPLIED
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
