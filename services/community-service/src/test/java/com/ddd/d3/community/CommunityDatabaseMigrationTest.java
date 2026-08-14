package com.ddd.d3.community;

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
class CommunityDatabaseMigrationTest {

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
    void d3Qlt001MigratesTheCommunityOwnedSchema() {
        Set<String> tables = Set.copyOf(jdbc.sql(
                        "select table_name from information_schema.tables where table_schema = 'public'")
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
    }

    @Test
    void d3Stat001StoresTwoDistinctTypedMatchParticipantsInSeatOrder() {
        UUID playerId = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class, () -> insertMatchProjection(playerId, playerId));

        UUID playerOneId = UUID.randomUUID();
        UUID playerTwoId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        assertEquals(1, insertMatchProjection(matchId, playerOneId, playerTwoId));

        PlayerSeats persistedPlayers = jdbc.sql("""
                        select player_one_user_id, player_two_user_id
                        from match_projection
                        where match_id = :matchId
                        """)
                .param("matchId", matchId)
                .query((resultSet, rowNumber) -> new PlayerSeats(
                        resultSet.getObject("player_one_user_id", UUID.class),
                        resultSet.getObject("player_two_user_id", UUID.class)))
                .single();
        assertEquals(new PlayerSeats(playerOneId, playerTwoId), persistedPlayers);
    }

    private int insertMatchProjection(UUID playerOneId, UUID playerTwoId) {
        return insertMatchProjection(UUID.randomUUID(), playerOneId, playerTwoId);
    }

    private int insertMatchProjection(UUID matchId, UUID playerOneId, UUID playerTwoId) {
        return jdbc.sql("""
                        insert into match_projection (
                            match_id, player_one_user_id, player_two_user_id,
                            result, ranked, source_version, projected_at
                        ) values (
                            :matchId, :playerOneId, :playerTwoId,
                            'DRAW', true, 1, now()
                        )
                        """)
                .param("matchId", matchId)
                .param("playerOneId", playerOneId)
                .param("playerTwoId", playerTwoId)
                .update();
    }

    private record PlayerSeats(UUID playerOneId, UUID playerTwoId) {}
}
