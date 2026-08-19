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
            List<PlayerRecordEvidence> players,
            Instant receivedAt) {

        public MatchFinishedEvent(
                UUID eventId, UUID aggregateId, long aggregateVersion, UUID matchId, String result,
                boolean ranked, List<UUID> playerIds, Instant receivedAt) {
            this(eventId, aggregateId, aggregateVersion, matchId, result, ranked, playerIds, List.of(), receivedAt);
        }

        public MatchFinishedEvent {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(matchId, "matchId");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(playerIds, "playerIds");
            Objects.requireNonNull(players, "players");
            Objects.requireNonNull(receivedAt, "receivedAt");
            playerIds = List.copyOf(playerIds);
            players = List.copyOf(players);
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
            if (!players.isEmpty() && (players.size() != 2
                    || !players.get(0).userId().equals(playerIds.get(0))
                    || !players.get(1).userId().equals(playerIds.get(1)))) {
                throw new IllegalArgumentException("player evidence must be empty or seat-ordered for both players");
            }
        }
    }

    /** Public, versioned evidence copied from Battle; never carries source, hidden tests, or diagnostics. */
    public record PlayerRecordEvidence(
            UUID userId,
            String language,
            int attempts,
            String peakTier,
            int leaderboardPosition,
            ScoreEvidence score,
            ExecutionEvidence execution,
            AttackEvidence attacks) {

        public PlayerRecordEvidence {
            Objects.requireNonNull(userId, "userId");
            if (language == null || language.isBlank() || language.length() > 32) throw new IllegalArgumentException("language must be bounded");
            if (attempts < 0 || leaderboardPosition < 1) throw new IllegalArgumentException("attempts and leaderboard position are invalid");
            if (peakTier == null || peakTier.isBlank() || peakTier.length() > 32) throw new IllegalArgumentException("peakTier must be bounded");
            Objects.requireNonNull(attacks, "attacks");
        }
    }

    public record ScoreEvidence(java.math.BigDecimal total, java.math.BigDecimal speed,
            java.math.BigDecimal dynamicEfficiency, java.math.BigDecimal submissionDiscipline,
            String calculationVersion, String problemVersion, String runtimeVersion, String calibrationVersion) {}

    public record ExecutionEvidence(String verdict, int passedCount, int totalCount,
            List<RuntimeMeasurement> runtimeMeasurements, String adapterVersion, String runtimeVersion,
            String evidenceVersion) {
        public ExecutionEvidence { runtimeMeasurements = List.copyOf(runtimeMeasurements); }
    }

    public record RuntimeMeasurement(String tier, long inputSize, int sampleCount, long medianRuntimeMicros) {}

    public record AttackEvidence(int launched, int targeted, int blocked, int reflected) {
        public AttackEvidence {
            if (launched < 0 || targeted < 0 || blocked < 0 || reflected < 0) throw new IllegalArgumentException("attack counts must not be negative");
        }
    }
}
