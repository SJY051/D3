package com.ddd.d3.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.lettuce.core.RedisClient;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.flywaydb.core.Flyway;
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

    @Test
    void d3Qlt001MigratesBattleDataAndConnectsToCoordinationDependencies() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        int migrations = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;
        Set<String> tables = Set.copyOf(JdbcClient.create(dataSource)
                .sql("select table_name from information_schema.tables where table_schema = 'public'")
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

        var jdbc = JdbcClient.create(dataSource);
        UUID problemId = UUID.randomUUID();
        jdbc.sql("""
                        insert into problem (
                            id, slug, version, title, difficulty, active, created_at, updated_at
                        ) values (:id, :slug, 1, 'Void invariant fixture', 'EASY', true, now(), now())
                        """)
                .param("id", problemId)
                .param("slug", "void-invariant-" + problemId)
                .update();

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
                            id, problem_id, ranked, status, result, created_at
                        ) values (:id, :problemId, true, 'FINISHED', 'DRAW', now())
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result, finished_at, created_at
                        ) values (:id, :problemId, true, 'RUNNING', null, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result, server_started_at, finished_at, created_at
                        ) values (
                            :id, :problemId, true, 'FINISHED', 'DRAW',
                            now(), now() - interval '1 minute', now() - interval '2 minutes'
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result, void_reason, finished_at, created_at
                        ) values (:id, :problemId, true, 'FINISHED', 'VOIDED', null, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result, void_reason, finished_at, created_at
                        ) values (:id, :problemId, true, 'FINISHED', 'VOIDED', '   ', now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

        assertEquals(1, jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result, void_reason, finished_at, created_at
                        ) values (:id, :problemId, true, 'FINISHED', 'VOIDED', 'judge incident', now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("problemId", problemId)
                .update());

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
}
