package com.ddd.d3.battle.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.battle.application.BattleEventPublisher;
import com.ddd.d3.battle.application.BattleOutboxDispatcher;
import com.ddd.d3.battle.application.BattleResultService;
import com.ddd.d3.battle.domain.outcome.MatchScoreCalculator;
import com.ddd.d3.battle.domain.outcome.RatingProgressionCalculator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class JdbcBattleResultStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:10:00Z");
    private static final UUID SEASON_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    DriverManagerDataSource dataSource;
    JdbcClient jdbc;
    Clock clock;
    JdbcBattleResultStore results;
    JdbcBattleMatchRepository matches;
    TransactionTemplate transactions;

    @BeforeEach
    void migrateSchema() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        results = new JdbcBattleResultStore(dataSource, new ObjectMapper(), clock, SEASON_ID, 1500);
        matches = new JdbcBattleMatchRepository(dataSource, transactionManager);
    }

    @Test
    void d3Btl003CommitsResultRatingAndReplaySafeOutboxExactlyOnceUnderConcurrency() throws Exception {
        MatchFixture match = createJudgingMatch();
        BattleResultService service = new BattleResultService(
                results,
                matches,
                MatchScoreCalculator.initialWeights("score-v1"),
                RatingProgressionCalculator.initialPolicy(),
                ignored -> {},
                clock,
                transactions);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                start.await();
                return service.completeBatch(1);
            });
            Future<Integer> second = executor.submit(() -> {
                start.await();
                return service.completeBatch(1);
            });
            start.countDown();

            assertEquals(1, first.get() + second.get());
        }

        assertEquals(0, service.completeBatch(1));
        assertEquals("FINISHED", value("select status from match where id = :id", match.matchId(), String.class));
        assertEquals("PLAYER_ONE_WIN", value("select result from match where id = :id", match.matchId(), String.class));
        assertEquals("JUDGE_RESULT", value(
                "select resolution_reason from match where id = :id", match.matchId(), String.class));
        assertEquals(2, count("select count(*) from match_player where match_id = :id and score is not null", match.matchId()));
        assertEquals(0, new BigDecimal("100").compareTo(value(
                "select score from match_player where user_id = :id",
                match.playerOneId(),
                BigDecimal.class)));
        assertEquals(0, new BigDecimal("57.5").compareTo(value(
                "select score from match_player where user_id = :id",
                match.playerTwoId(),
                BigDecimal.class)));
        assertEquals(1532, value(
                "select public_rating from rating where user_id = :id", match.playerOneId(), Integer.class));
        assertEquals(1468, value(
                "select public_rating from rating where user_id = :id", match.playerTwoId(), Integer.class));
        assertEquals(3, count("select count(*) from outbox_event where published_at is null", match.matchId(), false));
        assertEquals(1, count("select count(*) from outbox_event where event_type = 'match.finished'", match.matchId(), false));
        assertEquals(2, count("select count(*) from outbox_event where event_type = 'rating.changed'", match.matchId(), false));
        assertEquals(3, count(
                "select count(*) from outbox_event where payload ->> 'eventType' = event_type",
                match.matchId(),
                false));

        JdbcBattleOutboxStore outbox = new JdbcBattleOutboxStore(dataSource);
        BattleOutboxDispatcher failingDispatcher = new BattleOutboxDispatcher(
                new JdbcBattleOutboxStore(dataSource),
                event -> {
                    throw new IllegalStateException("broker unavailable");
                },
                clock);
        assertThrows(IllegalStateException.class, failingDispatcher::dispatchBatch);
        assertEquals(3, count("select count(*) from outbox_event where published_at is null", match.matchId(), false));

        List<String> payloads = new ArrayList<>();
        BattleEventPublisher publisher = event -> payloads.add(event.payload());
        BattleOutboxDispatcher dispatcher = new BattleOutboxDispatcher(outbox, publisher, clock);

        assertEquals(3, dispatcher.dispatchBatch());
        assertEquals(0, dispatcher.dispatchBatch());
        assertEquals(3, payloads.size());
        assertTrue(payloads.stream().allMatch(payload -> payload.contains("\"eventId\"")));
        assertFalse(payloads.stream().anyMatch(payload -> payload.contains("sourceCode")
                || payload.contains("hiddenTests")
                || payload.contains("credential")));
    }

    @Test
    void d3Btl005DoesNotRateOrScoreAVoidedMatch() {
        MatchFixture match = createFinishedVoidMatch();
        BattleResultService service = new BattleResultService(
                results,
                matches,
                MatchScoreCalculator.initialWeights("score-v1"),
                RatingProgressionCalculator.initialPolicy(),
                ignored -> {},
                clock,
                transactions);

        assertEquals(1, service.completeBatch(1));
        assertEquals(0, count(
                "select count(*) from match_player where match_id = :id and score is not null",
                match.matchId()));
        assertEquals(0, count(
                "select count(*) from match_player where match_id = :id and rating_before is not null",
                match.matchId()));
        assertEquals(1500, value(
                "select public_rating from rating where user_id = :id",
                match.playerOneId(),
                Integer.class));
        assertEquals(1, count(
                "select count(*) from outbox_event where event_type = 'match.finished'",
                match.matchId(),
                false));
        assertEquals(0, count(
                "select count(*) from outbox_event where event_type = 'rating.changed'",
                match.matchId(),
                false));
    }

    @Test
    void legacyFinishedEvidenceWithoutRuntimeDataDoesNotPoisonResultCompletion() {
        MatchFixture legacy = createFinishedMatch(
                UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(30));
        addLegacyEvidence(legacy.matchId(), legacy.playerOneId());
        BattleResultService service = new BattleResultService(
                results,
                matches,
                MatchScoreCalculator.initialWeights("score-v1"),
                RatingProgressionCalculator.initialPolicy(),
                ignored -> {},
                clock,
                transactions);

        assertEquals(1, service.completeBatch(1));
        assertEquals(1, count(
                "select count(*) from outbox_event where event_type = 'match.finished'",
                legacy.matchId(),
                false));
    }

    @Test
    void unsafeLegacyJudgingEvidenceDoesNotBlockANewerSafeResult() {
        MatchFixture legacy = createJudgingMatch();
        jdbc.sql("delete from judge_job_reference where match_id = :matchId")
                .param("matchId", legacy.matchId())
                .update();
        addLegacyEvidence(legacy.matchId(), legacy.playerOneId());
        jdbc.sql("update match set deadline_at = :deadline where id = :matchId")
                .param("deadline", Timestamp.from(NOW.minusSeconds(30)))
                .param("matchId", legacy.matchId())
                .update();
        MatchFixture safe = createJudgingMatch();
        BattleResultService service = new BattleResultService(
                results,
                matches,
                MatchScoreCalculator.initialWeights("score-v1"),
                RatingProgressionCalculator.initialPolicy(),
                ignored -> {},
                clock,
                transactions);

        assertEquals(1, service.completeBatch(1));
        assertEquals("JUDGING", value("select status from match where id = :id", legacy.matchId(), String.class));
        assertEquals("FINISHED", value("select status from match where id = :id", safe.matchId(), String.class));
    }

    @Test
    void concurrentClaimsPreserveResultOrderForTheSamePlayer() throws Exception {
        UUID sharedPlayer = UUID.randomUUID();
        MatchFixture older = createFinishedMatch(
                sharedPlayer, UUID.randomUUID(), NOW.minusSeconds(30));
        MatchFixture newer = createFinishedMatch(
                sharedPlayer, UUID.randomUUID(), NOW.minusSeconds(10));
        CountDownLatch olderClaimed = new CountDownLatch(1);
        CountDownLatch releaseOlder = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<UUID> first = executor.submit(() -> transactions.execute(status -> {
                UUID claimed = results.claimNextReady().orElseThrow().matchId();
                jdbc.sql("""
                                insert into outbox_event (
                                    id, aggregate_id, aggregate_version, event_type, payload, occurred_at
                                ) values (
                                    :id, :matchId, 99, 'match.finished', cast('{}' as jsonb), :occurredAt
                                )
                                """)
                        .param("id", UUID.randomUUID())
                        .param("matchId", claimed)
                        .param("occurredAt", Timestamp.from(NOW))
                        .update();
                olderClaimed.countDown();
                try {
                    releaseOlder.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("result claim test was interrupted", exception);
                }
                return claimed;
            }));

            assertTrue(olderClaimed.await(5, TimeUnit.SECONDS));
            try {
                var concurrent = transactions.execute(status -> results.claimNextReady());
                assertTrue(concurrent != null && concurrent.isEmpty());
            } finally {
                releaseOlder.countDown();
            }
            assertEquals(older.matchId(), first.get());
        }

        UUID next = transactions.execute(status -> results.claimNextReady().orElseThrow().matchId());
        assertEquals(newer.matchId(), next);
    }

    private MatchFixture createFinishedVoidMatch() {
        MatchFixture match = createJudgingMatch();
        jdbc.sql("delete from judge_job_reference where match_id = :matchId")
                .param("matchId", match.matchId())
                .update();
        jdbc.sql("""
                        update match
                        set status = 'FINISHED',
                            result = 'VOIDED',
                            finished_at = :finishedAt,
                            void_reason = 'judge-platform-failure',
                            resolution_reason = 'PLATFORM_INCIDENT',
                            aggregate_version = 5
                        where id = :matchId
                        """)
                .param("finishedAt", Timestamp.from(NOW))
                .param("matchId", match.matchId())
                .update();
        return match;
    }

    private MatchFixture createJudgingMatch() {
        UUID problemId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID playerOneId = UUID.randomUUID();
        UUID playerTwoId = UUID.randomUUID();
        jdbc.sql("""
                        insert into problem (
                            id, slug, version, title, difficulty, active, created_at, updated_at
                        ) values (
                            :id, :slug, 4, 'Problem', 'GOLD', true, :now, :now
                        )
                        """)
                .param("id", problemId)
                .param("slug", "problem-" + problemId)
                .param("now", Timestamp.from(NOW.minusSeconds(120)))
                .update();
        jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result,
                            server_started_at, deadline_at, aggregate_version, created_at
                        ) values (
                            :id, :problemId, true, 'JUDGING', null,
                            :startedAt, :deadlineAt, 4, :createdAt
                        )
                        """)
                .param("id", matchId)
                .param("problemId", problemId)
                .param("startedAt", Timestamp.from(NOW.minusSeconds(60)))
                .param("deadlineAt", Timestamp.from(NOW))
                .param("createdAt", Timestamp.from(NOW.minusSeconds(120)))
                .update();
        addPlayer(matchId, playerOneId, 1);
        addPlayer(matchId, playerTwoId, 2);
        addAcceptedEvidence(matchId, playerOneId, NOW.minusSeconds(40), 18_000);
        addAcceptedEvidence(matchId, playerTwoId, NOW.minusSeconds(20), 36_000);
        return new MatchFixture(matchId, playerOneId, playerTwoId);
    }

    private MatchFixture createFinishedMatch(UUID playerOneId, UUID playerTwoId, Instant finishedAt) {
        UUID problemId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        jdbc.sql("""
                        insert into problem (
                            id, slug, version, title, difficulty, active, created_at, updated_at
                        ) values (
                            :id, :slug, 4, 'Problem', 'GOLD', true, :createdAt, :createdAt
                        )
                        """)
                .param("id", problemId)
                .param("slug", "problem-" + problemId)
                .param("createdAt", Timestamp.from(finishedAt.minusSeconds(120)))
                .update();
        jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result, resolution_reason,
                            server_started_at, deadline_at, finished_at, aggregate_version, created_at
                        ) values (
                            :id, :problemId, true, 'FINISHED', 'PLAYER_ONE_WIN', 'SURRENDER',
                            :startedAt, :deadlineAt, :finishedAt, 5, :createdAt
                        )
                        """)
                .param("id", matchId)
                .param("problemId", problemId)
                .param("startedAt", Timestamp.from(finishedAt.minusSeconds(60)))
                .param("deadlineAt", Timestamp.from(finishedAt))
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("createdAt", Timestamp.from(finishedAt.minusSeconds(120)))
                .update();
        addPlayer(matchId, playerOneId, 1);
        addPlayer(matchId, playerTwoId, 2);
        return new MatchFixture(matchId, playerOneId, playerTwoId);
    }

    private void addLegacyEvidence(UUID matchId, UUID playerId) {
        jdbc.sql("""
                        insert into judge_job_reference (
                            submission_id, match_id, player_user_id, mode, command_id,
                            attempt_number, last_judge_status, evidence_version, accepted_at, last_result_at
                        ) values (
                            :submissionId, :matchId, :playerId, 'SUBMIT', :commandId,
                            1, 'WRONG_ANSWER', 'legacy-evidence-v1', :acceptedAt, :resultAt
                        )
                        """)
                .param("submissionId", UUID.randomUUID())
                .param("matchId", matchId)
                .param("playerId", playerId)
                .param("commandId", UUID.randomUUID())
                .param("acceptedAt", Timestamp.from(NOW.minusSeconds(30)))
                .param("resultAt", Timestamp.from(NOW.minusSeconds(20)))
                .update();
    }

    private void addPlayer(UUID matchId, UUID playerId, int seat) {
        jdbc.sql("""
                        insert into match_player (
                            match_id, user_id, seat, language_key, ready, attempts,
                            connection_state, connection_generation
                        ) values (
                            :matchId, :playerId, :seat, 'java', true, 1, 'CONNECTED', 9
                        )
                        """)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .param("seat", seat)
                .update();
    }

    private void addAcceptedEvidence(UUID matchId, UUID playerId, Instant acceptedAt, long runtimeMicros) {
        UUID submissionId = UUID.randomUUID();
        jdbc.sql("""
                        insert into judge_job_reference (
                            submission_id, match_id, player_user_id, mode, command_id,
                            attempt_number, last_judge_status, evidence_version, accepted_at,
                            last_result_at, passed_count, total_count, runtime_measurements,
                            adapter_version, runtime_version
                        ) values (
                            :submissionId, :matchId, :playerId, 'SUBMIT', :commandId,
                            1, 'ACCEPTED', 'judge-evidence-v1', :acceptedAt,
                            :resultAt, 8, 8, cast(:measurements as jsonb),
                            'judge0-v1', 'java-21'
                        )
                        """)
                .param("submissionId", submissionId)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .param("commandId", UUID.randomUUID())
                .param("acceptedAt", Timestamp.from(acceptedAt))
                .param("resultAt", Timestamp.from(acceptedAt.plusSeconds(1)))
                .param("measurements", "[{\"tier\":\"LARGE\",\"inputSize\":10000,\"sampleCount\":3,"
                        + "\"medianRuntimeMicros\":" + runtimeMicros + "}]")
                .update();
    }

    private int count(String sql, UUID id) {
        return count(sql, id, true);
    }

    private int count(String sql, UUID id, boolean bindId) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        if (bindId) {
            statement = statement.param("id", id);
        }
        return statement.query(Integer.class).single();
    }

    private <T> T value(String sql, UUID id, Class<T> type) {
        return jdbc.sql(sql).param("id", id).query(type).single();
    }

    private record MatchFixture(UUID matchId, UUID playerOneId, UUID playerTwoId) {}
}
