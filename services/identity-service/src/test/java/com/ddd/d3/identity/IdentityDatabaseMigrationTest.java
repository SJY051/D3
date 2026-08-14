package com.ddd.d3.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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
    void d3Qlt001MigratesTheIdentityOwnedSchema() {
        Set<String> tables = Set.copyOf(jdbc.sql(
                        "select table_name from information_schema.tables where table_schema = 'public'")
                .query(String.class)
                .list());

        assertEquals(1, migrations);
        assertEquals(
                Set.of("flyway_schema_history", "user_account", "login_identity", "refresh_session", "outbox_event"),
                tables);
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
