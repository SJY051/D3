package com.ddd.d3.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.lettuce.core.RedisClient;
import java.sql.Types;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class BattleInfrastructureIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.4.5-alpine").withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka:4.1.2").asCompatibleSubstituteFor("apache/kafka"));

    JdbcClient jdbc;
    DriverManagerDataSource dataSource;
    int migrations;

    @BeforeEach
    void migrateSchema() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        migrations = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void d3Qlt001MigratesTheBattleOwnedSchema() {
        Set<String> tables = Set.copyOf(jdbc.sql(
                        "select table_name from information_schema.tables where table_schema = 'public'")
                .query(String.class)
                .list());

        assertEquals(2, migrations);
        assertEquals(
                Set.of(
                        "flyway_schema_history",
                        "problem",
                        "match",
                        "match_player",
                        "match_player_legacy_accepted_pointer",
                        "judge_job_reference",
                        "judge_job_reference_legacy_duplicate",
                        "attack_event",
                        "rating",
                        "season_rank",
                        "outbox_event",
                        "inbox_event"),
                tables);
    }

    @Test
    void d3Qlt001ConnectsToCoordinationDependencies() throws Exception {
        RedisClient redis = RedisClient.create(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        try (var connection = redis.connect()) {
            assertEquals("PONG", connection.sync().ping());
        } finally {
            redis.shutdown();
        }

        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            assertFalse(admin.describeCluster().clusterId().get().isBlank());
        }
    }

    @Test
    void d3Btl002RejectsImpossibleMatchLifecycleStates() {
        UUID problemId = createProblem();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result, void_reason, created_at
                        ) values (:id, :problemId, true, 'LOBBY', null, 'not voided', now())
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result,
                            server_started_at, deadline_at, created_at
                        ) values (
                            :id, :problemId, true, 'FINISHED', 'DRAW',
                            now() - interval '1 minute', now() + interval '1 minute',
                            now() - interval '2 minutes'
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result,
                            server_started_at, deadline_at, finished_at, created_at
                        ) values (
                            :id, :problemId, true, 'RUNNING', null,
                            now() - interval '1 minute', now() + interval '1 minute', now(),
                            now() - interval '2 minutes'
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result, created_at
                        ) values (:id, :problemId, true, 'RUNNING', null, now())
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result,
                            server_started_at, deadline_at, created_at
                        ) values (
                            :id, :problemId, true, 'RUNNING', null,
                            now() - interval '2 minutes', now() + interval '1 minute',
                            now() - interval '1 minute'
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result,
                            server_started_at, deadline_at, finished_at, created_at
                        ) values (
                            :id, :problemId, true, 'FINISHED', 'DRAW',
                            now(), now() + interval '1 minute',
                            now() - interval '1 minute', now() - interval '2 minutes'
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> insertFinishedVoidMatch(problemId, null));
        assertThrows(DataIntegrityViolationException.class, () -> insertFinishedVoidMatch(problemId, "   "));
        assertEquals(1, insertFinishedVoidMatch(problemId, "judge incident"));
    }

    @Test
    void d3Btl002KeepsReconnectStateAndDeadlineAtomic() {
        UUID matchId = createRunningMatch();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match_player (
                            match_id, user_id, seat, language_key, connection_state
                        ) values (:matchId, :userId, 1, 'java', 'DISCONNECTED')
                        """)
                .param("matchId", matchId)
                .param("userId", UUID.randomUUID())
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match_player (
                            match_id, user_id, seat, language_key, connection_state, reconnect_deadline_at
                        ) values (:matchId, :userId, 1, 'java', 'CONNECTED', now())
                        """)
                .param("matchId", matchId)
                .param("userId", UUID.randomUUID())
                .update());

        assertEquals(1, addPlayer(matchId, UUID.randomUUID(), 1));
        assertEquals(1, addPlayer(matchId, UUID.randomUUID(), 2));
    }

    @Test
    void d3Jdg001UsesJudgeJobsAsTheAcceptedSubmissionSourceOfTruth() {
        UUID matchId = createRunningMatch();
        UUID playerId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        addPlayer(matchId, playerId, 1);
        addPlayer(matchId, opponentId, 2);

        assertThrows(DataIntegrityViolationException.class, () -> insertQueuedJudgeJob(
                matchId, UUID.randomUUID(), "RUN", null));
        assertThrows(DataIntegrityViolationException.class, () -> insertQueuedJudgeJob(matchId, playerId, "RUN", 1));
        assertThrows(DataIntegrityViolationException.class, () -> insertQueuedJudgeJob(matchId, playerId, "SUBMIT", null));
        assertThrows(DataIntegrityViolationException.class, () -> insertQueuedJudgeJob(matchId, playerId, "SUBMIT", 0));

        assertEquals(1, insertQueuedJudgeJob(matchId, playerId, "RUN", null));
        assertEquals(1, insertCompletedSubmit(matchId, playerId, 1, "WRONG_ANSWER"));
        assertThrows(DataIntegrityViolationException.class, () ->
                insertCompletedSubmit(matchId, playerId, 1, "RUNTIME_ERROR"));

        UUID acceptedSubmissionId = UUID.randomUUID();
        assertEquals(1, insertCompletedSubmit(acceptedSubmissionId, matchId, playerId, 2, "ACCEPTED"));
        assertThrows(DataIntegrityViolationException.class, () ->
                insertCompletedSubmit(matchId, playerId, 3, "ACCEPTED"));
        assertEquals(1, insertCompletedSubmit(matchId, opponentId, 1, "ACCEPTED"));

        UUID persistedAcceptedSubmissionId = jdbc.sql("""
                        select submission_id
                        from judge_job_reference
                        where match_id = :matchId
                          and player_user_id = :playerId
                          and mode = 'SUBMIT'
                          and last_judge_status = 'ACCEPTED'
                        """)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .query(UUID.class)
                .single();
        assertEquals(acceptedSubmissionId, persistedAcceptedSubmissionId);
    }

    @Test
    void d3Qlt001UpgradesExistingJudgeCorrelationsWithoutChangingV1() {
        migrateOnlyThrough("1");
        UUID matchId = createRunningMatch();
        UUID playerId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        addPlayer(matchId, playerId, 1);
        addPlayer(matchId, opponentId, 2);
        UUID runSubmissionId = insertLegacyRun(matchId, playerId);
        UUID acceptedSubmissionId = UUID.randomUUID();
        UUID supersededSubmissionId = UUID.randomUUID();
        UUID fallbackSubmissionId = UUID.randomUUID();
        UUID fallbackSupersededSubmissionId = UUID.randomUUID();
        UUID duplicateAttemptSubmissionId = UUID.randomUUID();
        UUID invalidAcceptedPointer = UUID.randomUUID();
        insertCompletedSubmit(acceptedSubmissionId, matchId, playerId, 1, "ACCEPTED");
        insertCompletedSubmit(supersededSubmissionId, matchId, playerId, 2, "ACCEPTED");
        insertCompletedSubmit(fallbackSupersededSubmissionId, matchId, opponentId, 2, "ACCEPTED");
        insertCompletedSubmit(fallbackSubmissionId, matchId, opponentId, 1, "ACCEPTED");
        insertCompletedSubmit(duplicateAttemptSubmissionId, matchId, opponentId, 1, "WRONG_ANSWER");
        assertEquals(1, jdbc.sql("""
                        update match_player
                        set accepted_submission_id = :submissionId
                        where match_id = :matchId and user_id = :playerId
                        """)
                .param("submissionId", acceptedSubmissionId)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .update());
        assertEquals(1, jdbc.sql("""
                        update match_player
                        set accepted_submission_id = :submissionId
                        where match_id = :matchId and user_id = :playerId
                        """)
                .param("submissionId", invalidAcceptedPointer)
                .param("matchId", matchId)
                .param("playerId", opponentId)
                .update());

        int applied = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;

        assertEquals(1, applied);
        assertEquals(1, jdbc.sql("""
                        select count(*)
                        from judge_job_reference
                        where submission_id = :submissionId and attempt_number is null
                        """)
                .param("submissionId", runSubmissionId)
                .query(Integer.class)
                .single());
        assertEquals(acceptedSubmissionId, jdbc.sql("""
                        select submission_id
                        from judge_job_reference
                        where match_id = :matchId
                          and player_user_id = :playerId
                          and mode = 'SUBMIT'
                          and last_judge_status = 'ACCEPTED'
                        """)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .query(UUID.class)
                .single());
        assertEquals(fallbackSubmissionId, jdbc.sql("""
                        select submission_id
                        from judge_job_reference
                        where match_id = :matchId
                          and player_user_id = :playerId
                          and mode = 'SUBMIT'
                          and last_judge_status = 'ACCEPTED'
                        """)
                .param("matchId", matchId)
                .param("playerId", opponentId)
                .query(UUID.class)
                .single());
        assertEquals(3, jdbc.sql("select count(*) from judge_job_reference_legacy_duplicate")
                .query(Integer.class)
                .single());
        assertEquals(acceptedSubmissionId, jdbc.sql("""
                        select canonical_submission_id
                        from judge_job_reference_legacy_duplicate
                        where submission_id = :submissionId
                        """)
                .param("submissionId", supersededSubmissionId)
                .query(UUID.class)
                .single());
        assertEquals(fallbackSubmissionId, jdbc.sql("""
                        select canonical_submission_id
                        from judge_job_reference_legacy_duplicate
                        where submission_id = :submissionId
                        """)
                .param("submissionId", fallbackSupersededSubmissionId)
                .query(UUID.class)
                .single());
        assertEquals("DUPLICATE_SUBMIT_ATTEMPT", jdbc.sql("""
                        select archive_reason
                        from judge_job_reference_legacy_duplicate
                        where submission_id = :submissionId
                        """)
                .param("submissionId", duplicateAttemptSubmissionId)
                .query(String.class)
                .single());
        assertEquals(true, jdbc.sql("""
                        select correlation_valid
                        from match_player_legacy_accepted_pointer
                        where match_id = :matchId and player_user_id = :playerId
                        """)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .query(Boolean.class)
                .single());
        assertEquals(false, jdbc.sql("""
                        select correlation_valid
                        from match_player_legacy_accepted_pointer
                        where match_id = :matchId and player_user_id = :playerId
                        """)
                .param("matchId", matchId)
                .param("playerId", opponentId)
                .query(Boolean.class)
                .single());
        assertEquals(0, jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'match_player'
                          and column_name = 'accepted_submission_id'
                        """)
                .query(Integer.class)
                .single());
    }

    @Test
    void d3Qlt001PreservesLegacyRowsWhileEnforcingNewLifecycleWrites() {
        migrateOnlyThrough("1");
        UUID problemId = createProblem();
        UUID matchId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        assertEquals(1, jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result,
                            server_started_at, deadline_at, created_at
                        ) values (
                            :id, :problemId, true, 'LOBBY', null,
                            now(), now() + interval '10 minutes', now()
                        )
                        """)
                .param("id", matchId)
                .param("problemId", problemId)
                .update());
        assertEquals(1, jdbc.sql("""
                        insert into match_player (
                            match_id, user_id, seat, language_key,
                            connection_state, reconnect_deadline_at
                        ) values (
                            :matchId, :playerId, 1, 'JAVA',
                            'CONNECTED', now() + interval '30 seconds'
                        )
                        """)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .update());

        int applied = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;

        assertEquals(1, applied);
        assertEquals(1, jdbc.sql("select count(*) from match where id = :id")
                .param("id", matchId)
                .query(Integer.class)
                .single());
        assertEquals(2, jdbc.sql("""
                        select count(*)
                        from pg_constraint
                        where conname in (
                            'match_clock_state_consistent',
                            'match_player_reconnect_deadline_consistent'
                        ) and not convalidated
                        """)
                .query(Integer.class)
                .single());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match_player (
                            match_id, user_id, seat, language_key,
                            connection_state, reconnect_deadline_at
                        ) values (
                            :matchId, :playerId, 2, 'JAVA',
                            'CONNECTED', now() + interval '30 seconds'
                        )
                        """)
                .param("matchId", matchId)
                .param("playerId", UUID.randomUUID())
                .update());
    }

    @Test
    void d3Btl004KeepsAttackActorsAndTargetsInsideTheMatch() {
        UUID matchId = createRunningMatch();
        UUID playerId = UUID.randomUUID();
        UUID opponentId = UUID.randomUUID();
        addPlayer(matchId, playerId, 1);
        addPlayer(matchId, opponentId, 2);

        assertThrows(DataIntegrityViolationException.class, () ->
                insertAttack(matchId, UUID.randomUUID(), opponentId));
        assertThrows(DataIntegrityViolationException.class, () ->
                insertAttack(matchId, playerId, UUID.randomUUID()));
        assertEquals(1, insertAttack(matchId, playerId, opponentId));
    }

    private UUID createProblem() {
        UUID problemId = UUID.randomUUID();
        assertEquals(1, jdbc.sql("""
                        insert into problem (
                            id, slug, version, title, difficulty, active, created_at, updated_at
                        ) values (:id, :slug, 1, 'Fixture', 'EASY', true, now(), now())
                        """)
                .param("id", problemId)
                .param("slug", "fixture-" + problemId)
                .update());
        return problemId;
    }

    private UUID createRunningMatch() {
        UUID problemId = createProblem();
        UUID matchId = UUID.randomUUID();
        assertEquals(1, jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result,
                            server_started_at, deadline_at, created_at
                        ) values (
                            :id, :problemId, true, 'RUNNING', null,
                            now() - interval '1 minute', now() + interval '1 minute',
                            now() - interval '2 minutes'
                        )
                        """)
                .param("id", matchId)
                .param("problemId", problemId)
                .update());
        return matchId;
    }

    private int insertFinishedVoidMatch(UUID problemId, String voidReason) {
        return jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result, void_reason,
                            server_started_at, deadline_at, finished_at, created_at
                        ) values (
                            :id, :problemId, true, 'FINISHED', 'VOIDED', :voidReason,
                            now() - interval '2 minutes', now() + interval '1 minute',
                            now(), now() - interval '3 minutes'
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .param("voidReason", voidReason, Types.VARCHAR)
                .update();
    }

    private int addPlayer(UUID matchId, UUID userId, int seat) {
        return jdbc.sql("""
                        insert into match_player (
                            match_id, user_id, seat, language_key, connection_state
                        ) values (:matchId, :userId, :seat, 'java', 'CONNECTED')
                        """)
                .param("matchId", matchId)
                .param("userId", userId)
                .param("seat", seat)
                .update();
    }

    private int insertQueuedJudgeJob(UUID matchId, UUID playerId, String mode, Integer attemptNumber) {
        return jdbc.sql("""
                        insert into judge_job_reference (
                            submission_id, match_id, player_user_id, mode, command_id,
                            attempt_number, last_judge_status, accepted_at
                        ) values (
                            :submissionId, :matchId, :playerId, :mode, :commandId,
                            :attemptNumber, 'QUEUED', now()
                        )
                        """)
                .param("submissionId", UUID.randomUUID())
                .param("matchId", matchId)
                .param("playerId", playerId)
                .param("mode", mode)
                .param("commandId", UUID.randomUUID())
                .param("attemptNumber", attemptNumber, Types.INTEGER)
                .update();
    }

    private UUID insertLegacyRun(UUID matchId, UUID playerId) {
        UUID submissionId = UUID.randomUUID();
        assertEquals(1, jdbc.sql("""
                        insert into judge_job_reference (
                            submission_id, match_id, player_user_id, mode, command_id,
                            attempt_number, last_judge_status, accepted_at
                        ) values (
                            :submissionId, :matchId, :playerId, 'RUN', :commandId,
                            0, 'QUEUED', now()
                        )
                        """)
                .param("submissionId", submissionId)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .param("commandId", UUID.randomUUID())
                .update());
        return submissionId;
    }

    private void migrateOnlyThrough(String version) {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target(version).load().migrate();
    }

    private int insertCompletedSubmit(UUID matchId, UUID playerId, int attemptNumber, String status) {
        return insertCompletedSubmit(UUID.randomUUID(), matchId, playerId, attemptNumber, status);
    }

    private int insertCompletedSubmit(
            UUID submissionId, UUID matchId, UUID playerId, int attemptNumber, String status) {
        return jdbc.sql("""
                        insert into judge_job_reference (
                            submission_id, match_id, player_user_id, mode, command_id,
                            attempt_number, last_judge_status, evidence_version,
                            accepted_at, last_result_at
                        ) values (
                            :submissionId, :matchId, :playerId, 'SUBMIT', :commandId,
                            :attemptNumber, :status, 'judge-evidence.v1',
                            now() - interval '1 second', now()
                        )
                        """)
                .param("submissionId", submissionId)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .param("commandId", UUID.randomUUID())
                .param("attemptNumber", attemptNumber)
                .param("status", status)
                .update();
    }

    private int insertAttack(UUID matchId, UUID actorId, UUID targetId) {
        return jdbc.sql("""
                        insert into attack_event (
                            id, match_id, sequence, actor_user_id, target_user_id,
                            attack_type, resolution, energy_cost, occurred_at
                        ) values (
                            :id, :matchId,
                            (select coalesce(max(sequence), 0) + 1 from attack_event where match_id = :matchId),
                            :actorId, :targetId, 'CAESAR', 'APPLIED', 1, now()
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("matchId", matchId)
                .param("actorId", actorId)
                .param("targetId", targetId)
                .update();
    }
}
