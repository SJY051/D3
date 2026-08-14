package com.ddd.d3.battle.domain.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ddd.d3.battle.domain.outcome.MatchScoreCalculator.PerformanceEvidenceVersion;
import com.ddd.d3.battle.domain.outcome.MatchScoreCalculator.PlayerPerformance;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MatchScoreCalculatorTest {

    private static final PerformanceEvidenceVersion EVIDENCE_VERSION =
            new PerformanceEvidenceVersion("sum-pairs-v1", "java-21-v1", "judge0-seoul-20260814");

    private final MatchScoreCalculator calculator = MatchScoreCalculator.initialWeights("score-v1");

    @Test
    void d3Btl003MakesTheOnlySolverWinEvenWhenTheOtherWeightedTotalIsHigher() {
        var result = calculator.calculate(
                accepted("player-one", 20, "60", 2),
                unsolved("player-two", "99", "100", 1));

        assertThat(result.outcome()).isEqualTo(MatchOutcome.PLAYER_ONE_WIN);
        assertThat(result.scoreFor("player-one").total()).isEqualByComparingTo("78.500");
        assertThat(result.scoreFor("player-two").total()).isEqualByComparingTo("99.500");
        assertThat(result.scoreFor("player-one").calculationVersion()).isEqualTo("score-v1");
        assertThat(result.scoreFor("player-one").evidenceVersion()).isEqualTo(EVIDENCE_VERSION);
    }

    @Test
    void d3Btl003ScoresBothSolversWithSpeedEfficiencyAndSubmissionDiscipline() {
        var result = calculator.calculate(
                accepted("player-one", 10, "80", 1),
                accepted("player-two", 20, "100", 2));

        assertThat(result.outcome()).isEqualTo(MatchOutcome.PLAYER_ONE_WIN);
        assertThat(result.scoreFor("player-one").speed()).isEqualByComparingTo("50.000");
        assertThat(result.scoreFor("player-one").dynamicEfficiency()).isEqualByComparingTo("28.000");
        assertThat(result.scoreFor("player-one").submissionDiscipline()).isEqualByComparingTo("15.000");
        assertThat(result.scoreFor("player-one").total()).isEqualByComparingTo("93.000");
        assertThat(result.scoreFor("player-two").speed()).isEqualByComparingTo("25.000");
        assertThat(result.scoreFor("player-two").dynamicEfficiency()).isEqualByComparingTo("35.000");
        assertThat(result.scoreFor("player-two").submissionDiscipline()).isEqualByComparingTo("7.500");
        assertThat(result.scoreFor("player-two").total()).isEqualByComparingTo("67.500");
    }

    @Test
    void d3Btl003UsesHiddenProgressWhenNeitherPlayerSolves() {
        var result = calculator.calculate(
                unsolved("player-one", "80", "50", 1),
                unsolved("player-two", "70", "70", 2));

        assertThat(result.outcome()).isEqualTo(MatchOutcome.PLAYER_ONE_WIN);
        assertThat(result.scoreFor("player-one").total()).isEqualByComparingTo("72.500");
        assertThat(result.scoreFor("player-two").total()).isEqualByComparingTo("67.000");
    }

    @Test
    void d3Btl003CommitsAnExactTieAsDraw() {
        var result = calculator.calculate(
                accepted("player-one", 10, "80", 1),
                accepted("player-two", 10, "80", 1));

        assertThat(result.outcome()).isEqualTo(MatchOutcome.DRAW);
        assertThat(result.scoreFor("player-one").total())
                .isEqualByComparingTo(result.scoreFor("player-two").total());
    }

    @Test
    void d3Btl003VoidsWithoutInventingPerformanceScores() {
        var result = calculator.voided("player-one", "player-two");

        assertThat(result.outcome()).isEqualTo(MatchOutcome.VOIDED);
        assertThat(result.scores()).isEmpty();
    }

    @Test
    void d3Btl003RejectsIncomparableRuntimeEvidence() {
        var differentHost = new PerformanceEvidenceVersion(
                "sum-pairs-v1", "java-21-v1", "different-calibration");

        assertThatThrownBy(() -> calculator.calculate(
                        accepted("player-one", 10, "80", 1),
                        new PlayerPerformance(
                                "player-two",
                                true,
                                Duration.ofSeconds(10),
                                new BigDecimal("100"),
                                new BigDecimal("80"),
                                1,
                                differentHost)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence version");
    }

    @Test
    void d3Btl003ProducesTheSameVersionedResultForTheSameEvidence() {
        var playerOne = accepted("player-one", 10, "80", 1);
        var playerTwo = unsolved("player-two", "90", "70", 0);

        var first = calculator.calculate(playerOne, playerTwo);
        var second = calculator.calculate(playerOne, playerTwo);

        assertThat(second).isEqualTo(first);
        assertThat(first.scoreFor("player-two").submissionDiscipline()).isEqualByComparingTo("0.000");
    }

    @Test
    void d3Btl003RejectsWeightsThatDoNotDescribeTheWholeScore() {
        assertThatThrownBy(() -> new MatchScoreCalculator.ScoringWeights(
                        new BigDecimal("0.50"),
                        new BigDecimal("0.35"),
                        new BigDecimal("0.16")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total 1");
    }

    private static PlayerPerformance accepted(
            String playerId, long solveSeconds, String efficiency, int attempts) {
        return new PlayerPerformance(
                playerId,
                true,
                Duration.ofSeconds(solveSeconds),
                new BigDecimal("100"),
                new BigDecimal(efficiency),
                attempts,
                EVIDENCE_VERSION);
    }

    private static PlayerPerformance unsolved(
            String playerId, String progress, String efficiency, int attempts) {
        return new PlayerPerformance(
                playerId,
                false,
                null,
                new BigDecimal(progress),
                new BigDecimal(efficiency),
                attempts,
                EVIDENCE_VERSION);
    }
}
