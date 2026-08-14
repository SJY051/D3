package com.ddd.d3.community;

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
class CommunityDatabaseMigrationTest {

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
    void d3Qlt001MigratesTheCommunityOwnedSchema() {
        Set<String> tables = Set.copyOf(jdbc.sql(
                        "select table_name from information_schema.tables where table_schema = 'public'")
                .query(String.class)
                .list());

        assertEquals(2, migrations);
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
                        "match_projection_rebuild_queue",
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
        assertEquals(new PlayerSeats(playerOneId, playerTwoId), readPlayerSeats(matchId));
    }

    @Test
    void d3Qlt001UpgradesAndPreservesAnExistingSeatOrderedProjection() {
        migrateOnlyThrough("1");
        UUID matchId = UUID.randomUUID();
        UUID playerOneId = UUID.randomUUID();
        UUID playerTwoId = UUID.randomUUID();
        assertEquals(1, jdbc.sql("""
                        insert into match_projection (
                            match_id, player_ids, result, ranked, source_version, projected_at
                        ) values (
                            :matchId, cast(:playerIds as jsonb), 'DRAW', true, 1, now()
                        )
                        """)
                .param("matchId", matchId)
                .param("playerIds", "[\"" + playerOneId + "\",\"" + playerTwoId + "\"]")
                .update());

        int applied = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;

        assertEquals(1, applied);
        assertEquals(new PlayerSeats(playerOneId, playerTwoId), readPlayerSeats(matchId));
        assertEquals(0, jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'match_projection'
                          and column_name = 'player_ids'
                        """)
                .query(Integer.class)
                .single());
    }

    @Test
    void d3Qlt001QuarantinesLegacyProjectionsThatCannotBeTyped() {
        migrateOnlyThrough("1");
        UUID nullPlayers = insertLegacyProjection("[null,null]");
        UUID referencingPost = UUID.randomUUID();
        assertEquals(1, jdbc.sql("""
                        insert into post (
                            id, author_user_id, visibility, prose_markdown,
                            match_projection_id, created_at, updated_at
                        ) values (
                            :id, :authorId, 'PUBLIC', 'legacy result',
                            :matchId, now(), now()
                        )
                        """)
                .param("id", referencingPost)
                .param("authorId", UUID.randomUUID())
                .param("matchId", nullPlayers)
                .update());
        insertLegacyProjection("[{},{}]");
        insertLegacyProjection("[\"not-a-uuid\",\"11111111-1111-4111-8111-111111111111\"]");
        UUID duplicatePlayer = UUID.fromString("22222222-2222-4222-8222-222222222222");
        insertLegacyProjection("[\"" + duplicatePlayer + "\",\"" + duplicatePlayer + "\"]");

        int applied = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;

        assertEquals(1, applied);
        assertEquals(4, jdbc.sql("""
                        select count(*)
                        from match_projection
                        where projection_status = 'REBUILD_REQUIRED'
                          and player_one_user_id is null
                          and player_two_user_id is null
                        """)
                .query(Integer.class)
                .single());
        assertEquals(4, jdbc.sql("select count(*) from match_projection_rebuild_queue")
                .query(Integer.class)
                .single());
        assertEquals("[null, null]", jdbc.sql("""
                        select legacy_player_ids::text
                        from match_projection_rebuild_queue
                        where match_id = :matchId
                        """)
                .param("matchId", nullPlayers)
                .query(String.class)
                .single());
        assertEquals(nullPlayers, jdbc.sql("""
                        select match_projection_id
                        from post
                        where id = :postId
                        """)
                .param("postId", referencingPost)
                .query(UUID.class)
                .single());
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

    private PlayerSeats readPlayerSeats(UUID matchId) {
        return jdbc.sql("""
                        select player_one_user_id, player_two_user_id
                        from match_projection
                        where match_id = :matchId
                        """)
                .param("matchId", matchId)
                .query((resultSet, rowNumber) -> new PlayerSeats(
                        resultSet.getObject("player_one_user_id", UUID.class),
                        resultSet.getObject("player_two_user_id", UUID.class)))
                .single();
    }

    private UUID insertLegacyProjection(String playerIds) {
        UUID matchId = UUID.randomUUID();
        assertEquals(1, jdbc.sql("""
                        insert into match_projection (
                            match_id, player_ids, result, ranked, source_version, projected_at
                        ) values (
                            :matchId, cast(:playerIds as jsonb), 'DRAW', true, 1, now()
                        )
                        """)
                .param("matchId", matchId)
                .param("playerIds", playerIds)
                .update());
        return matchId;
    }

    private void migrateOnlyThrough(String version) {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target(version).load().migrate();
    }

    private record PlayerSeats(UUID playerOneId, UUID playerTwoId) {}
}
