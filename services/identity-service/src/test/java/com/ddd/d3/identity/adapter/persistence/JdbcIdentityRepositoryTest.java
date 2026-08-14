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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
        repository = new JdbcIdentityRepository(jdbc);
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
