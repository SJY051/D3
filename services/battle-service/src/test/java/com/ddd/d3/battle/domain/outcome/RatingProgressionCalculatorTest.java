package com.ddd.d3.battle.domain.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import com.ddd.d3.battle.domain.outcome.RatingProgressionCalculator.PlayerStanding;
import org.junit.jupiter.api.Test;

class RatingProgressionCalculatorTest {

    private final RatingProgressionCalculator calculator = RatingProgressionCalculator.initialPolicy();

    @Test
    void d3Btl005UsesTheHighPlacementFactorAndKeepsTierUnranked() {
        var result = calculator.calculate(
                true,
                MatchOutcome.PLAYER_ONE_WIN,
                standing("player-one", 1200, 0, 0, 0),
                standing("player-two", 1200, 0, 0, 0));

        assertThat(result.playerOne().ratingAfter()).isEqualTo(1232);
        assertThat(result.playerTwo().ratingAfter()).isEqualTo(1168);
        assertThat(result.playerOne().seasonRpAfter()).isEqualTo(25);
        assertThat(result.playerTwo().seasonRpAfter()).isZero();
        assertThat(result.playerOne().placementCountAfter()).isEqualTo(1);
        assertThat(result.playerOne().rankAfter().tier()).isEqualTo("Unranked");
        assertThat(result.playerOne().rankAfter().division()).isNull();
    }

    @Test
    void d3Btl005UsesTheEstablishedFactorAfterPlacement() {
        var result = calculator.calculate(
                true,
                MatchOutcome.PLAYER_ONE_WIN,
                standing("player-one", 1200, 5, 10, 700),
                standing("player-two", 1200, 5, 10, 700));

        assertThat(result.playerOne().ratingAfter()).isEqualTo(1216);
        assertThat(result.playerTwo().ratingAfter()).isEqualTo(1184);
        assertThat(result.playerOne().seasonRpAfter()).isEqualTo(725);
        assertThat(result.playerTwo().seasonRpAfter()).isEqualTo(685);
        assertThat(result.playerOne().rankAfter().tier()).isEqualTo("Bronze");
        assertThat(result.playerOne().rankAfter().division()).isEqualTo("I");
    }

    @Test
    void d3Btl005RevealsTierAndDivisionOnTheFifthPlacementResult() {
        var result = calculator.calculate(
                true,
                MatchOutcome.DRAW,
                standing("player-one", 1200, 4, 4, 595),
                standing("player-two", 1200, 4, 4, 895));

        assertThat(result.playerOne().placementCountAfter()).isEqualTo(5);
        assertThat(result.playerOne().rankAfter().tier()).isEqualTo("Bronze");
        assertThat(result.playerOne().rankAfter().division()).isEqualTo("I");
        assertThat(result.playerTwo().rankAfter().tier()).isEqualTo("Silver");
        assertThat(result.playerTwo().rankAfter().division()).isEqualTo("III");
    }

    @Test
    void d3Btl005KeepsMasterAndGrandmasterWithoutDivisions() {
        assertThat(calculator.rankFor(5, 4_999)).isEqualTo(
                new RatingProgressionCalculator.Rank("Master", null));
        assertThat(calculator.rankFor(5, 5_000)).isEqualTo(
                new RatingProgressionCalculator.Rank("Grandmaster", null));
    }

    @Test
    void d3Btl005KeepsRankThresholdsBehindAReplaceableBoundary() {
        var custom = new RatingProgressionCalculator(
                new RatingProgressionCalculator.Policy(64, 32, 5, 25, 5, -15),
                new RatingProgressionCalculator.RankTable(300, 600, 900, 1_200, 1_500, 2_000, 100));

        assertThat(custom.rankFor(5, 1_499))
                .isEqualTo(new RatingProgressionCalculator.Rank("Diamond", "I"));
        assertThat(custom.rankFor(5, 1_500))
                .isEqualTo(new RatingProgressionCalculator.Rank("Master", null));
    }

    @Test
    void d3Btl005DoesNotMutateRatingOrRpForVoidOrUnrankedMatches() {
        var standingOne = standing("player-one", 1400, 5, 10, 1200);
        var standingTwo = standing("player-two", 1300, 5, 10, 900);

        var voided = calculator.calculate(
                true, MatchOutcome.VOIDED, standingOne, standingTwo);
        var unranked = calculator.calculate(
                false, MatchOutcome.PLAYER_ONE_WIN, standingOne, standingTwo);

        assertThat(voided.changed()).isFalse();
        assertThat(voided.playerOne().toStanding()).isEqualTo(standingOne);
        assertThat(voided.playerTwo().toStanding()).isEqualTo(standingTwo);
        assertThat(unranked.changed()).isFalse();
        assertThat(unranked.playerOne().toStanding()).isEqualTo(standingOne);
        assertThat(unranked.playerTwo().toStanding()).isEqualTo(standingTwo);
    }

    private static PlayerStanding standing(
            String playerId,
            int rating,
            int placementCount,
            int gamesPlayed,
            int seasonRp) {
        return new PlayerStanding(playerId, rating, placementCount, gamesPlayed, seasonRp);
    }
}
