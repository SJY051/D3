package com.ddd.d3.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CommunityDatabaseMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    @Test
    void d3Qlt001MigratesTheCommunityOwnedSchema() {
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
                        "post",
                        "circle",
                        "circle_member",
                        "follow",
                        "comment",
                        "post_like",
                        "profile_projection",
                        "match_projection",
                        "inbox_event"),
                tables);

        var jdbc = JdbcClient.create(dataSource);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match_projection (
                            match_id, player_ids, result, ranked, source_version, projected_at
                        ) values (:matchId, cast(:playerIds as jsonb), 'DRAW', true, 1, now())
                        """)
                .param("matchId", UUID.randomUUID())
                .param("playerIds", "[null, null]")
                .update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        insert into match_projection (
                            match_id, player_ids, result, ranked, source_version, projected_at
                        ) values (:matchId, cast(:playerIds as jsonb), 'DRAW', true, 1, now())
                        """)
                .param("matchId", UUID.randomUUID())
                .param("playerIds", "[{}, {}]")
                .update());

        UUID playerOneId = UUID.randomUUID();
        UUID playerTwoId = UUID.randomUUID();
        assertEquals(1, jdbc.sql("""
                        insert into match_projection (
                            match_id, player_ids, result, ranked, source_version, projected_at
                        ) values (:matchId, cast(:playerIds as jsonb), 'DRAW', true, 1, now())
                        """)
                .param("matchId", UUID.randomUUID())
                .param("playerIds", "[\"" + playerOneId + "\", \"" + playerTwoId + "\"]")
                .update());
    }
}
