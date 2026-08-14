package com.ddd.d3.identity.application;

import com.ddd.d3.identity.domain.Account;
import com.ddd.d3.identity.domain.RefreshSession;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test double: an in-memory IdentityRepository shared by the service and seeder unit tests. */
final class InMemoryIdentityRepository implements IdentityRepository {

    private final Map<String, Account> accountsByEmail = new HashMap<>();
    private final Map<UUID, Account> accountsById = new HashMap<>();
    private final Map<String, String> handles = new HashMap<>();
    private final Map<UUID, RefreshSession> sessionsById = new HashMap<>();
    private final Map<String, UUID> sessionIdByTokenHash = new HashMap<>();

    int accountCount() {
        return accountsById.size();
    }

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
