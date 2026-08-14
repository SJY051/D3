package com.ddd.d3.battle.domain.outcome;

import java.util.Objects;
import java.util.Set;

public final class RatingProgressionCalculator {

    public record Policy(
            int placementAdjustmentFactor,
            int establishedAdjustmentFactor,
            int placementMatches,
            int winRp,
            int drawRp,
            int lossRp) {

        public Policy {
            if (placementAdjustmentFactor <= 0 || establishedAdjustmentFactor <= 0) {
                throw new IllegalArgumentException("rating adjustment factors must be positive");
            }
            if (placementMatches <= 0) {
                throw new IllegalArgumentException("placementMatches must be positive");
            }
            if (winRp < 0 || drawRp < 0 || lossRp > 0) {
                throw new IllegalArgumentException("seasonal RP deltas have invalid signs");
            }
        }
    }

    public record Rank(String tier, String division) {
        public Rank {
            if (tier == null || tier.isBlank()) {
                throw new IllegalArgumentException("tier must not be blank");
            }
            Set<String> dividedTiers = Set.of("Bronze", "Silver", "Gold", "Platinum", "Diamond");
            Set<String> undividedTiers = Set.of("Unranked", "Master", "Grandmaster");
            if (dividedTiers.contains(tier)
                    && (division == null || !Set.of("III", "II", "I").contains(division))) {
                throw new IllegalArgumentException("divided tier requires division III, II, or I");
            }
            if (undividedTiers.contains(tier) && division != null) {
                throw new IllegalArgumentException("undivided tier must not include a division");
            }
            if (!dividedTiers.contains(tier) && !undividedTiers.contains(tier)) {
                throw new IllegalArgumentException("unsupported tier");
            }
        }
    }

    public record RankTable(
            int silverFloor,
            int goldFloor,
            int platinumFloor,
            int diamondFloor,
            int masterFloor,
            int grandmasterFloor,
            int divisionWidth) {

        public RankTable {
            long tierWidth = (long) divisionWidth * 3;
            if (divisionWidth <= 0
                    || silverFloor != tierWidth
                    || goldFloor != silverFloor + tierWidth
                    || platinumFloor != goldFloor + tierWidth
                    || diamondFloor != platinumFloor + tierWidth
                    || masterFloor != diamondFloor + tierWidth
                    || grandmasterFloor <= masterFloor) {
                throw new IllegalArgumentException("rank floors must define ordered three-division tiers");
            }
        }
    }

    public record PlayerStanding(
            String playerId,
            int publicRating,
            int placementCount,
            int gamesPlayed,
            int seasonRp) {

        public PlayerStanding {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId must not be blank");
            }
            if (publicRating < 0 || placementCount < 0 || gamesPlayed < 0 || seasonRp < 0) {
                throw new IllegalArgumentException("standing values must not be negative");
            }
            if (placementCount > gamesPlayed) {
                throw new IllegalArgumentException("placementCount must not exceed gamesPlayed");
            }
        }
    }

    public record PlayerUpdate(
            String playerId,
            int ratingBefore,
            int ratingAfter,
            int placementCountBefore,
            int placementCountAfter,
            int gamesPlayedBefore,
            int gamesPlayedAfter,
            int seasonRpBefore,
            int seasonRpAfter,
            Rank rankAfter) {

        public PlayerStanding toStanding() {
            return new PlayerStanding(
                    playerId,
                    ratingAfter,
                    placementCountAfter,
                    gamesPlayedAfter,
                    seasonRpAfter);
        }
    }

    public record RatingProgressionResult(
            boolean changed,
            PlayerUpdate playerOne,
            PlayerUpdate playerTwo) {}

    private final Policy policy;
    private final RankTable rankTable;

    public RatingProgressionCalculator(Policy policy, RankTable rankTable) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.rankTable = Objects.requireNonNull(rankTable, "rankTable must not be null");
    }

    public static RatingProgressionCalculator initialPolicy() {
        return new RatingProgressionCalculator(
                new Policy(64, 32, 5, 25, 5, -15),
                new RankTable(900, 1_800, 2_700, 3_600, 4_500, 5_000, 300));
    }

    public RatingProgressionResult calculate(
            boolean ranked,
            MatchOutcome outcome,
            PlayerStanding playerOne,
            PlayerStanding playerTwo) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(playerOne, "playerOne must not be null");
        Objects.requireNonNull(playerTwo, "playerTwo must not be null");
        if (playerOne.playerId().equals(playerTwo.playerId())) {
            throw new IllegalArgumentException("players must be distinct");
        }
        if (!ranked || outcome == MatchOutcome.VOIDED) {
            return new RatingProgressionResult(
                    false,
                    unchanged(playerOne),
                    unchanged(playerTwo));
        }

        double playerOneActual = actualScore(outcome, true);
        double playerTwoActual = actualScore(outcome, false);
        double playerOneExpected = expectedScore(playerOne.publicRating(), playerTwo.publicRating());
        double playerTwoExpected = 1.0d - playerOneExpected;
        return new RatingProgressionResult(
                true,
                update(playerOne, playerOneActual, playerOneExpected),
                update(playerTwo, playerTwoActual, playerTwoExpected));
    }

    public Rank rankFor(int placementCount, int seasonRp) {
        if (placementCount < 0 || seasonRp < 0) {
            throw new IllegalArgumentException("rank inputs must not be negative");
        }
        if (placementCount < policy.placementMatches()) {
            return new Rank("Unranked", null);
        }
        if (seasonRp < rankTable.silverFloor()) {
            return dividedRank("Bronze", 0, seasonRp);
        }
        if (seasonRp < rankTable.goldFloor()) {
            return dividedRank("Silver", rankTable.silverFloor(), seasonRp);
        }
        if (seasonRp < rankTable.platinumFloor()) {
            return dividedRank("Gold", rankTable.goldFloor(), seasonRp);
        }
        if (seasonRp < rankTable.diamondFloor()) {
            return dividedRank("Platinum", rankTable.platinumFloor(), seasonRp);
        }
        if (seasonRp < rankTable.masterFloor()) {
            return dividedRank("Diamond", rankTable.diamondFloor(), seasonRp);
        }
        if (seasonRp < rankTable.grandmasterFloor()) {
            return new Rank("Master", null);
        }
        return new Rank("Grandmaster", null);
    }

    private PlayerUpdate update(PlayerStanding standing, double actual, double expected) {
        int adjustmentFactor = standing.placementCount() < policy.placementMatches()
                ? policy.placementAdjustmentFactor()
                : policy.establishedAdjustmentFactor();
        int ratingDelta = (int) Math.round(adjustmentFactor * (actual - expected));
        int ratingAfter = Math.max(0, Math.addExact(standing.publicRating(), ratingDelta));
        int placementAfter = standing.placementCount() < policy.placementMatches()
                ? standing.placementCount() + 1
                : standing.placementCount();
        int gamesAfter = Math.addExact(standing.gamesPlayed(), 1);
        int rpAfter = Math.max(0, Math.addExact(standing.seasonRp(), rpDelta(actual)));
        return new PlayerUpdate(
                standing.playerId(),
                standing.publicRating(),
                ratingAfter,
                standing.placementCount(),
                placementAfter,
                standing.gamesPlayed(),
                gamesAfter,
                standing.seasonRp(),
                rpAfter,
                rankFor(placementAfter, rpAfter));
    }

    private PlayerUpdate unchanged(PlayerStanding standing) {
        return new PlayerUpdate(
                standing.playerId(),
                standing.publicRating(),
                standing.publicRating(),
                standing.placementCount(),
                standing.placementCount(),
                standing.gamesPlayed(),
                standing.gamesPlayed(),
                standing.seasonRp(),
                standing.seasonRp(),
                rankFor(standing.placementCount(), standing.seasonRp()));
    }

    private int rpDelta(double actual) {
        if (actual == 1.0d) {
            return policy.winRp();
        }
        if (actual == 0.5d) {
            return policy.drawRp();
        }
        return policy.lossRp();
    }

    private static double expectedScore(int ownRating, int opponentRating) {
        return 1.0d / (1.0d + Math.pow(10.0d, ((double) opponentRating - ownRating) / 400.0d));
    }

    private static double actualScore(MatchOutcome outcome, boolean playerOne) {
        return switch (outcome) {
            case PLAYER_ONE_WIN -> playerOne ? 1.0d : 0.0d;
            case PLAYER_TWO_WIN -> playerOne ? 0.0d : 1.0d;
            case DRAW -> 0.5d;
            case VOIDED -> throw new IllegalArgumentException("voided outcome must not be rated");
        };
    }

    private Rank dividedRank(String tier, int floor, int seasonRp) {
        int band = Math.min(2, (seasonRp - floor) / rankTable.divisionWidth());
        String division = switch (band) {
            case 0 -> "III";
            case 1 -> "II";
            default -> "I";
        };
        return new Rank(tier, division);
    }
}
