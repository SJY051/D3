package com.ddd.d3.identity.adapter.persistence;

import com.ddd.d3.identity.application.DuplicateAccountException;
import com.ddd.d3.identity.application.IdentityRepository;
import com.ddd.d3.identity.domain.Account;
import com.ddd.d3.identity.domain.RefreshSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class JdbcIdentityRepository implements IdentityRepository {

    private static final String PROFILE_CHANGED_EVENT = "user-profile.changed";

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public JdbcIdentityRepository(
            JdbcClient jdbcClient, TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void saveAccount(Account account) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcClient.sql("""
                                insert into user_account (
                                    id, handle, email, password_hash, display_name, status,
                                    created_at, updated_at
                                ) values (
                                    :id, :handle, :email, :passwordHash, :displayName, :status,
                                    :createdAt, :createdAt
                                )
                                """)
                        .param("id", account.id())
                        .param("handle", account.handle())
                        .param("email", account.email())
                        .param("passwordHash", account.passwordHash())
                        .param("displayName", account.displayName())
                        .param("status", account.status())
                        .param("createdAt", Timestamp.from(account.createdAt()))
                        .update();
                // Registration publishes the first projection (profileVersion 0) in the same transaction.
                insertProfileChangedOutbox(account.id(), account.handle(), 0L, account.createdAt());
            });
        } catch (DuplicateKeyException conflict) {
            // A concurrent registration won the email/handle unique constraint.
            throw new DuplicateAccountException();
        }
    }

    @Override
    public Optional<Account> findAccountByEmail(String email) {
        return jdbcClient.sql("""
                        select id, handle, email, password_hash, display_name, status, created_at
                        from user_account where email = :email
                        """)
                .param("email", email)
                .query(this::mapAccount)
                .optional();
    }

    @Override
    public Optional<Account> findAccountById(UUID id) {
        return jdbcClient.sql("""
                        select id, handle, email, password_hash, display_name, status, created_at
                        from user_account where id = :id
                        """)
                .param("id", id)
                .query(this::mapAccount)
                .optional();
    }

    @Override
    public Optional<Account> updateDisplayName(UUID id, String displayName, Instant updatedAt) {
        return transactionTemplate.execute(status -> {
            // Bump profileVersion monotonically and read back the handle so the outbox event and the
            // committed row agree, both under the same transaction.
            Optional<ProfileChange> change = jdbcClient.sql("""
                            update user_account
                            set display_name = :displayName,
                                updated_at = :updatedAt,
                                profile_version = profile_version + 1
                            where id = :id and status = :status
                            returning handle, profile_version
                            """)
                    .param("displayName", displayName)
                    .param("updatedAt", Timestamp.from(updatedAt))
                    .param("id", id)
                    .param("status", Account.ACTIVE)
                    .query((rs, rowNumber) -> new ProfileChange(rs.getString("handle"), rs.getLong("profile_version")))
                    .optional();
            if (change.isEmpty()) {
                return Optional.empty();
            }
            insertProfileChangedOutbox(id, change.get().handle(), change.get().profileVersion(), updatedAt);
            return findAccountById(id);
        });
    }

    private record ProfileChange(String handle, long profileVersion) {}

    /**
     * Appends a {@code user-profile.changed.v1} envelope to the outbox. The caller runs inside the same
     * transaction as the {@code user_account} write, so the projection event and the state commit or roll
     * back together. {@code aggregate_version} carries {@code profileVersion} for the consumer's monotonic
     * source-version guard; the outbox unique constraint makes redelivery a no-op.
     */
    private void insertProfileChangedOutbox(UUID userId, String handle, long profileVersion, Instant occurredAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("handle", handle);
        data.put("profileVersion", profileVersion);
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", PROFILE_CHANGED_EVENT);
        envelope.put("version", 1);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("correlationId", userId.toString());
        envelope.put("aggregateId", userId.toString());
        envelope.put("aggregateVersion", profileVersion);
        envelope.put("data", data);
        jdbcClient.sql("""
                        insert into outbox_event (
                            id, aggregate_id, aggregate_version, event_type, payload, occurred_at, published_at)
                        values (
                            :id, :aggregateId, :aggregateVersion, :eventType,
                            cast(:payload as jsonb), :occurredAt, null)
                        """)
                .param("id", eventId)
                .param("aggregateId", userId)
                .param("aggregateVersion", profileVersion)
                .param("eventType", PROFILE_CHANGED_EVENT)
                .param("payload", json(envelope))
                .param("occurredAt", Timestamp.from(occurredAt))
                .update();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("identity outbox payload could not be serialized", exception);
        }
    }

    @Override
    public void saveSession(RefreshSession session) {
        insertSession(session);
    }

    private void insertSession(RefreshSession session) {
        jdbcClient.sql("""
                        insert into refresh_session (
                            id, user_id, token_hash, expires_at, rotated_from_id, revoked_at, created_at
                        ) values (
                            :id, :userId, :tokenHash, :expiresAt, :rotatedFromId, :revokedAt, :createdAt
                        )
                        """)
                .param("id", session.id())
                .param("userId", session.userId())
                .param("tokenHash", session.tokenHash())
                .param("expiresAt", Timestamp.from(session.expiresAt()))
                .param("rotatedFromId", session.rotatedFromId())
                .param("revokedAt", session.revokedAt() == null ? null : Timestamp.from(session.revokedAt()))
                .param("createdAt", Timestamp.from(session.createdAt()))
                .update();
    }

    @Override
    public Optional<RefreshSession> findSessionByTokenHash(String tokenHash) {
        return jdbcClient.sql("""
                        select id, user_id, token_hash, expires_at, rotated_from_id, revoked_at, created_at
                        from refresh_session where token_hash = :tokenHash
                        """)
                .param("tokenHash", tokenHash)
                .query(this::mapSession)
                .optional();
    }

    @Override
    public boolean rotateSession(UUID currentSessionId, RefreshSession replacement, Instant revokedAt) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            int revoked = revokeIfActive(currentSessionId, revokedAt);
            if (revoked != 1) {
                // Another concurrent rotation already consumed this token; store no replacement.
                return false;
            }
            insertSession(replacement);
            return true;
        }));
    }

    @Override
    public void revokeSession(UUID sessionId, Instant revokedAt) {
        revokeIfActive(sessionId, revokedAt);
    }

    private int revokeIfActive(UUID sessionId, Instant revokedAt) {
        return jdbcClient.sql("""
                        update refresh_session set revoked_at = :revokedAt
                        where id = :id and revoked_at is null
                        """)
                .param("revokedAt", Timestamp.from(revokedAt))
                .param("id", sessionId)
                .update();
    }

    @Override
    public void revokeAllSessions(UUID userId, Instant revokedAt) {
        jdbcClient.sql("""
                        update refresh_session set revoked_at = :revokedAt
                        where user_id = :userId and revoked_at is null
                        """)
                .param("revokedAt", Timestamp.from(revokedAt))
                .param("userId", userId)
                .update();
    }

    private Account mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Account(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("handle"),
                resultSet.getString("email"),
                resultSet.getString("password_hash"),
                resultSet.getString("display_name"),
                resultSet.getString("status"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private RefreshSession mapSession(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        return new RefreshSession(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("token_hash"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getObject("rotated_from_id", UUID.class),
                revokedAt == null ? null : revokedAt.toInstant(),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
