package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import com.ddd.d3.battle.domain.outcome.MatchOutcome;
import com.ddd.d3.battle.domain.outcome.MatchScoreCalculator;
import com.ddd.d3.battle.domain.outcome.MatchScoreCalculator.PerformanceEvidenceVersion;
import com.ddd.d3.battle.domain.outcome.MatchScoreCalculator.PlayerPerformance;
import com.ddd.d3.battle.domain.outcome.RatingProgressionCalculator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

public final class BattleResultService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleResultService.class);
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final Map<String, Integer> TIER_ORDER = Map.of(
            "Unranked", 0,
            "Bronze", 1,
            "Silver", 2,
            "Gold", 3,
            "Platinum", 4,
            "Diamond", 5,
            "Master", 6,
            "Grandmaster", 7);

    private final BattleResultStore results;
    private final BattleMatchRepository matches;
    private final MatchScoreCalculator scores;
    private final RatingProgressionCalculator ratings;
    private final BattleSnapshotPublisher snapshots;
    private final Clock clock;
    private final TransactionOperations transactions;

    public BattleResultService(
            BattleResultStore results,
            BattleMatchRepository matches,
            MatchScoreCalculator scores,
            RatingProgressionCalculator ratings,
            BattleSnapshotPublisher snapshots,
            Clock clock,
            TransactionOperations transactions) {
        this.results = Objects.requireNonNull(results, "results");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.ratings = Objects.requireNonNull(ratings, "ratings");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public int completeBatch(int maximumMatches) {
        if (maximumMatches <= 0 || maximumMatches > 100) {
            throw new IllegalArgumentException("maximumMatches must be between 1 and 100");
        }
        List<UUID> completed = new ArrayList<>(maximumMatches);
        while (completed.size() < maximumMatches) {
            Optional<UUID> next = Objects.requireNonNull(
                    transactions.execute(status -> completeNextInsideTransaction()));
            if (next.isEmpty()) {
                break;
            }
            completed.add(next.orElseThrow());
        }
        completed.forEach(this::publishSnapshot);
        return completed.size();
    }

    private Optional<UUID> completeNextInsideTransaction() {
        Optional<BattleResultStore.PendingMatch> claimed = results.claimNextReady();
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        BattleResultStore.PendingMatch pending = claimed.orElseThrow();
        BattleMatch.Snapshot loaded = matches.findById(pending.matchId())
                .orElseThrow(BattleMatchNotFoundException::new);
        requireSamePlayers(pending, loaded);

        MatchScoreCalculator.MatchScoreResult scoreResult = null;
        MatchOutcome outcome;
        BattleMatch.Snapshot finished = loaded;
        if (loaded.state() == BattleMatch.State.JUDGING) {
            scoreResult = calculateScore(pending, loaded);
            BattleMatch match = BattleMatch.restore(loaded, Clock.fixed(clock.instant(), clock.getZone()));
            match.handle(new BattleMatch.CompleteJudging(winnerId(scoreResult.outcome(), loaded)));
            finished = match.snapshot();
            matches.save(finished, loaded.aggregateVersion());
            outcome = scoreResult.outcome();
        } else if (loaded.state() == BattleMatch.State.FINISHED) {
            outcome = persistedOutcome(loaded);
        } else {
            throw new IllegalStateException("result claim returned a non-terminal match");
        }

        boolean ratingEligible = pending.ranked()
                && finished.result().reason() != BattleMatch.ResolutionReason.LEGACY_IMPORT;
        RatingProgressionCalculator.RatingProgressionResult ratingResult = ratings.calculate(
                ratingEligible,
                outcome,
                pending.playerOne().standing(),
                pending.playerTwo().standing());
        String playerOnePeak = peakTier(
                pending.playerOne().peakTier(), ratingResult.playerOne().rankAfter().tier());
        String playerTwoPeak = peakTier(
                pending.playerTwo().peakTier(), ratingResult.playerTwo().rankAfter().tier());
        results.commit(new BattleResultStore.Completion(
                pending,
                finished,
                scoreResult,
                ratingResult,
                playerOnePeak,
                playerTwoPeak,
                clock.instant()));
        return Optional.of(pending.matchId());
    }

    private MatchScoreCalculator.MatchScoreResult calculateScore(
            BattleResultStore.PendingMatch pending, BattleMatch.Snapshot match) {
        Efficiencies efficiencies = efficiencies(pending.playerOne(), pending.playerTwo());
        PerformanceEvidenceVersion evidenceVersion = new PerformanceEvidenceVersion(
                pending.problemVersion(),
                efficiencies.runtimeVersion(),
                efficiencies.calibrationVersion());
        return scores.calculate(
                performance(pending.playerOne(), match.startedAt(), efficiencies.playerOne(), evidenceVersion),
                performance(pending.playerTwo(), match.startedAt(), efficiencies.playerTwo(), evidenceVersion));
    }

    private static PlayerPerformance performance(
            BattleResultStore.PlayerContext player,
            java.time.Instant startedAt,
            BigDecimal efficiency,
            PerformanceEvidenceVersion evidenceVersion) {
        Optional<BattleResultStore.JudgedPerformance> judged = player.performance();
        boolean accepted = judged.map(BattleResultStore.JudgedPerformance::accepted).orElse(false);
        Duration solveDuration = accepted
                ? positiveDuration(startedAt, judged.orElseThrow().acceptedAt())
                : null;
        BigDecimal progress = judged
                .map(BattleResultService::progress)
                .orElse(BigDecimal.ZERO);
        return new PlayerPerformance(
                player.playerId().toString(),
                accepted,
                solveDuration,
                progress,
                efficiency,
                player.submissionAttempts(),
                evidenceVersion);
    }

    private static Duration positiveDuration(java.time.Instant startedAt, java.time.Instant acceptedAt) {
        Duration duration = Duration.between(startedAt, acceptedAt);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalStateException("accepted submission must follow match start");
        }
        return duration;
    }

    private static BigDecimal progress(BattleResultStore.JudgedPerformance performance) {
        if (performance.totalCount() == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(performance.passedCount())
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(performance.totalCount()), MathContext.DECIMAL128);
    }

    private static Efficiencies efficiencies(
            BattleResultStore.PlayerContext playerOne,
            BattleResultStore.PlayerContext playerTwo) {
        Optional<BattleResultStore.JudgedPerformance> one = playerOne.performance();
        Optional<BattleResultStore.JudgedPerformance> two = playerTwo.performance();
        if (one.isEmpty() && two.isEmpty()) {
            return new Efficiencies(BigDecimal.ZERO, BigDecimal.ZERO, "no-runtime-evidence-v1", "no-calibration-evidence-v1");
        }
        if (one.isEmpty() || two.isEmpty()) {
            BattleResultStore.JudgedPerformance available = one.orElseGet(two::orElseThrow);
            BigDecimal availableEfficiency =
                    available.runtimeMeasurements().isEmpty() ? BigDecimal.ZERO : ONE_HUNDRED;
            return new Efficiencies(
                    one.isPresent() ? availableEfficiency : BigDecimal.ZERO,
                    two.isPresent() ? availableEfficiency : BigDecimal.ZERO,
                    available.runtimeVersion(),
                    calibrationVersion(available));
        }
        BattleResultStore.JudgedPerformance first = one.orElseThrow();
        BattleResultStore.JudgedPerformance second = two.orElseThrow();
        if (!first.runtimeVersion().equals(second.runtimeVersion())
                || !calibrationVersion(first).equals(calibrationVersion(second))) {
            throw new IllegalStateException("judge runtime evidence is not comparable");
        }
        List<MeasurementKey> firstKeys = measurementKeys(first);
        List<MeasurementKey> secondKeys = measurementKeys(second);
        if (firstKeys.isEmpty() && secondKeys.isEmpty()) {
            return new Efficiencies(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    first.runtimeVersion(),
                    calibrationVersion(first));
        }
        if (firstKeys.isEmpty() || secondKeys.isEmpty()) {
            return new Efficiencies(
                    firstKeys.isEmpty() ? BigDecimal.ZERO : ONE_HUNDRED,
                    secondKeys.isEmpty() ? BigDecimal.ZERO : ONE_HUNDRED,
                    first.runtimeVersion(),
                    calibrationVersion(first));
        }
        if (!firstKeys.equals(secondKeys)) {
            throw new IllegalStateException("judge runtime measurement tiers are not comparable");
        }
        long firstTotal = totalRuntime(first);
        long secondTotal = totalRuntime(second);
        long fastest = Math.min(firstTotal, secondTotal);
        return new Efficiencies(
                relativeEfficiency(fastest, firstTotal),
                relativeEfficiency(fastest, secondTotal),
                first.runtimeVersion(),
                calibrationVersion(first));
    }

    private static List<MeasurementKey> measurementKeys(BattleResultStore.JudgedPerformance performance) {
        return performance.runtimeMeasurements().stream()
                .map(measurement -> new MeasurementKey(
                        measurement.tier(), measurement.inputSize(), measurement.sampleCount()))
                .sorted(Comparator.comparing(MeasurementKey::tier)
                        .thenComparingLong(MeasurementKey::inputSize)
                        .thenComparingInt(MeasurementKey::sampleCount))
                .toList();
    }

    private static long totalRuntime(BattleResultStore.JudgedPerformance performance) {
        return performance.runtimeMeasurements().stream()
                .mapToLong(BattleJudgeGateway.RuntimeMeasurement::medianRuntimeMicros)
                .reduce(0L, Math::addExact);
    }

    private static BigDecimal relativeEfficiency(long fastest, long runtime) {
        if (runtime == 0L) {
            return ONE_HUNDRED;
        }
        return BigDecimal.valueOf(fastest)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(runtime), MathContext.DECIMAL128);
    }

    private static String calibrationVersion(BattleResultStore.JudgedPerformance performance) {
        return performance.evidenceVersion() + "/" + performance.adapterVersion();
    }

    private static MatchOutcome persistedOutcome(BattleMatch.Snapshot match) {
        BattleMatch.Result result = Objects.requireNonNull(match.result(), "finished match result");
        return switch (result.outcome()) {
            case VOID -> MatchOutcome.VOIDED;
            case DRAW -> MatchOutcome.DRAW;
            case WIN -> result.winnerId().equals(match.playerOneId())
                    ? MatchOutcome.PLAYER_ONE_WIN
                    : MatchOutcome.PLAYER_TWO_WIN;
        };
    }

    private static String winnerId(MatchOutcome outcome, BattleMatch.Snapshot match) {
        return switch (outcome) {
            case PLAYER_ONE_WIN -> match.playerOneId();
            case PLAYER_TWO_WIN -> match.playerTwoId();
            case DRAW -> null;
            case VOIDED -> throw new IllegalArgumentException("judged performance cannot create a void result");
        };
    }

    private static String peakTier(String currentPeak, String candidate) {
        Integer currentOrder = TIER_ORDER.get(currentPeak);
        Integer candidateOrder = TIER_ORDER.get(candidate);
        if (currentOrder == null || candidateOrder == null) {
            throw new IllegalArgumentException("unsupported peak tier");
        }
        return candidateOrder > currentOrder ? candidate : currentPeak;
    }

    private static void requireSamePlayers(
            BattleResultStore.PendingMatch pending, BattleMatch.Snapshot match) {
        if (!pending.playerOne().playerId().toString().equals(match.playerOneId())
                || !pending.playerTwo().playerId().toString().equals(match.playerTwoId())) {
            throw new IllegalStateException("claimed result players do not match aggregate seats");
        }
    }

    private void publishSnapshot(UUID matchId) {
        try {
            snapshots.publish(matchId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Committed result snapshot fan-out failed for matchId={}", matchId);
        }
    }

    private record MeasurementKey(String tier, long inputSize, int sampleCount) {}

    private record Efficiencies(
            BigDecimal playerOne,
            BigDecimal playerTwo,
            String runtimeVersion,
            String calibrationVersion) {}
}
