package com.ddd.d3.community.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.community.adapter.persistence.JdbcCommunityRepository.MatchFinishedProjection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

@Testcontainers
class JdbcMatchProjectionConsumerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    private static final UUID EVENT_ID = UUID.fromString("55555555-5555-4555-8555-555555555551");
    private static final UUID MATCH_ID = UUID.fromString("44444444-4444-4444-8444-444444444441");
    private static final UUID PLAYER_ONE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_TWO = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private JdbcClient jdbc;
    private JdbcCommunityRepository repository;

    @BeforeEach
    void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcCommunityRepository(
                jdbc,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void d3Stat001AppliesMatchFinishedOnceAndSkipsDuplicateDelivery() {
        var event = new MatchFinishedProjection(
                EVENT_ID, "match.finished", MATCH_ID, 1L,
                MATCH_ID, "PLAYER_ONE_WIN", true, PLAYER_ONE, PLAYER_TWO);

        assertTrue(repository.applyMatchFinished(event), "first delivery applies");
        assertFalse(repository.applyMatchFinished(event), "duplicate delivery is skipped");

        assertEquals(1, count("inbox_event"));
        assertEquals(1, count("match_projection"));
        assertNotNull(
                jdbc.sql("select applied_at from inbox_event where event_id = :id")
                        .param("id", EVENT_ID)
                        .query(Instant.class)
                        .single(),
                "processed event is marked applied");

        var row = jdbc.sql("""
                        select player_one_user_id, player_two_user_id, result, ranked,
                               source_version, projection_status
                        from match_projection where match_id = :id
                        """)
                .param("id", MATCH_ID)
                .query((rs, n) -> rs.getObject("player_one_user_id", UUID.class)
                        + "|" + rs.getObject("player_two_user_id", UUID.class)
                        + "|" + rs.getString("result")
                        + "|" + rs.getBoolean("ranked")
                        + "|" + rs.getLong("source_version")
                        + "|" + rs.getString("projection_status"))
                .single();
        assertEquals(PLAYER_ONE + "|" + PLAYER_TWO + "|PLAYER_ONE_WIN|true|1|ACTIVE", row);
    }

    @Test
    void d3Stat001ReprocessesTheSameMatchWhenANewerSourceVersionArrives() {
        repository.applyMatchFinished(finished(EVENT_ID, 1L, "PLAYER_ONE_WIN"));
        boolean applied = repository.applyMatchFinished(
                finished(otherEvent(2), 2L, "PLAYER_TWO_WIN"));

        assertTrue(applied);
        assertEquals(2, count("inbox_event"));
        assertEquals(1, count("match_projection"));
        assertEquals("PLAYER_TWO_WIN", resultOfMatch());
        assertEquals(2L, sourceVersionOfMatch());
    }

    @Test
    void d3Stat001DropsAnOutOfOrderReplayInsteadOfDowngradingTheProjection() {
        repository.applyMatchFinished(finished(EVENT_ID, 5L, "PLAYER_TWO_WIN"));
        boolean applied = repository.applyMatchFinished(
                finished(otherEvent(1), 1L, "PLAYER_ONE_WIN"));

        assertTrue(applied, "the stale event is still claimed in the inbox");
        assertEquals(2, count("inbox_event"));
        assertEquals("PLAYER_TWO_WIN", resultOfMatch());
        assertEquals(5L, sourceVersionOfMatch());
    }

    @Test
    void d3Stat001AppliesExactlyOnceUnderConcurrentDuplicateDelivery() throws Exception {
        var event = finished(EVENT_ID, 1L, "PLAYER_ONE_WIN");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> task = () -> {
                start.await();
                return repository.applyMatchFinished(event);
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            int applied = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, applied, "exactly one delivery applies");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("inbox_event"));
        assertEquals(1, count("match_projection"));
    }

    @Test
    void d3Stat001NeverPersistsSourceOrHiddenTestEvidence() {
        repository.applyMatchFinished(finished(EVENT_ID, 1L, "PLAYER_ONE_WIN"));

        assertEquals(
                0,
                jdbc.sql("""
                        select count(*) from match_projection
                        where score_summary is not null
                           or attack_summary is not null
                           or execution_summary is not null
                        """)
                        .query(Integer.class)
                        .single());
    }

    private static MatchFinishedProjection finished(UUID eventId, long version, String result) {
        return new MatchFinishedProjection(
                eventId, "match.finished", MATCH_ID, version,
                MATCH_ID, result, true, PLAYER_ONE, PLAYER_TWO);
    }

    private static UUID otherEvent(int suffix) {
        return UUID.fromString("66666666-6666-4666-8666-66666666666" + suffix);
    }

    private String resultOfMatch() {
        return jdbc.sql("select result from match_projection where match_id = :id")
                .param("id", MATCH_ID)
                .query(String.class)
                .single();
    }

    private long sourceVersionOfMatch() {
        return jdbc.sql("select source_version from match_projection where match_id = :id")
                .param("id", MATCH_ID)
                .query(Long.class)
                .single();
    }

    private int count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
    }
}
