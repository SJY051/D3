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
import org.junit.jupiter.api.BeforeAll;
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

    static JdbcClient jdbc;
    static int migrations;

    @BeforeAll
    static void migrateSchema() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        migrations = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void d3Qlt001MigratesTheBattleOwnedSchema() {
        Set<String> tables = Set.copyOf(jdbc.sql(
                        "select table_name from information_schema.tables where table_schema = 'public'")
                .query(String.class)
                .list());

        assertEquals(1, migrations);
        assertEquals(
                Set.of(
                        "flyway_schema_history",
                        "problem",
                        "match",
                        "match_player",
                        "judge_job_reference",
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
