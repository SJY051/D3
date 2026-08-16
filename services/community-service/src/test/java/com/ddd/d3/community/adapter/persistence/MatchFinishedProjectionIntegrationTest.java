package com.ddd.d3.community.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.community.application.CommunityService;
import com.ddd.d3.community.application.MatchFinishedProjectionService;
import com.ddd.d3.community.application.MatchFinishedProjectionService.MatchFinishedEvent;
import com.ddd.d3.community.domain.MarkdownPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MatchFinishedProjectionIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MATCH_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLAYER_ONE = UUID.fromString("33333333-3333-4333-8333-333333333331");
    private static final UUID PLAYER_TWO = UUID.fromString("33333333-3333-4333-8333-333333333332");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-16T01:00:00Z");

    private JdbcClient jdbc;
    private JdbcCommunityRepository communityRepository;
    private MatchFinishedProjectionService projections;

    @BeforeEach
    void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        communityRepository = new JdbcCommunityRepository(jdbc, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        var community = new CommunityService(
                communityRepository,
                new MarkdownPolicy(),
                UUID::randomUUID,
                2_000,
                20_000);
        projections = new MatchFinishedProjectionService(
                new JdbcMatchProjectionStore(jdbc),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                community::createResultPost);
    }

    @Test
    void d3Stat001ProjectsMatchFinishedExactlyOnceWithSeatOrderAndTraceability() {
        MatchFinishedEvent event = event(EVENT_ID, 7, "PLAYER_ONE_WIN", RECEIVED_AT);

        assertTrue(projections.receive(event));
        assertFalse(projections.receive(event));
        assertFalse(projections.receive(event(
                UUID.fromString("11111111-1111-4111-8111-111111111112"),
                7,
                "PLAYER_ONE_WIN",
                RECEIVED_AT.plusSeconds(1))));

        assertEquals(new InboxRow(EVENT_ID, MATCH_ID, 7, true), readInbox(EVENT_ID));
        assertEquals(1, count("inbox_event"));
        assertEquals(new ProjectionRow(
                MATCH_ID, PLAYER_ONE, PLAYER_TWO, "PLAYER_ONE_WIN", true, 7, "ACTIVE"),
                readProjection());
        assertEquals(1, count("post"));
        assertEquals("""
                Ranked match 22222222-2222-4222-8222-222222222222
                Player one: 33333333-3333-4333-8333-333333333331
                Player two: 33333333-3333-4333-8333-333333333332
                Result: PLAYER_ONE_WIN
                Ranked: true""", readResultPostMarkdown());
        assertEquals(
                MATCH_ID,
                communityRepository.publicFeed(Optional.empty(), 20).posts().getFirst().matchId());
        assertEquals(7, readResultPostSourceVersion());
    }

    @Test
    void d3Com001DoesNotLetALegacyLinkedPostBlockTheGeneratedResultPost() {
        jdbc.sql("""
                        insert into match_projection (
                            match_id, player_one_user_id, player_two_user_id, projection_status,
                            result, ranked, source_version, projected_at
                        ) values (
                            :matchId, :playerOne, :playerTwo, 'ACTIVE',
                            'DRAW', true, 6, :projectedAt
                        )
                        """)
                .param("matchId", MATCH_ID)
                .param("playerOne", PLAYER_ONE)
                .param("playerTwo", PLAYER_TWO)
                .param("projectedAt", java.sql.Timestamp.from(RECEIVED_AT.minusSeconds(1)))
                .update();
        jdbc.sql("""
                        insert into post (
                            id, author_user_id, visibility, prose_markdown, rendered_html,
                            prose_character_count, match_projection_id, created_at, updated_at
                        ) values (
                            :id, :authorUserId, 'PUBLIC', 'Legacy linked post',
                            '<p>Legacy linked post</p>', 18, :matchId, :createdAt, :createdAt
                        )
                        """)
                .param("id", UUID.fromString("44444444-4444-4444-8444-444444444445"))
                .param("authorUserId", PLAYER_TWO)
                .param("matchId", MATCH_ID)
                .param("createdAt", java.sql.Timestamp.from(RECEIVED_AT.minusSeconds(1)))
                .update();

        assertTrue(projections.receive(event(EVENT_ID, 7, "PLAYER_ONE_WIN", RECEIVED_AT)));

        assertEquals(2, count("post"));
        assertEquals(1, countGeneratedResultPosts());
        assertEquals(7, readResultPostSourceVersion());
    }

    @Test
    void d3Stat001DeduplicatesConcurrentDelivery() throws Exception {
        MatchFinishedEvent event = event(EVENT_ID, 7, "DRAW", RECEIVED_AT);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return projections.receive(event);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return projections.receive(event);
            });
            ready.await();
            start.countDown();

            assertEquals(1, List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        }

        assertEquals(1, count("inbox_event"));
        assertEquals(1, count("match_projection"));
        assertEquals(7, readProjection().sourceVersion());
        assertEquals(1, count("post"));
    }

    @Test
    void d3Stat001DoesNotRegressOnOutOfOrderVersions() {
        MatchFinishedEvent newer = event(
                UUID.fromString("11111111-1111-4111-8111-111111111119"),
                9,
                "PLAYER_TWO_WIN",
                RECEIVED_AT.plusSeconds(2));
        MatchFinishedEvent stale = event(
                UUID.fromString("11111111-1111-4111-8111-111111111118"),
                8,
                "PLAYER_ONE_WIN",
                RECEIVED_AT.plusSeconds(3));

        assertTrue(projections.receive(newer));
        assertTrue(projections.receive(stale));

        assertEquals(2, count("inbox_event"));
        assertEquals(new ProjectionRow(
                MATCH_ID, PLAYER_ONE, PLAYER_TWO, "PLAYER_TWO_WIN", true, 9, "ACTIVE"),
                readProjection());
        assertEquals(1, count("post"));
        assertTrue(readResultPostMarkdown().contains("Result: PLAYER_TWO_WIN"));
    }

    @Test
    void d3Com001KeepsTheFirstGeneratedResultPostAsAnImmutableAuditRecord() {
        assertTrue(projections.receive(event(EVENT_ID, 7, "PLAYER_ONE_WIN", RECEIVED_AT)));
        assertTrue(projections.receive(event(
                UUID.fromString("11111111-1111-4111-8111-111111111117"),
                8,
                "PLAYER_TWO_WIN",
                RECEIVED_AT.plusSeconds(1))));

        assertEquals("PLAYER_TWO_WIN", readProjection().result());
        assertEquals(8, readProjection().sourceVersion());
        assertEquals(1, countGeneratedResultPosts());
        assertEquals(7, readResultPostSourceVersion());
        assertTrue(readResultPostMarkdown().contains("Result: PLAYER_ONE_WIN"));
    }

    @Test
    void d3Stat001RebuildsAQuarantinedProjectionFromAnAuthoritativeReplay() {
        jdbc.sql("""
                        insert into match_projection (
                            match_id, player_one_user_id, player_two_user_id, projection_status,
                            result, ranked, source_version, projected_at
                        ) values (:matchId, null, null, 'REBUILD_REQUIRED', 'DRAW', true, 7, :projectedAt)
                        """)
                .param("matchId", MATCH_ID)
                .param("projectedAt", java.sql.Timestamp.from(RECEIVED_AT.minusSeconds(1)))
                .update();
        jdbc.sql("""
                        insert into match_projection_rebuild_queue (
                            match_id, legacy_player_ids, result, ranked, source_version,
                            projected_at, quarantine_reason
                        ) values (
                            :matchId, cast('["not-a-uuid", "still-not-a-uuid"]' as jsonb),
                            'DRAW', true, 7, :projectedAt, 'INVALID_LEGACY_PLAYER_IDS'
                        )
                        """)
                .param("matchId", MATCH_ID)
                .param("projectedAt", java.sql.Timestamp.from(RECEIVED_AT.minusSeconds(1)))
                .update();
        jdbc.sql("""
                        insert into post (
                            id, author_user_id, visibility, prose_markdown, rendered_html,
                            prose_character_count, match_projection_id, post_kind,
                            match_source_version, created_at, updated_at
                        ) values (
                            :id, :authorUserId, 'PUBLIC', 'Original quarantined record',
                            '<p>Original quarantined record</p>', 27, :matchId,
                            'MATCH_RESULT', 7, :createdAt, :createdAt
                        )
                        """)
                .param("id", UUID.fromString("44444444-4444-4444-8444-444444444444"))
                .param("authorUserId", PLAYER_ONE)
                .param("matchId", MATCH_ID)
                .param("createdAt", java.sql.Timestamp.from(RECEIVED_AT.minusSeconds(1)))
                .update();

        assertTrue(projections.receive(event(EVENT_ID, 7, "PLAYER_ONE_WIN", RECEIVED_AT)));

        assertEquals(new ProjectionRow(
                MATCH_ID, PLAYER_ONE, PLAYER_TWO, "PLAYER_ONE_WIN", true, 7, "ACTIVE"),
                readProjection());
        assertEquals(0, count("match_projection_rebuild_queue"));
        assertEquals(1, count("post"));
        assertEquals("Original quarantined record", readResultPostMarkdown());
    }

    @Test
    void d3Stat001RollsBackInboxAndProjectionWhenApplicationFails() {
        jdbc.sql("""
                        create function reject_inbox_application() returns trigger
                        language plpgsql as $$
                        begin
                            raise exception 'forced inbox application failure';
                        end;
                        $$
                        """).update();
        jdbc.sql("""
                        create trigger reject_inbox_application
                        before update of applied_at on inbox_event
                        for each row execute function reject_inbox_application()
                        """).update();

        assertThrows(
                DataAccessException.class,
                () -> projections.receive(event(EVENT_ID, 7, "DRAW", RECEIVED_AT)));

        assertEquals(0, count("inbox_event"));
        assertEquals(0, count("match_projection"));
        assertEquals(0, count("post"));
    }

    @Test
    void d3Stat001RollsBackInboxAndProjectionWhenResultPostCreationFails() {
        jdbc.sql("""
                        create function reject_result_post() returns trigger
                        language plpgsql as $$
                        begin
                            raise exception 'forced result post failure';
                        end;
                        $$
                        """).update();
        jdbc.sql("""
                        create trigger reject_result_post
                        before insert on post
                        for each row execute function reject_result_post()
                        """).update();

        assertThrows(
                DataAccessException.class,
                () -> projections.receive(event(EVENT_ID, 7, "PLAYER_ONE_WIN", RECEIVED_AT)));

        assertEquals(0, count("inbox_event"));
        assertEquals(0, count("match_projection"));
        assertEquals(0, count("post"));
    }

    @Test
    void d3Com001DoesNotAutoPostUnrankedOrVoidedRecords() {
        MatchFinishedEvent unranked = event(
                UUID.fromString("11111111-1111-4111-8111-111111111120"),
                10,
                "PLAYER_ONE_WIN",
                false,
                RECEIVED_AT.plusSeconds(10));
        MatchFinishedEvent voided = event(
                UUID.fromString("11111111-1111-4111-8111-111111111121"),
                11,
                "VOIDED",
                true,
                RECEIVED_AT.plusSeconds(11));

        assertTrue(projections.receive(unranked));
        assertTrue(projections.receive(voided));

        assertEquals(0, count("post"));
        assertEquals("VOIDED", readProjection().result());
        assertEquals(11, readProjection().sourceVersion());
    }

    private MatchFinishedEvent event(UUID eventId, long version, String result, Instant receivedAt) {
        return event(eventId, version, result, true, receivedAt);
    }

    private MatchFinishedEvent event(
            UUID eventId, long version, String result, boolean ranked, Instant receivedAt) {
        return new MatchFinishedEvent(
                eventId,
                MATCH_ID,
                version,
                MATCH_ID,
                result,
                ranked,
                List.of(PLAYER_ONE, PLAYER_TWO),
                receivedAt);
    }

    private InboxRow readInbox(UUID eventId) {
        return jdbc.sql("""
                        select event_id, aggregate_id, aggregate_version, applied_at is not null as applied
                        from inbox_event
                        where event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query((resultSet, rowNumber) -> new InboxRow(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getObject("aggregate_id", UUID.class),
                        resultSet.getLong("aggregate_version"),
                        resultSet.getBoolean("applied")))
                .single();
    }

    private ProjectionRow readProjection() {
        return jdbc.sql("""
                        select match_id, player_one_user_id, player_two_user_id, result,
                               ranked, source_version, projection_status
                        from match_projection
                        where match_id = :matchId
                        """)
                .param("matchId", MATCH_ID)
                .query((resultSet, rowNumber) -> new ProjectionRow(
                        resultSet.getObject("match_id", UUID.class),
                        resultSet.getObject("player_one_user_id", UUID.class),
                        resultSet.getObject("player_two_user_id", UUID.class),
                        resultSet.getString("result"),
                        resultSet.getBoolean("ranked"),
                        resultSet.getLong("source_version"),
                        resultSet.getString("projection_status")))
                .single();
    }

    private String readResultPostMarkdown() {
        return jdbc.sql("""
                        select prose_markdown
                        from post
                        where match_projection_id = :matchId
                          and post_kind = 'MATCH_RESULT'
                        """)
                .param("matchId", MATCH_ID)
                .query(String.class)
                .single();
    }

    private long readResultPostSourceVersion() {
        return jdbc.sql("""
                        select match_source_version
                        from post
                        where match_projection_id = :matchId
                          and post_kind = 'MATCH_RESULT'
                        """)
                .param("matchId", MATCH_ID)
                .query(Long.class)
                .single();
    }

    private long countGeneratedResultPosts() {
        return jdbc.sql("select count(*) from post where post_kind = 'MATCH_RESULT'")
                .query(Long.class)
                .single();
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private record InboxRow(UUID eventId, UUID aggregateId, long aggregateVersion, boolean applied) {}

    private record ProjectionRow(
            UUID matchId,
            UUID playerOneId,
            UUID playerTwoId,
            String result,
            boolean ranked,
            long sourceVersion,
            String status) {}
}
