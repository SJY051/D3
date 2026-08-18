package com.ddd.d3.community.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.community.adapter.persistence.JdbcProfileIdentityStore.UserProfileChangedEvent;
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
class JdbcProfileIdentityStoreTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_USER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT = UUID.fromString("55555555-5555-4555-8555-555555555551");
    private static final Instant AT = Instant.parse("2026-08-16T00:00:00Z");

    private JdbcClient jdbc;
    private JdbcProfileIdentityStore store;

    @BeforeEach
    void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcProfileIdentityStore(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void d3Stat001AppliesHandleOnceAndSkipsDuplicateDelivery() {
        var event = new UserProfileChangedEvent(EVENT, USER, 0L, USER, "alice", AT);

        assertTrue(store.apply(event), "first delivery applies");
        assertFalse(store.apply(event), "duplicate delivery is skipped");

        assertEquals("alice|0", identity(USER));
        assertEquals(1, jdbc.sql("select count(*) from inbox_event").query(Integer.class).single());
        assertNotNull(jdbc.sql("select applied_at from inbox_event where event_id = :id")
                .param("id", EVENT).query(Instant.class).single());
    }

    @Test
    void d3Stat001AppliesANewerHandleAndDropsAnOutOfOrderReplay() {
        store.apply(event(EVENT, 0L, "alice"));

        assertTrue(store.apply(event(other(2), 2L, "alice-renamed")), "newer version applies");
        assertTrue(store.apply(event(other(1), 1L, "stale-name")), "stale event is still claimed");

        assertEquals("alice-renamed|2", identity(USER));
        assertEquals(3, jdbc.sql("select count(*) from inbox_event").query(Integer.class).single());
    }

    @Test
    void d3Stat001FillsHandleOnARatingFirstRowWithoutTouchingRating() {
        // Battle's rating.changed projection created the row first with null identity columns.
        jdbc.sql("""
                        insert into profile_projection (user_id, public_rating, rp, tier, rating_source_version, projected_at)
                        values (:userId, 1450, 60, 'GOLD', 5, :at)
                        """)
                .param("userId", USER)
                .param("at", java.sql.Timestamp.from(AT))
                .update();

        assertTrue(store.apply(event(EVENT, 0L, "alice")));

        assertEquals("alice|0", identity(USER));
        // Rating columns owned by the other producer are untouched.
        assertEquals("1450|60|GOLD|5", jdbc.sql("""
                        select public_rating || '|' || rp || '|' || tier || '|' || rating_source_version
                        from profile_projection where user_id = :userId
                        """).param("userId", USER).query(String.class).single());
    }

    @Test
    void d3Stat001AppliesExactlyOnceUnderConcurrentDuplicateDelivery() throws Exception {
        var event = event(EVENT, 0L, "alice");
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
    void d3Stat001PersistsAnIdentityFirstRowBeforeAnyRatingExists() {
        assertTrue(store.apply(event(EVENT, 0L, "alice")));

        assertEquals("alice|0", identity(USER));
        // rating columns stay null until rating.changed projects them
        assertNull(jdbc.sql("select public_rating from profile_projection where user_id = :id")
                .param("id", USER).query(Integer.class).optional().orElse(null));
        assertNull(jdbc.sql("select rating_source_version from profile_projection where user_id = :id")
                .param("id", USER).query(Long.class).optional().orElse(null));
    }

    @Test
    void d3Stat001DoesNotRejectAReusedHandleAcrossProjectedUsers() {
        assertTrue(store.apply(event(EVENT, USER, 0L, "alice")));
        assertTrue(store.apply(event(other(2), OTHER_USER, 0L, "alice")));

        assertEquals(2, jdbc.sql("select count(*) from profile_projection where handle = 'alice'")
                .query(Integer.class)
                .single());
    }

    private static UserProfileChangedEvent event(UUID eventId, long version, String handle) {
        return event(eventId, USER, version, handle);
    }

    private static UserProfileChangedEvent event(UUID eventId, UUID userId, long version, String handle) {
        return new UserProfileChangedEvent(eventId, userId, version, userId, handle, AT);
    }

    private static UUID other(int suffix) {
        return UUID.fromString("66666666-6666-4666-8666-66666666666" + suffix);
    }

    private String identity(UUID userId) {
        return jdbc.sql("""
                        select handle || '|' || identity_source_version
                        from profile_projection where user_id = :userId
                        """).param("userId", userId).query(String.class).single();
    }
}
