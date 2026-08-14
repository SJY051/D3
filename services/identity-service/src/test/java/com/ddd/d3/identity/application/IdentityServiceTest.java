package com.ddd.d3.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ddd.d3.identity.domain.Account;
import com.ddd.d3.identity.domain.RefreshSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

    private static final class InMemoryIdentityRepository implements IdentityRepository {

        private final Map<String, Account> accountsByEmail = new HashMap<>();
        private final Map<UUID, Account> accountsById = new HashMap<>();
        private final Map<String, String> handles = new HashMap<>();
        private final Map<UUID, RefreshSession> sessionsById = new HashMap<>();
        private final Map<String, UUID> sessionIdByTokenHash = new HashMap<>();

        int sessionCount() {
            return sessionsById.size();
        }

        @Override
        public boolean existsByEmailOrHandle(String email, String handle) {
            return accountsByEmail.containsKey(email) || handles.containsKey(handle);
        }

        @Override
        public void saveAccount(Account account) {
            accountsByEmail.put(account.email(), account);
            accountsById.put(account.id(), account);
            handles.put(account.handle(), account.email());
        }

        @Override
        public Optional<Account> findAccountByEmail(String email) {
            return Optional.ofNullable(accountsByEmail.get(email));
        }

        @Override
        public Optional<Account> findAccountById(UUID id) {
            return Optional.ofNullable(accountsById.get(id));
        }

        @Override
        public void saveSession(RefreshSession session) {
            sessionsById.put(session.id(), session);
            sessionIdByTokenHash.put(session.tokenHash(), session.id());
        }

        @Override
        public Optional<RefreshSession> findSessionByTokenHash(String tokenHash) {
            return Optional.ofNullable(sessionIdByTokenHash.get(tokenHash)).map(sessionsById::get);
        }

        @Override
        public void revokeSession(UUID sessionId, Instant revokedAt) {
            RefreshSession current = sessionsById.get(sessionId);
            if (current != null) {
                sessionsById.put(sessionId, revoked(current, revokedAt));
            }
        }

        @Override
        public void revokeAllSessions(UUID userId, Instant revokedAt) {
            sessionsById.replaceAll((id, session) ->
                    session.userId().equals(userId) ? revoked(session, revokedAt) : session);
        }

        private static RefreshSession revoked(RefreshSession session, Instant revokedAt) {
            if (session.revokedAt() != null) {
                return session;
            }
            return new RefreshSession(
                    session.id(),
                    session.userId(),
                    session.tokenHash(),
                    session.expiresAt(),
                    session.rotatedFromId(),
                    revokedAt,
                    session.createdAt());
        }
    }
}
