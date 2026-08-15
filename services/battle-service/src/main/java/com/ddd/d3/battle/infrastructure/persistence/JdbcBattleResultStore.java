package com.ddd.d3.battle.infrastructure.persistence;

import com.ddd.d3.battle.application.BattleJudgeGateway;
import com.ddd.d3.battle.application.BattleResultStore;
import com.ddd.d3.battle.domain.BattleMatch;
import com.ddd.d3.battle.domain.outcome.MatchOutcome;
import com.ddd.d3.battle.domain.outcome.MatchScoreCalculator;
import com.ddd.d3.battle.domain.outcome.RatingProgressionCalculator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcBattleResultStore implements BattleResultStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final UUID seasonId;
    private final int initialRating;

    public JdbcBattleResultStore(
            DataSource dataSource,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${d3.battle.current-season-id:00000000-0000-0000-0000-000000000001}") UUID seasonId,
            @Value("${d3.battle.ranked-matchmaking.initial-rating:1500}") int initialRating) {
        this.jdbc = JdbcClient.create(dataSource);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.seasonId = Objects.requireNonNull(seasonId, "seasonId");
        if (initialRating < 0) {
            throw new IllegalArgumentException("initialRating must not be negative");
        }
        this.initialRating = initialRating;
    }

    @Override
    public Optional<PendingMatch> claimNextReady() {
        Optional<UUID> candidate = jdbc.sql("""
                        with ready_match as materialized (
                            select candidate.id,
                                   coalesce(candidate.finished_at, candidate.deadline_at) as ready_at
                            from match candidate
                            where (
                                    candidate.status = 'FINISHED'
                                    or (
                                        candidate.status = 'JUDGING'
                                        and not exists (
                                            select 1
                                            from judge_job_reference judge_reference
                                            where judge_reference.match_id = candidate.id
                                              and judge_reference.last_judge_status in ('QUEUED', 'RUNNING')
                                        )
                                        and not exists (
                                            select 1
                                            from judge_job_reference unsafe_reference
                                            where unsafe_reference.match_id = candidate.id
                                              and unsafe_reference.mode = 'SUBMIT'
                                              and unsafe_reference.last_judge_status not in ('QUEUED', 'RUNNING')
                                              and (
                                                  unsafe_reference.evidence_version is null
                                                  or unsafe_reference.runtime_measurements is null
                                              )
                                        )
                                    )
                                )
                              and not exists (
                                  select 1
                                  from outbox_event outbox
                                  where outbox.aggregate_id = candidate.id
                                    and outbox.event_type = 'match.finished'
                              )
                        )
                        select battle_match.id
                        from ready_match candidate
                        join match battle_match on battle_match.id = candidate.id
                        where not exists (
                            select 1
                            from ready_match older
                            where (older.ready_at, older.id) < (candidate.ready_at, candidate.id)
                              and exists (
                                  select 1
                                  from match_player older_player
                                  join match_player candidate_player
                                    on candidate_player.match_id = candidate.id
                                   and candidate_player.user_id = older_player.user_id
                                  where older_player.match_id = older.id
                              )
                        )
                        order by candidate.ready_at, candidate.id
                        limit 1
                        for update of battle_match skip locked
                        """)
                .query(UUID.class)
                .optional();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        UUID matchId = candidate.orElseThrow();
        MatchMetadata match = jdbc.sql("""
                        select battle_match.ranked, problem.slug, problem.version
                        from match battle_match
                        join problem on problem.id = battle_match.problem_id
                        where battle_match.id = :matchId
                        """)
                .param("matchId", matchId)
                .query((resultSet, rowNumber) -> new MatchMetadata(
                        resultSet.getBoolean("ranked"),
                        resultSet.getString("slug") + "-v" + resultSet.getInt("version")))
                .single();
        List<PlayerRow> players = jdbc.sql("""
                        select user_id, seat, attempts
                        from match_player
                        where match_id = :matchId
                        order by seat
                        """)
                .param("matchId", matchId)
                .query((resultSet, rowNumber) -> new PlayerRow(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getInt("seat"),
                        resultSet.getInt("attempts")))
                .list();
        if (players.size() != 2 || players.get(0).seat() != 1 || players.get(1).seat() != 2) {
            throw new IllegalStateException("result match requires two ordered players");
        }
        initializeStandings(players);
        Map<UUID, StandingRow> standings = lockStandings(players);
        List<PlayerContext> contexts = players.stream()
                .map(player -> {
                    StandingRow standing = Objects.requireNonNull(standings.get(player.playerId()), "standing");
                    return new PlayerContext(
                            player.playerId(),
                            player.attempts(),
                            bestPerformance(matchId, player.playerId()),
                            new RatingProgressionCalculator.PlayerStanding(
                                    player.playerId().toString(),
                                    standing.publicRating(),
                                    standing.placementCount(),
                                    standing.gamesPlayed(),
                                    standing.seasonRp()),
                            standing.peakTier());
                })
                .toList();
        return Optional.of(new PendingMatch(matchId, match.ranked(), match.problemVersion(), contexts));
    }

    @Override
    public void commit(Completion completion) {
        Objects.requireNonNull(completion, "completion");
        if (completion.finishedMatch().state() != BattleMatch.State.FINISHED
                || !completion.pending().matchId().toString().equals(completion.finishedMatch().matchId())) {
            throw new IllegalArgumentException("completion requires the claimed finished match");
        }
        if (completion.scoreResult() != null) {
            persistScores(completion);
        }
        if (completion.ratingResult().changed()) {
            persistRatingUpdate(
                    completion.pending().matchId(),
                    completion.pending().playerOne(),
                    completion.ratingResult().playerOne(),
                    completion.playerOnePeakTier(),
                    completion.occurredAt());
            persistRatingUpdate(
                    completion.pending().matchId(),
                    completion.pending().playerTwo(),
                    completion.ratingResult().playerTwo(),
                    completion.playerTwoPeakTier(),
                    completion.occurredAt());
        }
        insertMatchFinished(completion);
        if (completion.ratingResult().changed()) {
            insertRatingChanged(completion, completion.ratingResult().playerOne());
            insertRatingChanged(completion, completion.ratingResult().playerTwo());
        }
    }

    private void initializeStandings(List<PlayerRow> players) {
        List<UUID> ordered = players.stream()
                .map(PlayerRow::playerId)
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        Instant now = clock.instant();
        for (UUID playerId : ordered) {
            jdbc.sql("""
                            insert into rating (user_id, public_rating, placement_count, games_played, updated_at)
                            values (:playerId, :initialRating, 0, 0, :updatedAt)
                            on conflict (user_id) do nothing
                            """)
                    .param("playerId", playerId)
                    .param("initialRating", initialRating)
                    .param("updatedAt", Timestamp.from(now))
                    .update();
            jdbc.sql("""
                            insert into season_rank (season_id, user_id, rp, tier, division, peak_tier, updated_at)
                            values (:seasonId, :playerId, 0, 'Unranked', null, 'Unranked', :updatedAt)
                            on conflict (season_id, user_id) do nothing
                            """)
                    .param("seasonId", seasonId)
                    .param("playerId", playerId)
                    .param("updatedAt", Timestamp.from(now))
                    .update();
        }
    }

    private Map<UUID, StandingRow> lockStandings(List<PlayerRow> players) {
        UUID firstId = players.stream()
                .map(PlayerRow::playerId)
                .min(Comparator.comparing(UUID::toString))
                .orElseThrow();
        UUID secondId = players.stream()
                .map(PlayerRow::playerId)
                .max(Comparator.comparing(UUID::toString))
                .orElseThrow();
        List<StandingRow> rows = jdbc.sql("""
                        select rating.user_id, rating.public_rating, rating.placement_count, rating.games_played,
                               season_rank.rp, season_rank.peak_tier
                        from rating
                        join season_rank on season_rank.user_id = rating.user_id
                                        and season_rank.season_id = :seasonId
                        where rating.user_id in (:firstId, :secondId)
                        order by rating.user_id
                        for update of rating, season_rank
                        """)
                .param("seasonId", seasonId)
                .param("firstId", firstId)
                .param("secondId", secondId)
                .query((resultSet, rowNumber) -> new StandingRow(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getInt("public_rating"),
                        resultSet.getInt("placement_count"),
                        resultSet.getInt("games_played"),
                        resultSet.getInt("rp"),
                        resultSet.getString("peak_tier")))
                .list();
        if (rows.size() != 2) {
            throw new IllegalStateException("both player standings must exist");
        }
        Map<UUID, StandingRow> byPlayer = new LinkedHashMap<>();
        rows.forEach(row -> byPlayer.put(row.playerId(), row));
        return byPlayer;
    }

    private Optional<JudgedPerformance> bestPerformance(UUID matchId, UUID playerId) {
        return jdbc.sql("""
                        select last_judge_status, accepted_at, passed_count, total_count,
                               runtime_measurements::text, adapter_version, runtime_version, evidence_version
                        from judge_job_reference
                        where match_id = :matchId
                          and player_user_id = :playerId
                          and mode = 'SUBMIT'
                          and last_judge_status not in ('QUEUED', 'RUNNING', 'PLATFORM_FAILURE')
                          and runtime_measurements is not null
                        order by
                            case when last_judge_status = 'ACCEPTED' then 1 else 0 end desc,
                            case when total_count > 0 then passed_count::numeric / total_count else 0 end desc,
                            attempt_number desc
                        limit 1
                        """)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .query((resultSet, rowNumber) -> {
                    Timestamp acceptedAt = resultSet.getTimestamp("accepted_at");
                    return new JudgedPerformance(
                            "ACCEPTED".equals(resultSet.getString("last_judge_status")),
                            acceptedAt == null ? null : acceptedAt.toInstant(),
                            resultSet.getInt("passed_count"),
                            resultSet.getInt("total_count"),
                            runtimeMeasurements(resultSet.getString("runtime_measurements")),
                            resultSet.getString("adapter_version"),
                            resultSet.getString("runtime_version"),
                            resultSet.getString("evidence_version"));
                })
                .optional();
    }

    private void persistScores(Completion completion) {
        MatchScoreCalculator.MatchScoreResult result = completion.scoreResult();
        persistScore(completion.pending().playerOne().playerId(), completion.pending().matchId(),
                result.scoreFor(completion.pending().playerOne().playerId().toString()));
        persistScore(completion.pending().playerTwo().playerId(), completion.pending().matchId(),
                result.scoreFor(completion.pending().playerTwo().playerId().toString()));
    }

    private void persistScore(UUID playerId, UUID matchId, MatchScoreCalculator.PlayerScore score) {
        int updated = jdbc.sql("""
                        update match_player
                        set score = :score,
                            speed_score_component = :speed,
                            efficiency_score_component = :efficiency,
                            submission_score_component = :discipline,
                            score_calculation_version = :calculationVersion,
                            score_problem_version = :problemVersion,
                            score_runtime_version = :runtimeVersion,
                            score_calibration_version = :calibrationVersion
                        where match_id = :matchId
                          and user_id = :playerId
                          and score is null
                        """)
                .param("score", score.total())
                .param("speed", score.speed())
                .param("efficiency", score.dynamicEfficiency())
                .param("discipline", score.submissionDiscipline())
                .param("calculationVersion", score.calculationVersion())
                .param("problemVersion", score.evidenceVersion().problemVersion())
                .param("runtimeVersion", score.evidenceVersion().runtimeVersion())
                .param("calibrationVersion", score.evidenceVersion().calibrationVersion())
                .param("matchId", matchId)
                .param("playerId", playerId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("player score was already committed");
        }
    }

    private void persistRatingUpdate(
            UUID matchId,
            PlayerContext context,
            RatingProgressionCalculator.PlayerUpdate update,
            String peakTier,
            Instant occurredAt) {
        UUID playerId = context.playerId();
        int ratingUpdated = jdbc.sql("""
                        update rating
                        set public_rating = :ratingAfter,
                            placement_count = :placementAfter,
                            games_played = :gamesAfter,
                            updated_at = :updatedAt
                        where user_id = :playerId
                          and public_rating = :ratingBefore
                          and placement_count = :placementBefore
                          and games_played = :gamesBefore
                        """)
                .param("ratingAfter", update.ratingAfter())
                .param("placementAfter", update.placementCountAfter())
                .param("gamesAfter", update.gamesPlayedAfter())
                .param("updatedAt", Timestamp.from(occurredAt))
                .param("playerId", playerId)
                .param("ratingBefore", update.ratingBefore())
                .param("placementBefore", update.placementCountBefore())
                .param("gamesBefore", update.gamesPlayedBefore())
                .update();
        if (ratingUpdated != 1) {
            throw new IllegalStateException("rating standing changed while result was locked");
        }
        int seasonUpdated = jdbc.sql("""
                        update season_rank
                        set rp = :rpAfter,
                            tier = :tierAfter,
                            division = :divisionAfter,
                            peak_tier = :peakTier,
                            updated_at = :updatedAt
                        where season_id = :seasonId
                          and user_id = :playerId
                          and rp = :rpBefore
                        """)
                .param("rpAfter", update.seasonRpAfter())
                .param("tierAfter", update.rankAfter().tier())
                .param("divisionAfter", update.rankAfter().division())
                .param("peakTier", peakTier)
                .param("updatedAt", Timestamp.from(occurredAt))
                .param("seasonId", seasonId)
                .param("playerId", playerId)
                .param("rpBefore", update.seasonRpBefore())
                .update();
        if (seasonUpdated != 1) {
            throw new IllegalStateException("season standing changed while result was locked");
        }
        int playerUpdated = jdbc.sql("""
                        update match_player
                        set rating_before = :ratingBefore,
                            rating_after = :ratingAfter,
                            rp_before = :rpBefore,
                            rp_after = :rpAfter,
                            placement_count_before = :placementBefore,
                            placement_count_after = :placementAfter,
                            season_id = :seasonId,
                            tier_after = :tierAfter,
                            division_after = :divisionAfter
                        where match_id = :matchId
                          and user_id = :playerId
                          and rating_before is null
                        """)
                .param("ratingBefore", update.ratingBefore())
                .param("ratingAfter", update.ratingAfter())
                .param("rpBefore", update.seasonRpBefore())
                .param("rpAfter", update.seasonRpAfter())
                .param("placementBefore", update.placementCountBefore())
                .param("placementAfter", update.placementCountAfter())
                .param("seasonId", seasonId)
                .param("tierAfter", update.rankAfter().tier())
                .param("divisionAfter", update.rankAfter().division())
                .param("matchId", matchId)
                .param("playerId", playerId)
                .update();
        if (playerUpdated != 1) {
            throw new IllegalStateException("match rating audit was already committed");
        }
    }

    private void insertMatchFinished(Completion completion) {
        BattleMatch.Snapshot match = completion.finishedMatch();
        MatchOutcome outcome = outcome(match);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matchId", match.matchId());
        data.put("result", outcome.name());
        data.put("ranked", completion.pending().ranked());
        data.put("playerIds", List.of(match.playerOneId(), match.playerTwoId()));
        insertOutbox(
                UUID.randomUUID(),
                completion.pending().matchId(),
                match.aggregateVersion(),
                "match.finished",
                match.result().resolvedAt(),
                completion.pending().matchId().toString(),
                data);
    }

    private void insertRatingChanged(
            Completion completion, RatingProgressionCalculator.PlayerUpdate update) {
        UUID playerId = UUID.fromString(update.playerId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", update.playerId());
        data.put("matchId", completion.pending().matchId().toString());
        data.put("ratingBefore", update.ratingBefore());
        data.put("ratingAfter", update.ratingAfter());
        data.put("seasonRpAfter", update.seasonRpAfter());
        data.put("tierAfter", displayRank(update.rankAfter()));
        insertOutbox(
                UUID.randomUUID(),
                playerId,
                update.gamesPlayedAfter(),
                "rating.changed",
                completion.occurredAt(),
                completion.pending().matchId().toString(),
                data);
    }

    private void insertOutbox(
            UUID eventId,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            Instant occurredAt,
            String correlationId,
            Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("version", 1);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("correlationId", correlationId);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("aggregateVersion", aggregateVersion);
        envelope.put("data", data);
        jdbc.sql("""
                        insert into outbox_event (
                            id, aggregate_id, aggregate_version, event_type, payload, occurred_at, published_at)
                        values (
                            :eventId, :aggregateId, :aggregateVersion, :eventType,
                            cast(:payload as jsonb), :occurredAt, null)
                        """)
                .param("eventId", eventId)
                .param("aggregateId", aggregateId)
                .param("aggregateVersion", aggregateVersion)
                .param("eventType", eventType)
                .param("payload", json(envelope))
                .param("occurredAt", Timestamp.from(occurredAt))
                .update();
    }

    private MatchOutcome outcome(BattleMatch.Snapshot match) {
        BattleMatch.Result result = Objects.requireNonNull(match.result(), "finished result");
        return switch (result.outcome()) {
            case VOID -> MatchOutcome.VOIDED;
            case DRAW -> MatchOutcome.DRAW;
            case WIN -> result.winnerId().equals(match.playerOneId())
                    ? MatchOutcome.PLAYER_ONE_WIN
                    : MatchOutcome.PLAYER_TWO_WIN;
        };
    }

    private static String displayRank(RatingProgressionCalculator.Rank rank) {
        return rank.division() == null ? rank.tier() : rank.tier() + " " + rank.division();
    }

    private List<BattleJudgeGateway.RuntimeMeasurement> runtimeMeasurements(String value) {
        try {
            BattleJudgeGateway.RuntimeMeasurement[] parsed = objectMapper.readValue(
                    value, BattleJudgeGateway.RuntimeMeasurement[].class);
            return List.of(parsed);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored runtime measurements are malformed", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("battle outbox payload could not be serialized", exception);
        }
    }

    private record MatchMetadata(boolean ranked, String problemVersion) {}

    private record PlayerRow(UUID playerId, int seat, int attempts) {}

    private record StandingRow(
            UUID playerId,
            int publicRating,
            int placementCount,
            int gamesPlayed,
            int seasonRp,
            String peakTier) {}
}
