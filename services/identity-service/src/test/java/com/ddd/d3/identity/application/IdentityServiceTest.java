package com.ddd.d3.identity.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class IdentityServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);

    private InMemoryIdentityRepository repository;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryIdentityRepository();
        AtomicInteger sequence = new AtomicInteger();
        service = new IdentityService(
                repository,
                new BCryptPasswordEncoder(),
                CLOCK,
                () -> new UUID(0, sequence.incrementAndGet()),
                () -> "raw-token-" + sequence.incrementAndGet());
    }

    @Test
    void d3Id001RegisterThenLoginIssuesARefreshSession() {
        UUID userId = service.register("dev@d3.dev", "dev", "Dev", "correct horse");

        SessionToken token = service.login("dev@d3.dev", "correct horse");

        assertEquals(userId, token.userId());
        assertEquals(1, repository.sessionCount());
    }

    @Test
    void d3Id001RejectsADuplicateAccount() {
        service.register("dev@d3.dev", "dev", "Dev", "correct horse");

        assertThrows(
                DuplicateAccountException.class,
                () -> service.register("dev@d3.dev", "dev2", "Dev Two", "another one"));
    }

    @Test
    void d3Id001TreatsEmailCaseInsensitively() {
        service.register("Dev@d3.dev", "dev", "Dev", "correct horse");

        assertDoesNotThrow(() -> service.login("dev@d3.dev", "correct horse"));
        assertThrows(
                DuplicateAccountException.class,
                () -> service.register("DEV@d3.dev", "dev-two", "Dev Two", "another one"));
    }

    @Test
    void d3Id001RejectsAWrongPassword() {
        service.register("dev@d3.dev", "dev", "Dev", "correct horse");

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login("dev@d3.dev", "wrong password"));
    }

    @Test
    void d3Id001RefreshRotatesAndRejectsTheOldToken() {
        service.register("dev@d3.dev", "dev", "Dev", "correct horse");
        SessionToken first = service.login("dev@d3.dev", "correct horse");

        SessionToken rotated = service.refresh(first.refreshToken());

        assertNotEquals(first.refreshToken(), rotated.refreshToken());
        assertThrows(RefreshTokenRejectedException.class, () -> service.refresh(first.refreshToken()));
    }

    @Test
    void d3Sec001ReusingARotatedTokenRevokesTheWholeFamily() {
        service.register("dev@d3.dev", "dev", "Dev", "correct horse");
        SessionToken first = service.login("dev@d3.dev", "correct horse");
        SessionToken rotated = service.refresh(first.refreshToken());

        assertThrows(RefreshTokenRejectedException.class, () -> service.refresh(first.refreshToken()));
        // The breach response also kills the still-live rotated token.
        assertThrows(RefreshTokenRejectedException.class, () -> service.refresh(rotated.refreshToken()));
    }

    @Test
    void d3Id001RevokeEndsTheSession() {
        service.register("dev@d3.dev", "dev", "Dev", "correct horse");
        SessionToken token = service.login("dev@d3.dev", "correct horse");

        service.revoke(token.refreshToken());

        assertThrows(RefreshTokenRejectedException.class, () -> service.refresh(token.refreshToken()));
    }

    @Test
    void d3Id001UpdatesTheAuthenticatedProfile() {
        UUID userId = service.register("dev@d3.dev", "dev", "Dev", "correct horse");

        assertEquals("Dev Updated", service.updateProfile(userId, "Dev Updated").displayName());
    }
}
