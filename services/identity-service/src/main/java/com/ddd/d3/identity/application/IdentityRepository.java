package com.ddd.d3.identity.application;

import com.ddd.d3.identity.domain.Account;
import com.ddd.d3.identity.domain.RefreshSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdentityRepository {

    boolean existsByEmailOrHandle(String email, String handle);

    void saveAccount(Account account);

    Optional<Account> findAccountByEmail(String email);

    Optional<Account> findAccountById(UUID id);

    void saveSession(RefreshSession session);

    Optional<RefreshSession> findSessionByTokenHash(String tokenHash);

    /** @return true if this call revoked a still-active session; false if it was already revoked (lost the race). */
    boolean revokeSession(UUID sessionId, Instant revokedAt);

    void revokeAllSessions(UUID userId, Instant revokedAt);
}
