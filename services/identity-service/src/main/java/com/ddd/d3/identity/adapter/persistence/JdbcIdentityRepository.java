package com.ddd.d3.identity.adapter.persistence;

import com.ddd.d3.identity.application.DuplicateAccountException;
import com.ddd.d3.identity.application.IdentityRepository;
import com.ddd.d3.identity.domain.Account;
import com.ddd.d3.identity.domain.RefreshSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcIdentityRepository implements IdentityRepository {

    private final JdbcClient jdbcClient;

    public JdbcIdentityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public boolean existsByEmailOrHandle(String email, String handle) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("select exists(select 1 from user_account where email = :email or handle = :handle)")
                .param("email", email)
                .param("handle", handle)
                .query(Boolean.class)
                .single());
    }

    @Override
    public void saveAccount(Account account) {
        try {
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
    public void saveSession(RefreshSession session) {
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
    public boolean revokeSession(UUID sessionId, Instant revokedAt) {
        return jdbcClient.sql("""
                        update refresh_session set revoked_at = :revokedAt
                        where id = :id and revoked_at is null
                        """)
                .param("revokedAt", Timestamp.from(revokedAt))
                .param("id", sessionId)
                .update() == 1;
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
