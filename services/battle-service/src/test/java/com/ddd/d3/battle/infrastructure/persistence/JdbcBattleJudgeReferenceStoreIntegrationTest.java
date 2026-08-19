package com.ddd.d3.battle.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.battle.application.BattleJudgeGateway;
import com.ddd.d3.battle.application.BattleJudgeReferenceStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
class JdbcBattleJudgeReferenceStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    DriverManagerDataSource dataSource;
    JdbcClient jdbc;
    JdbcBattleJudgeReferenceStore store;
    TransactionTemplate transactions;

    @BeforeEach
    void migrateSchema() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcBattleJudgeReferenceStore(dataSource, new ObjectMapper());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void d3Btl001CorrelatesEarlyJudgeEventAndPersistsOnlySafeEvidenceExactlyOnce() {
        MatchFixture match = createRunningMatch();
        UUID commandId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        BattleJudgeReferenceStore.JudgedEvent event = new BattleJudgeReferenceStore.JudgedEvent(
                eventId, submissionId, 1, NOW.plusSeconds(1));

        assertEquals(Boolean.TRUE, transactions.execute(status -> store.receiveJudgedEvent(event)));
        assertTrue(store.findProcessablePending(10).isEmpty());

        BattleJudgeReferenceStore.SubmissionContext context = transactions.execute(status ->
                store.lockSubmissionContext(
                        match.matchId(), match.playerOneId(), 9, BattleJudgeGateway.Mode.SUBMIT));
        assertEquals(match.problemId(), context.problemId());
        assertEquals(1, context.nextSubmitAttempt());
        transactions.executeWithoutResult(status -> store.record(new BattleJudgeReferenceStore.Reference(
                submissionId,
                match.matchId(),
                match.playerOneId(),
                BattleJudgeGateway.Mode.SUBMIT,
                commandId,
                1,
                "QUEUED",
                null,
                NOW,
                null)));
        assertThrows(IllegalStateException.class, () -> transactions.execute(status ->
                store.lockSubmissionContext(
                        match.matchId(), match.playerOneId(), 9, BattleJudgeGateway.Mode.SUBMIT)));


        assertEquals(List.of(new BattleJudgeReferenceStore.PendingJudgedEvent(eventId, submissionId)),
                store.findProcessablePending(10));
        transactions.executeWithoutResult(status -> {
            assertTrue(store.lockPendingReference(eventId).isPresent());
            store.recordEvidence(eventId, new BattleJudgeReferenceStore.Evidence(
                    submissionId,
                    "ACCEPTED",
                    8,
                    8,
                    List.of(new BattleJudgeGateway.RuntimeMeasurement("LARGE", 10_000, 3, 25_000)),
                    "judge0-v1",
                    "java-21",
                    "judge-evidence-v1",
                    NOW.plusSeconds(2)));
        });

        assertTrue(store.findProcessablePending(10).isEmpty());
        assertEquals(Boolean.FALSE, transactions.execute(status -> store.receiveJudgedEvent(event)));
        assertEquals("ACCEPTED", jdbc.sql("""
                        select last_judge_status
                        from judge_job_reference
                        where submission_id = :submissionId
                        """)
                .param("submissionId", submissionId)
                .query(String.class)
                .single());
        assertEquals(1, jdbc.sql("""
                        select attempts from match_player
                        where match_id = :matchId and user_id = :playerId
                        """)
                .param("matchId", match.matchId())
                .param("playerId", match.playerOneId())
                .query(Integer.class)
                .single());
        assertEquals("LARGE", jdbc.sql("""
                        select runtime_measurements -> 0 ->> 'tier'
                        from judge_job_reference where submission_id = :submissionId
                        """)
                .param("submissionId", submissionId)
                .query(String.class)
                .single());
        assertEquals(0, jdbc.sql("""
                        select count(*) from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'judge_job_reference'
                          and column_name in ('source_code', 'source')
                        """)
                .query(Integer.class)
                .single());
    }

    @Test
    void d3Btl001MapsAllSixRankedLanguageKeysToJudgeCommands() {
        MatchFixture match = createRunningMatch();

        for (String language : List.of("C", "CPP", "JAVA", "PYTHON3", "JAVASCRIPT", "TYPESCRIPT")) {
            jdbc.sql("""
                            update match_player
                            set language_key = :language
                            where match_id = :matchId and user_id = :playerId
                            """)
                    .param("language", language.toLowerCase())
                    .param("matchId", match.matchId())
                    .param("playerId", match.playerOneId())
                    .update();
            BattleJudgeReferenceStore.SubmissionContext context = transactions.execute(status ->
                    store.lockSubmissionContext(
                            match.matchId(), match.playerOneId(), 9, BattleJudgeGateway.Mode.RUN));
            assertEquals(language, context.language());
        }
    }

    @Test
    void v9UpgradePreservesExistingJudgeReferencesAndAddsNullableEvidence() {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target("8").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        MatchFixture match = createRunningMatch();
        UUID submissionId = UUID.randomUUID();
        jdbc.sql("""
                        insert into judge_job_reference (
                            submission_id, match_id, player_user_id, mode, command_id,
                            attempt_number, last_judge_status, accepted_at
                        ) values (
                            :submissionId, :matchId, :playerId, 'SUBMIT', :commandId,
                            1, 'QUEUED', :acceptedAt
                        )
                        """)
                .param("submissionId", submissionId)
                .param("matchId", match.matchId())
                .param("playerId", match.playerOneId())
                .param("commandId", UUID.randomUUID())
                .param("acceptedAt", Timestamp.from(NOW))
                .update();

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertEquals("QUEUED", jdbc.sql("""
                        select last_judge_status from judge_job_reference where submission_id = :submissionId
                        """)
                .param("submissionId", submissionId)
                .query(String.class)
                .single());
        assertEquals(5, jdbc.sql("""
                        select count(*) from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'judge_job_reference'
                          and column_name in (
                              'passed_count', 'total_count', 'runtime_measurements',
                              'adapter_version', 'runtime_version'
                          )
                        """)
                .query(Integer.class)
                .single());
        assertEquals(1, jdbc.sql("""
                        select count(*) from judge_job_reference
                        where submission_id = :submissionId
                          and passed_count is null
                          and runtime_measurements is null
                        """)
                .param("submissionId", submissionId)
                .query(Integer.class)
                .single());
        assertThrows(RuntimeException.class, () -> jdbc.sql("""
                        update judge_job_reference
                        set passed_count = 1
                        where submission_id = :submissionId
                        """)
                .param("submissionId", submissionId)
                .update());
    }

    @Test
    void d3Btl002ReportsBothParticipantsAcceptedOnlyWhenEachHoldsAnAcceptedSubmit() {
        MatchFixture match = createRunningMatch();

        assertEquals(false, store.bothParticipantsAccepted(match.matchId()));

        insertSubmitReference(match.matchId(), match.playerOneId(), 1, "ACCEPTED");
        assertEquals(false, store.bothParticipantsAccepted(match.matchId()));

        insertSubmitReference(match.matchId(), match.playerTwoId(), 1, "WRONG_ANSWER");
        assertEquals(false, store.bothParticipantsAccepted(match.matchId()));

        insertSubmitReference(match.matchId(), match.playerTwoId(), 2, "ACCEPTED");
        assertEquals(true, store.bothParticipantsAccepted(match.matchId()));
    }

    private void insertSubmitReference(UUID matchId, UUID playerId, int attempt, String status) {
        jdbc.sql("""
                        insert into judge_job_reference (
                            submission_id, match_id, player_user_id, mode, command_id,
                            attempt_number, last_judge_status, evidence_version, accepted_at, last_result_at
                        ) values (
                            :submissionId, :matchId, :playerId, 'SUBMIT', :commandId,
                            :attempt, :status, 'judge-evidence-v1', :acceptedAt, :resultAt
                        )
                        """)
                .param("submissionId", UUID.randomUUID())
                .param("matchId", matchId)
                .param("playerId", playerId)
                .param("commandId", UUID.randomUUID())
                .param("attempt", attempt)
                .param("status", status)
                .param("acceptedAt", Timestamp.from(NOW))
                .param("resultAt", Timestamp.from(NOW.plusSeconds(1)))
                .update();
    }

    private MatchFixture createRunningMatch() {
        UUID problemId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID playerOneId = UUID.randomUUID();
        UUID playerTwoId = UUID.randomUUID();
        jdbc.sql("""
                        insert into problem (
                            id, slug, version, title, difficulty, active, created_at, updated_at
                        ) values (
                            :id, :slug, 4, 'Problem', 'GOLD', true, now(), now()
                        )
                        """)
                .param("id", problemId)
                .param("slug", "problem-" + problemId)
                .update();
        jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result,
                            server_started_at, deadline_at, created_at
                        ) values (
                            :id, :problemId, true, 'RUNNING', null,
                            now() - interval '1 minute', now() + interval '9 minutes',
                            now() - interval '2 minutes'
                        )
                        """)
                .param("id", matchId)
                .param("problemId", problemId)
                .update();
        addPlayer(matchId, playerOneId, 1);
        addPlayer(matchId, playerTwoId, 2);
        return new MatchFixture(problemId, matchId, playerOneId, playerTwoId);
    }

    private void addPlayer(UUID matchId, UUID playerId, int seat) {
        jdbc.sql("""
                        insert into match_player (
                            match_id, user_id, seat, language_key, ready,
                            connection_state, connection_generation
                        ) values (:matchId, :playerId, :seat, 'java', true, 'CONNECTED', 9)
                        """)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .param("seat", seat)
                .update();
    }

    private record MatchFixture(UUID problemId, UUID matchId, UUID playerOneId, UUID playerTwoId) {}
}
