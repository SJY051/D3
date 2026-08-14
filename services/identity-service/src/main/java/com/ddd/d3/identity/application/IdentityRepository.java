package com.ddd.d3.identity.application;

import com.ddd.d3.identity.domain.Account;
import com.ddd.d3.identity.domain.RefreshSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdentityRepository {

    /** @throws DuplicateAccountException if the email or handle is already taken (the single duplicate boundary). */
    void saveAccount(Account account);

    Optional<Account> findAccountByEmail(String email);

    Optional<Account> findAccountById(UUID id);

    void saveSession(RefreshSession session);

    Optional<RefreshSession> findSessionByTokenHash(String tokenHash);

    /**
     * Atomically revoke the current session and persist its replacement in one transaction.
     *
     * @return true if this call revoked a still-active session and stored the replacement; false if the
     *     current session was already revoked (lost a concurrent rotation), in which case nothing is stored.
     */
    boolean rotateSession(UUID currentSessionId, RefreshSession replacement, Instant revokedAt);

    void revokeSession(UUID sessionId, Instant revokedAt);

    void revokeAllSessions(UUID userId, Instant revokedAt);
}
