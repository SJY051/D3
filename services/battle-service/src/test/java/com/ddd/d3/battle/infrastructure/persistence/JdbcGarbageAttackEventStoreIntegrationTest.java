package com.ddd.d3.battle.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ddd.d3.battle.domain.attack.GarbageAttackExchange;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class JdbcGarbageAttackEventStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    DriverManagerDataSource dataSource;
    JdbcClient jdbc;
    JdbcGarbageAttackEventStore store;

    @BeforeEach
    void migrateSchema() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcGarbageAttackEventStore(dataSource, new ObjectMapper());
    }

    @Test
    void freshSchemaSupportsVersionedAttackPayloadsAndAttackReceipts() {
        assertEquals(1, jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'attack_event'
                          and column_name = 'payload_version'
                        """)
                .query(Integer.class)
                .single());

        MatchFixture match = createRunningMatch();
        assertEquals(1, insertReceipt(match, "ATTACK_LAUNCH"));
        assertEquals(1, insertReceipt(match, "ATTACK_BLOCK"));
        assertEquals(1, insertReceipt(match, "ATTACK_REFLECT"));
        assertThrows(RuntimeException.class, () -> insertReceipt(match, "ATTACK_UNKNOWN"));
    }

    @Test
    void upgradeArchivesLegacyAttackRowsOutsideTheReplayableEventStream() {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target("7").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        MatchFixture match = createRunningMatch();
        UUID eventId = UUID.randomUUID();
        assertEquals(1, jdbc.sql("""
                        insert into attack_event (
                            id, match_id, sequence, actor_user_id, target_user_id,
                            attack_type, resolution, energy_cost, occurred_at
                        ) values (
                            :id, :matchId, 1, :actorId, :targetId,
                            'LEGACY_ATTACK', 'LEGACY_RESOLUTION', 4, :occurredAt
                        )
                        """)
                .param("id", eventId)
                .param("matchId", match.matchId())
                .param("actorId", match.playerOneId())
                .param("targetId", match.playerTwoId())
                .param("occurredAt", Timestamp.from(Instant.parse("2026-08-15T00:00:00Z")))
                .update());

        assertEquals(4, Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted);

        assertEquals(0, jdbc.sql("select count(*) from attack_event where id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single());
        assertEquals("LEGACY_ATTACK", jdbc.sql("""
                        select attack_type
                        from attack_event_legacy
                        where id = :id
                        """)
                .param("id", eventId)
                .query(String.class)
                .single());
        assertEquals(1, jdbc.sql("select count(*) from attack_event_legacy where id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single());
        assertEquals(List.of(), store.findByMatchId(match.matchId()));
    }

    @Test
    void eventsRoundTripCompletelyInSequenceIncludingEnergyWithoutAttackState() {
        MatchFixture match = createRunningMatch();
        Instant occurredAt = Instant.parse("2026-08-15T01:02:03.456Z");
        GarbageAttackExchange.AttackEvent energy = new GarbageAttackExchange.AttackEvent(
                1,
                GarbageAttackExchange.EventType.ENERGY_GRANTED,
                match.playerOneId().toString(),
                "progress:submission-1",
                20,
                20,
                null,
                occurredAt);
        GarbageAttackExchange.AttackState warning = new GarbageAttackExchange.AttackState(
                "attack-1",
                match.playerOneId().toString(),
                match.playerTwoId().toString(),
                false,
                GarbageAttackExchange.Phase.WARNING,
                occurredAt.plusSeconds(2),
                null,
                923L,
                null);
        GarbageAttackExchange.AttackEvent warned = new GarbageAttackExchange.AttackEvent(
                2,
                GarbageAttackExchange.EventType.ATTACK_WARNED,
                match.playerOneId().toString(),
                "attack-1",
                -40,
                0,
                warning,
                occurredAt.plusMillis(1));

        store.append(match.matchId(), List.of(energy, warned));

        assertEquals(List.of(energy, warned), store.findByMatchId(match.matchId()));
        List<UUID> diagnosticPlayers = jdbc.sql("""
                        select actor_user_id
                        from attack_event
                        where match_id = :matchId and sequence = 1
                        union all
                        select target_user_id
                        from attack_event
                        where match_id = :matchId and sequence = 1
                        order by 1
                        """)
                .param("matchId", match.matchId())
                .query(UUID.class)
                .list();
        assertNotEquals(diagnosticPlayers.get(0), diagnosticPlayers.get(1));
    }

    @Test
    void appendRejectsGapsAndPlayersOutsideTheMatchBeforeWriting() {
        MatchFixture match = createRunningMatch();
        GarbageAttackExchange.AttackEvent gap = energyEvent(2, match.playerOneId());
        GarbageAttackExchange.AttackEvent outsider = energyEvent(1, UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () -> store.append(match.matchId(), List.of(gap)));
        assertThrows(IllegalArgumentException.class, () -> store.append(match.matchId(), List.of(outsider)));
        assertEquals(0, jdbc.sql("select count(*) from attack_event where match_id = :matchId")
                .param("matchId", match.matchId())
                .query(Integer.class)
                .single());
        assertThrows(RuntimeException.class, () -> jdbc.sql("""
                        insert into attack_event (
                            id, match_id, sequence, actor_user_id, target_user_id,
                            attack_type, resolution, energy_cost, occurred_at,
                            payload_version, event_payload
                        ) values (
                            :id, :matchId, 2, :actorId, :targetId,
                            'ENERGY_GRANTED', 'ENERGY_GRANTED', 0, now(), 1,
                            jsonb_build_object(
                                'sequence', 2,
                                'type', 'ENERGY_GRANTED',
                                'playerId', cast(:actorId as text),
                                'key', 'progress:x',
                                'energyDelta', 1,
                                'energyAfter', 1,
                                'occurredAt', '2026-08-15T00:00:00Z'
                            )
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("matchId", match.matchId())
                .param("actorId", match.playerOneId())
                .param("targetId", match.playerTwoId())
                .update());
    }

    @Test
    void lockSerializesWritersOnTheAuthoritativeMatchRow() throws Exception {
        MatchFixture match = createRunningMatch();
        TransactionTemplate firstTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TransactionTemplate secondTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> firstTransaction.executeWithoutResult(status -> {
                store.lock(match.matchId());
                locked.countDown();
                await(release);
            }));
            locked.await(5, TimeUnit.SECONDS);
            Future<?> second = executor.submit(() -> secondTransaction.executeWithoutResult(status -> store.lock(match.matchId())));

            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    private MatchFixture createRunningMatch() {
        UUID problemId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID playerOneId = UUID.randomUUID();
        UUID playerTwoId = UUID.randomUUID();
        jdbc.sql("""
                        insert into problem (id, slug, version, title, difficulty, active, created_at, updated_at)
                        values (:id, :slug, 1, 'Fixture', 'EASY', true, now(), now())
                        """)
                .param("id", problemId)
                .param("slug", "fixture-" + problemId)
                .update();
        jdbc.sql("""
                        insert into match (
                            id, problem_id, ranked, status, result,
                            server_started_at, deadline_at, created_at
                        ) values (
                            :id, :problemId, true, 'RUNNING', null,
                            now() - interval '1 minute', now() + interval '9 minutes',
                            now() - interval '2 minutes'
                        )
                        """)
                .param("id", matchId)
                .param("problemId", problemId)
                .update();
        addPlayer(matchId, playerOneId, 1);
        addPlayer(matchId, playerTwoId, 2);
        return new MatchFixture(matchId, playerOneId, playerTwoId);
    }

    private void addPlayer(UUID matchId, UUID playerId, int seat) {
        jdbc.sql("""
                        insert into match_player (
                            match_id, user_id, seat, language_key, ready,
                            connection_state, connection_generation
                        ) values (:matchId, :playerId, :seat, 'java', true, 'CONNECTED', 1)
                        """)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .param("seat", seat)
                .update();
    }

    private int insertReceipt(MatchFixture match, String type) {
        return jdbc.sql("""
                        insert into match_command_receipt (
                            command_id, match_id, player_user_id, command_type,
                            payload_fingerprint, aggregate_version, accepted_at
                        ) values (
                            :commandId, :matchId, :playerId, :type,
                            'fingerprint', 0, now()
                        )
                        """)
                .param("commandId", UUID.randomUUID())
                .param("matchId", match.matchId())
                .param("playerId", match.playerOneId())
                .param("type", type)
                .update();
    }

    private static GarbageAttackExchange.AttackEvent energyEvent(long sequence, UUID playerId) {
        return new GarbageAttackExchange.AttackEvent(
                sequence,
                GarbageAttackExchange.EventType.ENERGY_GRANTED,
                playerId.toString(),
                "progress:" + sequence,
                20,
                20,
                null,
                Instant.parse("2026-08-15T00:00:00Z"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("lock release timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("lock wait interrupted", exception);
        }
    }

    private record MatchFixture(UUID matchId, UUID playerOneId, UUID playerTwoId) {}
}
