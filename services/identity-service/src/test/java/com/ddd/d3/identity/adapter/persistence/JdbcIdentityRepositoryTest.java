package com.ddd.d3.identity.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.identity.application.DuplicateAccountException;
import com.ddd.d3.identity.application.IdentityService;
import com.ddd.d3.identity.application.RefreshTokenRejectedException;
import com.ddd.d3.identity.application.SessionToken;
import com.ddd.d3.identity.domain.Account;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class JdbcIdentityRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
    private static final String PASSWORD = "correct horse battery staple";

    private JdbcClient jdbc;
    private JdbcIdentityRepository repository;
    private IdentityService service;

    @BeforeEach
    void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcIdentityRepository(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
        service = new IdentityService(
                repository,
                new BCryptPasswordEncoder(),
                CLOCK,
                UUID::randomUUID,
                () -> UUID.randomUUID().toString());
    }

    @Test
    void d3Id001PersistsAccountAndRotatesRefreshSessionOnPostgres() {
        service.register("dev@d3.dev", "dev", "Dev", PASSWORD);
        SessionToken first = service.login("dev@d3.dev", PASSWORD);

        SessionToken rotated = service.refresh(first.refreshToken());

        assertNotEquals(first.refreshToken(), rotated.refreshToken());
        assertEquals(2, sessionCount());
        // Reusing the rotated-away token trips the breach response and revokes the whole family.
        assertThrows(RefreshTokenRejectedException.class, () -> service.refresh(first.refreshToken()));
        assertThrows(RefreshTokenRejectedException.class, () -> service.refresh(rotated.refreshToken()));
        assertEquals(0, activeSessionCount());
    }

    @Test
    void d3Id001MapsAUniqueEmailViolationToADomainConflict() {
        repository.saveAccount(account("dev@d3.dev", "dev"));

        assertThrows(DuplicateAccountException.class, () -> repository.saveAccount(account("dev@d3.dev", "other")));
    }

    @Test
    void d3Sec001ConcurrentRefreshOfOneTokenIssuesExactlyOneNewSession() throws Exception {
        service.register("dev@d3.dev", "dev", "Dev", PASSWORD);
        SessionToken issued = service.login("dev@d3.dev", PASSWORD);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> refresh = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                service.refresh(issued.refreshToken());
                accepted.incrementAndGet();
            } catch (RefreshTokenRejectedException rejectedException) {
                rejected.incrementAndGet();
            }
            return null;
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> one = executor.submit(refresh);
            Future<Void> two = executor.submit(refresh);
            start.countDown();
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        }

        assertEquals(1, accepted.get());
        assertEquals(1, rejected.get());
        // The original token is spent and only the single winning rotation is live.
        assertEquals(1, activeSessionCount());
    }

    @Test
    void d3Sec001DisabledAccountCannotLogIn() {
        repository.saveAccount(new Account(
                UUID.randomUUID(), "dev", "dev@d3.dev", encoded(PASSWORD), "Dev", "DISABLED", CLOCK.instant()));

        assertThrows(
                com.ddd.d3.identity.application.InvalidCredentialsException.class,
                () -> service.login("dev@d3.dev", PASSWORD));
    }

    @Test
    void d3Sec001StoresNoPlaintextPasswordOrRawRefreshToken() {
        service.register("dev@d3.dev", "dev", "Dev", PASSWORD);
        SessionToken token = service.login("dev@d3.dev", PASSWORD);

        String storedHash = jdbc.sql("select password_hash from user_account where email = 'dev@d3.dev'")
                .query(String.class)
                .single();
        assertNotEquals(PASSWORD, storedHash);
        assertTrue(new BCryptPasswordEncoder().matches(PASSWORD, storedHash));

        long rawTokenRows = jdbc.sql("select count(*) from refresh_session where token_hash = :raw")
                .param("raw", token.refreshToken())
                .query(Long.class)
                .single();
        assertEquals(0, rawTokenRows);
    }

    @Test
    void d3Id001UpdatesDisplayName() {
        Account account = account("dev@d3.dev", "dev");
        repository.saveAccount(account);

        Account updated = repository.updateDisplayName(
                        account.id(), "Dev Updated", CLOCK.instant().plusSeconds(60))
                .orElseThrow();

        assertEquals("Dev Updated", updated.displayName());
        assertEquals(
                CLOCK.instant().plusSeconds(60),
                jdbc.sql("select updated_at from user_account where id = :id")
                        .param("id", account.id())
                        .query(Timestamp.class)
                        .single()
                        .toInstant());
    }

    @Test
    void d3Sec001DoesNotUpdateDisabledAccountDisplayName() {
        Account disabled = new Account(
                UUID.randomUUID(), "disabled", "disabled@d3.dev", "hash", "Disabled", "DISABLED", CLOCK.instant());
        repository.saveAccount(disabled);

        assertTrue(repository.updateDisplayName(disabled.id(), "Changed", CLOCK.instant().plusSeconds(60)).isEmpty());
        assertEquals("Disabled", repository.findAccountById(disabled.id()).orElseThrow().displayName());
    }

    @Test
    void d3Stat001RegistrationEmitsProfileChangedOutboxAtVersionZero() {
        Account account = account("dev@d3.dev", "dev");

        repository.saveAccount(account);

        assertEquals(0L, profileVersion(account.id()));
        assertEquals(1, outboxCount(account.id()));
        assertEquals(0L, outboxAggregateVersion(account.id()));
        assertEquals("user-profile.changed", outboxField(account.id(), "eventType"));
        assertEquals(account.id().toString(), outboxDataField(account.id(), "userId"));
        assertEquals("dev", outboxDataField(account.id(), "handle"));
        assertEquals("0", outboxDataField(account.id(), "profileVersion"));
    }

    @Test
    void d3Stat001ProfileChangesEmitMonotonicOutboxVersionsInSameTransaction() {
        Account account = account("dev@d3.dev", "dev");
        repository.saveAccount(account);

        repository.updateDisplayName(account.id(), "Dev One", CLOCK.instant().plusSeconds(60)).orElseThrow();
        repository.updateDisplayName(account.id(), "Dev Two", CLOCK.instant().plusSeconds(120)).orElseThrow();

        // Registration (v0) plus two changes (v1, v2): monotonic, one outbox row each, no gaps or reuse.
        assertEquals(2L, profileVersion(account.id()));
        assertEquals(3, outboxCount(account.id()));
        assertEquals(
                java.util.List.of(0L, 1L, 2L),
                jdbc.sql("select aggregate_version from outbox_event where aggregate_id = :id order by aggregate_version")
                        .param("id", account.id())
                        .query(Long.class)
                        .list());
        assertEquals(2L, outboxAggregateVersion(account.id()));
    }

    private long profileVersion(UUID id) {
        return jdbc.sql("select profile_version from user_account where id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private long outboxCount(UUID aggregateId) {
        return jdbc.sql("select count(*) from outbox_event where aggregate_id = :id and event_type = 'user-profile.changed'")
                .param("id", aggregateId)
                .query(Long.class)
                .single();
    }

    private long outboxAggregateVersion(UUID aggregateId) {
        return jdbc.sql("select max(aggregate_version) from outbox_event where aggregate_id = :id")
                .param("id", aggregateId)
                .query(Long.class)
                .single();
    }

    private String outboxField(UUID aggregateId, String field) {
        return jdbc.sql("select payload->>:field from outbox_event where aggregate_id = :id order by aggregate_version desc limit 1")
                .param("field", field)
                .param("id", aggregateId)
                .query(String.class)
                .single();
    }

    private String outboxDataField(UUID aggregateId, String field) {
        return jdbc.sql("select payload->'data'->>:field from outbox_event where aggregate_id = :id order by aggregate_version desc limit 1")
                .param("field", field)
                .param("id", aggregateId)
                .query(String.class)
                .single();
    }

    private static Account account(String email, String handle) {
        return new Account(
                UUID.randomUUID(), handle, email, "hash", "Name", Account.ACTIVE, CLOCK.instant());
    }

    private static String encoded(String rawPassword) {
        return new BCryptPasswordEncoder().encode(rawPassword);
    }

    private long sessionCount() {
        return jdbc.sql("select count(*) from refresh_session").query(Long.class).single();
    }

    private long activeSessionCount() {
        return jdbc.sql("select count(*) from refresh_session where revoked_at is null")
                .query(Long.class)
                .single();
    }
}
