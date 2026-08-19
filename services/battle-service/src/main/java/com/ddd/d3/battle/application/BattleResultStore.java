package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import com.ddd.d3.battle.domain.outcome.MatchScoreCalculator;
import com.ddd.d3.battle.domain.outcome.RatingProgressionCalculator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BattleResultStore {

    Optional<PendingMatch> claimNextReady();

    void commit(Completion completion);

    record JudgedPerformance(
            boolean accepted,
            String verdict,
            Instant acceptedAt,
            int passedCount,
            int totalCount,
            List<BattleJudgeGateway.RuntimeMeasurement> runtimeMeasurements,
            String adapterVersion,
            String runtimeVersion,
            String evidenceVersion) {

        public JudgedPerformance {
            runtimeMeasurements = List.copyOf(runtimeMeasurements);
        }
    }

    record PlayerContext(
            UUID playerId,
            String language,
            int submissionAttempts,
            Optional<JudgedPerformance> performance,
            RatingProgressionCalculator.PlayerStanding standing,
            String peakTier) {}

    record PendingMatch(
            UUID matchId,
            boolean ranked,
            String problemVersion,
            List<PlayerContext> players) {

        public PendingMatch {
            players = List.copyOf(players);
            if (players.size() != 2) {
                throw new IllegalArgumentException("pending match requires two ordered players");
            }
        }

        public PlayerContext playerOne() {
            return players.get(0);
        }

        public PlayerContext playerTwo() {
            return players.get(1);
        }
    }

    record Completion(
            PendingMatch pending,
            BattleMatch.Snapshot finishedMatch,
            MatchScoreCalculator.MatchScoreResult scoreResult,
            RatingProgressionCalculator.RatingProgressionResult ratingResult,
            String playerOnePeakTier,
            String playerTwoPeakTier,
            Instant occurredAt) {}
}
