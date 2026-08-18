package com.ddd.d3.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class IdentityDatabaseMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

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
    void d3Qlt001MigratesTheIdentityOwnedSchema() {
        Set<String> tables = Set.copyOf(jdbc.sql(
                        "select table_name from information_schema.tables where table_schema = 'public'")
                .query(String.class)
                .list());

        assertEquals(3, migrations);
        assertEquals(
                Set.of(
                        "flyway_schema_history",
                        "user_account",
                        "login_identity",
                        "refresh_session",
                        "refresh_session_legacy_normalization",
                        "outbox_event"),
                tables);
    }

    @Test
    void d3Stat001AddsMonotonicProfileVersionDefaultingToZero() {
        UUID userId = createUser("versioned");

        assertEquals(
                0L,
                jdbc.sql("select profile_version from user_account where id = :id")
                        .param("id", userId)
                        .query(Long.class)
                        .single());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                        "update user_account set profile_version = -1 where id = :id")
                .param("id", userId)
                .update());
    }

    @Test
    void d3Sec001KeepsRefreshRotationWithinOneUserAndOneLineage() {
        UUID firstUserId = createUser("first");
        UUID secondUserId = createUser("second");
        UUID parentSessionId = createRefreshSession(firstUserId, null);

        assertThrows(DataIntegrityViolationException.class, () -> createRefreshSession(secondUserId, parentSessionId));

        assertEquals(1, insertRefreshSession(UUID.randomUUID(), firstUserId, parentSessionId));
        assertThrows(DataIntegrityViolationException.class, () -> createRefreshSession(firstUserId, parentSessionId));
    }

    @Test
    void d3Sec001RejectsSelfRotationAndInvalidRevocationChronology() {
        UUID userId = createUser("chronology");
        UUID sessionId = UUID.randomUUID();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into refresh_session (
                            id, user_id, token_hash, expires_at, rotated_from_id, created_at
                        ) values (
                            :id, :userId, :tokenHash, now() + interval '1 hour', :id, now()
                        )
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("tokenHash", "self-" + sessionId)
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into refresh_session (
                            id, user_id, token_hash, expires_at, revoked_at, created_at
                        ) values (
                            :id, :userId, :tokenHash,
                            now() + interval '1 hour', now() - interval '1 hour', now()
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("tokenHash", "revoked-" + UUID.randomUUID())
                .update());
    }

    @Test
    void d3Qlt001UpgradesAnExistingRefreshLineageWithoutChangingV1() {
        migrateOnlyThrough("1");
        UUID firstUserId = createUser("upgrade-first");
        UUID secondUserId = createUser("upgrade-second");
        UUID parentSessionId = createRefreshSession(firstUserId, null);
        UUID childSessionId = createRefreshSession(firstUserId, parentSessionId);
        UUID duplicateChildSessionId = createRefreshSession(firstUserId, parentSessionId);
        UUID crossUserSessionId = createRefreshSession(secondUserId, parentSessionId);
        UUID selfSessionId = UUID.randomUUID();
        assertEquals(1, insertRefreshSession(selfSessionId, firstUserId, selfSessionId));
        UUID invalidChronologySessionId = createRefreshSession(firstUserId, null);
        assertEquals(1, jdbc.sql("""
                        update refresh_session
                        set revoked_at = created_at - interval '1 second'
                        where id = :sessionId
                        """)
                .param("sessionId", invalidChronologySessionId)
                .update());
        assertEquals(2, jdbc.sql("""
                        update refresh_session
                        set created_at = case
                                when id = :childSessionId then timestamptz '2026-08-13 00:00:01+00'
                                else timestamptz '2026-08-13 00:00:02+00'
                            end,
                            expires_at = timestamptz '2026-08-13 01:00:00+00'
                        where id in (:childSessionId, :duplicateChildSessionId)
                        """)
                .param("childSessionId", childSessionId)
                .param("duplicateChildSessionId", duplicateChildSessionId)
                .update());

        int applied = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;

        // V2 refresh-lineage normalization plus V3 profile_version apply forward over the V1-only schema.
        assertEquals(2, applied);
        assertEquals(parentSessionId, jdbc.sql("""
                        select rotated_from_id
                        from refresh_session
                        where id = :childSessionId
                        """)
                .param("childSessionId", childSessionId)
                .query(UUID.class)
                .single());
        assertEquals(4, jdbc.sql("select count(*) from refresh_session_legacy_normalization")
                .query(Integer.class)
                .single());
        assertEquals(childSessionId, jdbc.sql("""
                        select canonical_child_session_id
                        from refresh_session_legacy_normalization
                        where session_id = :sessionId
                        """)
                .param("sessionId", duplicateChildSessionId)
                .query(UUID.class)
                .single());
        assertEquals(3, jdbc.sql("""
                        select count(*)
                        from refresh_session
                        where id in (:duplicateId, :crossUserId, :selfId)
                          and rotated_from_id is null
                          and revoked_at >= created_at
                        """)
                .param("duplicateId", duplicateChildSessionId)
                .param("crossUserId", crossUserSessionId)
                .param("selfId", selfSessionId)
                .query(Integer.class)
                .single());
        assertEquals(1, jdbc.sql("""
                        select count(*)
                        from refresh_session
                        where id = :sessionId and revoked_at = created_at
                        """)
                .param("sessionId", invalidChronologySessionId)
                .query(Integer.class)
                .single());
        assertThrows(DataIntegrityViolationException.class, () -> createRefreshSession(secondUserId, parentSessionId));
    }

    @Test
    void d3Stat001BackfillsAProfileChangedOutboxRowForAccountsThatPredateTheProducer() {
        migrateOnlyThrough("2");
        UUID existing = createUser("legacy");

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertEquals(
                1L,
                jdbc.sql("select count(*) from outbox_event where aggregate_id = :id and event_type = 'user-profile.changed'")
                        .param("id", existing)
                        .query(Long.class)
                        .single());
        assertEquals(
                0L,
                jdbc.sql("select aggregate_version from outbox_event where aggregate_id = :id")
                        .param("id", existing)
                        .query(Long.class)
                        .single());
        assertEquals(
                existing.toString(),
                jdbc.sql("select payload->'data'->>'userId' from outbox_event where aggregate_id = :id")
                        .param("id", existing)
                        .query(String.class)
                        .single());
        assertEquals(
                "0",
                jdbc.sql("select payload->'data'->>'profileVersion' from outbox_event where aggregate_id = :id")
                        .param("id", existing)
                        .query(String.class)
                        .single());
        // The backfilled row is unpublished so the scheduled publisher relays it like any in-band event.
        assertEquals(
                1L,
                jdbc.sql("select count(*) from outbox_event where aggregate_id = :id and published_at is null")
                        .param("id", existing)
                        .query(Long.class)
                        .single());
    }

    private void migrateOnlyThrough(String version) {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target(version).load().migrate();
    }

    private UUID createUser(String prefix) {
        UUID userId = UUID.randomUUID();
        assertEquals(1, jdbc.sql("""
                        insert into user_account (
                            id, handle, email, display_name, status, created_at, updated_at
                        ) values (:id, :handle, :email, 'Fixture', 'ACTIVE', now(), now())
                        """)
                .param("id", userId)
                .param("handle", prefix + "-" + userId)
                .param("email", prefix + "-" + userId + "@example.test")
                .update());
        return userId;
    }

    private UUID createRefreshSession(UUID userId, UUID rotatedFromId) {
        UUID sessionId = UUID.randomUUID();
        insertRefreshSession(sessionId, userId, rotatedFromId);
        return sessionId;
    }

    private int insertRefreshSession(UUID sessionId, UUID userId, UUID rotatedFromId) {
        return jdbc.sql("""
                        insert into refresh_session (
                            id, user_id, token_hash, expires_at, rotated_from_id, created_at
                        ) values (
                            :id, :userId, :tokenHash, now() + interval '1 hour', :rotatedFromId, now()
                        )
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("tokenHash", "token-" + sessionId)
                .param("rotatedFromId", rotatedFromId, java.sql.Types.OTHER)
                .update();
    }
}
