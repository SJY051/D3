package com.ddd.d3.community.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.community.adapter.persistence.JdbcProfileRatingStore.RatingChangedEvent;
import java.time.Instant;
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
class JdbcProfileRatingStoreTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT = UUID.fromString("55555555-5555-4555-8555-555555555551");
    private static final Instant AT = Instant.parse("2026-08-16T00:00:00Z");

    private JdbcClient jdbc;
    private JdbcProfileRatingStore store;

    @BeforeEach
    void migrateAndSeed() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcProfileRatingStore(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        // A profile row exists (created by the identity projection, seeded directly here).
        jdbc.sql("""
                        insert into profile_projection (user_id, handle, identity_source_version, projected_at)
                        values (:userId, 'alice', 3, :at)
                        """)
                .param("userId", USER)
                .param("at", java.sql.Timestamp.from(AT))
                .update();
    }

    @Test
    void d3Stat001AppliesRatingOnceAndSkipsDuplicateDelivery() {
        var event = new RatingChangedEvent(EVENT, USER, 5L, USER, 1450, 60, "GOLD", AT);

        assertTrue(store.apply(event), "first delivery applies");
        assertFalse(store.apply(event), "duplicate delivery is skipped");

        assertEquals("1450|60|GOLD|5", jdbc.sql("""
                        select public_rating || '|' || rp || '|' || tier || '|' || rating_source_version
                        from profile_projection where user_id = :userId
                        """).param("userId", USER).query(String.class).single());
        assertEquals(1, jdbc.sql("select count(*) from inbox_event").query(Integer.class).single());
        assertNotNull(jdbc.sql("select applied_at from inbox_event where event_id = :id")
                .param("id", EVENT).query(Instant.class).single());
    }

    @Test
    void d3Stat001AppliesANewerRatingAndDropsAnOutOfOrderReplay() {
        store.apply(event(EVENT, 5L, 1450, 60, "GOLD"));

        assertTrue(store.apply(event(other(2), 6L, 1500, 70, "PLATINUM")), "newer version applies");
        assertTrue(store.apply(event(other(1), 4L, 1200, 40, "SILVER")), "stale event is still claimed");

        assertEquals("1500|70|PLATINUM|6", rating());
        assertEquals(3, jdbc.sql("select count(*) from inbox_event").query(Integer.class).single());
    }

    @Test
    void d3Stat001AppliesExactlyOnceUnderConcurrentDuplicateDelivery() throws Exception {
        var event = event(EVENT, 5L, 1450, 60, "GOLD");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> task = () -> {
                start.await();
                return store.apply(event);
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0), "exactly one delivery applies");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.sql("select count(*) from inbox_event").query(Integer.class).single());
    }

    @Test
    void d3Stat001PersistsARatingFirstRowBeforeTheIdentityProjectionExists() {
        UUID unknown = UUID.fromString("99999999-9999-4999-8999-999999999999");
        var event = new RatingChangedEvent(EVENT, unknown, 5L, unknown, 1450, 60, "GOLD", AT);

        assertTrue(store.apply(event));

        // rating is durably stored as a partial row; identity columns stay null until user-profile.changed
        assertEquals("1450|60|GOLD|5", jdbc.sql("""
                        select public_rating || '|' || rp || '|' || tier || '|' || rating_source_version
                        from profile_projection where user_id = :id
                        """).param("id", unknown).query(String.class).single());
        assertNull(jdbc.sql("select handle from profile_projection where user_id = :id")
                .param("id", unknown).query(String.class).optional().orElse(null));
        assertNull(jdbc.sql("select identity_source_version from profile_projection where user_id = :id")
                .param("id", unknown).query(Long.class).optional().orElse(null));
    }

    private static RatingChangedEvent event(UUID eventId, long version, int rating, int rp, String tier) {
        return new RatingChangedEvent(eventId, USER, version, USER, rating, rp, tier, AT);
    }

    private static UUID other(int suffix) {
        return UUID.fromString("66666666-6666-4666-8666-66666666666" + suffix);
    }

    private String rating() {
        return jdbc.sql("""
                        select public_rating || '|' || rp || '|' || tier || '|' || rating_source_version
                        from profile_projection where user_id = :userId
                        """).param("userId", USER).query(String.class).single();
    }
}
