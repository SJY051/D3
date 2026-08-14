package com.ddd.d3.battle.domain.outcome;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MatchScoreCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int SCORE_SCALE = 6;
    private static final MathContext COMPARISON_CONTEXT = MathContext.DECIMAL128;

    public record ScoringWeights(
            BigDecimal speed,
            BigDecimal dynamicEfficiency,
            BigDecimal submissionDiscipline) {

        public ScoringWeights {
            speed = requireNonNegative(speed, "speed");
            dynamicEfficiency = requireNonNegative(dynamicEfficiency, "dynamicEfficiency");
            submissionDiscipline = requireNonNegative(submissionDiscipline, "submissionDiscipline");
            if (speed.add(dynamicEfficiency).add(submissionDiscipline).compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalArgumentException("scoring weights must total 1");
            }
        }

        private static BigDecimal requireNonNegative(BigDecimal value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.signum() < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }
    }

    public record PerformanceEvidenceVersion(
            String problemVersion,
            String runtimeVersion,
            String calibrationVersion) {

        public PerformanceEvidenceVersion {
            problemVersion = requireText(problemVersion, "problemVersion");
            runtimeVersion = requireText(runtimeVersion, "runtimeVersion");
            calibrationVersion = requireText(calibrationVersion, "calibrationVersion");
        }
    }

    public record PlayerPerformance(
            String playerId,
            boolean accepted,
            Duration solveDuration,
            BigDecimal hiddenTestProgress,
            BigDecimal dynamicEfficiency,
            int submissionAttempts,
            PerformanceEvidenceVersion evidenceVersion) {

        public PlayerPerformance {
            playerId = requireText(playerId, "playerId");
            hiddenTestProgress = requirePercentage(hiddenTestProgress, "hiddenTestProgress");
            dynamicEfficiency = requirePercentage(dynamicEfficiency, "dynamicEfficiency");
            Objects.requireNonNull(evidenceVersion, "evidenceVersion must not be null");
            if (submissionAttempts < 0) {
                throw new IllegalArgumentException("submissionAttempts must not be negative");
            }
            if (accepted) {
                if (solveDuration == null || solveDuration.isZero() || solveDuration.isNegative()) {
                    throw new IllegalArgumentException("accepted performance requires a positive solveDuration");
                }
                if (submissionAttempts == 0) {
                    throw new IllegalArgumentException("accepted performance requires a submission attempt");
                }
            } else if (solveDuration != null) {
                throw new IllegalArgumentException("unsolved performance must not include solveDuration");
            }
        }
    }

    public record PlayerScore(
            BigDecimal speed,
            BigDecimal dynamicEfficiency,
            BigDecimal submissionDiscipline,
            BigDecimal total,
            String calculationVersion,
            PerformanceEvidenceVersion evidenceVersion) {

        public PlayerScore {
            speed = ScoringWeights.requireNonNegative(speed, "speed");
            dynamicEfficiency = ScoringWeights.requireNonNegative(
                    dynamicEfficiency, "dynamicEfficiency");
            submissionDiscipline = ScoringWeights.requireNonNegative(
                    submissionDiscipline, "submissionDiscipline");
            total = ScoringWeights.requireNonNegative(total, "total");
            calculationVersion = requireText(calculationVersion, "calculationVersion");
            Objects.requireNonNull(evidenceVersion, "evidenceVersion must not be null");
            if (speed.add(dynamicEfficiency).add(submissionDiscipline).compareTo(total) != 0) {
                throw new IllegalArgumentException("named score components must total the committed score");
            }
        }
    }

    public record MatchScoreResult(
            MatchOutcome outcome,
            String playerOneId,
            String playerTwoId,
            Map<String, PlayerScore> scores) {

        public MatchScoreResult {
            Objects.requireNonNull(outcome, "outcome must not be null");
            playerOneId = requireText(playerOneId, "playerOneId");
            playerTwoId = requireText(playerTwoId, "playerTwoId");
            if (playerOneId.equals(playerTwoId)) {
                throw new IllegalArgumentException("players must be distinct");
            }
            Objects.requireNonNull(scores, "scores must not be null");
            scores = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
            if (outcome == MatchOutcome.VOIDED && !scores.isEmpty()) {
                throw new IllegalArgumentException("voided result must not invent scores");
            }
            if (outcome != MatchOutcome.VOIDED
                    && !scores.keySet().equals(Set.of(playerOneId, playerTwoId))) {
                throw new IllegalArgumentException("scored result must contain both players exactly once");
            }
        }

        public PlayerScore scoreFor(String playerId) {
            PlayerScore score = scores.get(playerId);
            if (score == null) {
                throw new IllegalArgumentException("score is not available for player " + playerId);
            }
            return score;
        }
    }

    private record CalculatedScore(PlayerScore committed, BigDecimal comparisonTotal) {}

    private final String calculationVersion;
    private final ScoringWeights weights;

    public MatchScoreCalculator(String calculationVersion, ScoringWeights weights) {
        this.calculationVersion = requireText(calculationVersion, "calculationVersion");
        this.weights = Objects.requireNonNull(weights, "weights must not be null");
    }

    public static MatchScoreCalculator initialWeights(String calculationVersion) {
        return new MatchScoreCalculator(
                calculationVersion,
                new ScoringWeights(
                        new BigDecimal("0.50"),
                        new BigDecimal("0.35"),
                        new BigDecimal("0.15")));
    }

    public MatchScoreResult calculate(PlayerPerformance playerOne, PlayerPerformance playerTwo) {
        Objects.requireNonNull(playerOne, "playerOne must not be null");
        Objects.requireNonNull(playerTwo, "playerTwo must not be null");
        if (playerOne.playerId().equals(playerTwo.playerId())) {
            throw new IllegalArgumentException("players must be distinct");
        }
        if (!playerOne.evidenceVersion().equals(playerTwo.evidenceVersion())) {
            throw new IllegalArgumentException("players must use the same performance evidence version");
        }

        BigDecimal[] primaryScores = primaryScores(playerOne, playerTwo);
        CalculatedScore playerOneScore = score(playerOne, primaryScores[0]);
        CalculatedScore playerTwoScore = score(playerTwo, primaryScores[1]);
        MatchOutcome outcome = decideOutcome(
                playerOne,
                playerTwo,
                playerOneScore.comparisonTotal(),
                playerTwoScore.comparisonTotal());
        Map<String, PlayerScore> scores = new LinkedHashMap<>();
        scores.put(playerOne.playerId(), playerOneScore.committed());
        scores.put(playerTwo.playerId(), playerTwoScore.committed());
        return new MatchScoreResult(outcome, playerOne.playerId(), playerTwo.playerId(), scores);
    }

    public MatchScoreResult voided(String playerOneId, String playerTwoId) {
        return new MatchScoreResult(MatchOutcome.VOIDED, playerOneId, playerTwoId, Map.of());
    }

    private BigDecimal[] primaryScores(PlayerPerformance playerOne, PlayerPerformance playerTwo) {
        if (playerOne.accepted() && playerTwo.accepted()) {
            BigDecimal playerOneDuration = BigDecimal.valueOf(playerOne.solveDuration().toNanos());
            BigDecimal playerTwoDuration = BigDecimal.valueOf(playerTwo.solveDuration().toNanos());
            BigDecimal fastest = playerOneDuration.min(playerTwoDuration);
            return new BigDecimal[] {
                ratioScore(fastest, playerOneDuration),
                ratioScore(fastest, playerTwoDuration)
            };
        }
        if (playerOne.accepted()) {
            return new BigDecimal[] {ONE_HUNDRED, playerTwo.hiddenTestProgress()};
        }
        if (playerTwo.accepted()) {
            return new BigDecimal[] {playerOne.hiddenTestProgress(), ONE_HUNDRED};
        }
        return new BigDecimal[] {playerOne.hiddenTestProgress(), playerTwo.hiddenTestProgress()};
    }

    private static BigDecimal ratioScore(BigDecimal fastest, BigDecimal duration) {
        return fastest.divide(duration, COMPARISON_CONTEXT).multiply(ONE_HUNDRED, COMPARISON_CONTEXT);
    }

    private CalculatedScore score(PlayerPerformance performance, BigDecimal primaryScore) {
        BigDecimal comparisonSpeed = weightedForComparison(primaryScore, weights.speed());
        BigDecimal comparisonEfficiency = weightedForComparison(
                performance.dynamicEfficiency(), weights.dynamicEfficiency());
        BigDecimal comparisonDiscipline = weightedForComparison(
                disciplineScore(performance.submissionAttempts()), weights.submissionDiscipline());
        BigDecimal comparisonTotal = comparisonSpeed
                .add(comparisonEfficiency, COMPARISON_CONTEXT)
                .add(comparisonDiscipline, COMPARISON_CONTEXT);
        BigDecimal speed = committed(comparisonSpeed);
        BigDecimal efficiency = committed(comparisonEfficiency);
        BigDecimal discipline = committed(comparisonDiscipline);
        BigDecimal total = speed.add(efficiency).add(discipline).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        return new CalculatedScore(
                new PlayerScore(
                        speed,
                        efficiency,
                        discipline,
                        total,
                        calculationVersion,
                        performance.evidenceVersion()),
                comparisonTotal);
    }

    private static BigDecimal disciplineScore(int attempts) {
        if (attempts == 0) {
            return BigDecimal.ZERO;
        }
        return ONE_HUNDRED.divide(BigDecimal.valueOf(attempts), COMPARISON_CONTEXT);
    }

    private static BigDecimal weightedForComparison(BigDecimal rawScore, BigDecimal weight) {
        return rawScore.multiply(weight, COMPARISON_CONTEXT);
    }

    private static BigDecimal committed(BigDecimal score) {
        return score.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private static MatchOutcome decideOutcome(
            PlayerPerformance playerOne,
            PlayerPerformance playerTwo,
            BigDecimal playerOneTotal,
            BigDecimal playerTwoTotal) {
        if (playerOne.accepted() != playerTwo.accepted()) {
            return playerOne.accepted() ? MatchOutcome.PLAYER_ONE_WIN : MatchOutcome.PLAYER_TWO_WIN;
        }
        int comparison = playerOneTotal.compareTo(playerTwoTotal);
        if (comparison > 0) {
            return MatchOutcome.PLAYER_ONE_WIN;
        }
        if (comparison < 0) {
            return MatchOutcome.PLAYER_TWO_WIN;
        }
        return MatchOutcome.DRAW;
    }

    private static BigDecimal requirePercentage(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() < 0 || value.compareTo(ONE_HUNDRED) > 0) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
