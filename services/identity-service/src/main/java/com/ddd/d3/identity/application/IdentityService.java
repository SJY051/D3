package com.ddd.d3.identity.application;

import com.ddd.d3.identity.domain.Account;
import com.ddd.d3.identity.domain.RefreshSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class IdentityService {

    // ponytail: fixed 14-day refresh lifetime; move behind config when a season/security policy needs to tune it
    private static final Duration REFRESH_TTL = Duration.ofDays(14);

    private final IdentityRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Supplier<UUID> uuidSupplier;
    private final Supplier<String> refreshTokenSupplier;

    public IdentityService(
            IdentityRepository repository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            Supplier<UUID> uuidSupplier,
            Supplier<String> refreshTokenSupplier) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
        this.refreshTokenSupplier = Objects.requireNonNull(refreshTokenSupplier, "refreshTokenSupplier");
    }

    public UUID register(String email, String handle, String displayName, String rawPassword) {
        requireText(email, "email");
        requireText(handle, "handle");
        requireText(displayName, "displayName");
        requireText(rawPassword, "password");
        if (repository.existsByEmailOrHandle(email, handle)) {
            throw new DuplicateAccountException();
        }
        Account account = new Account(
                uuidSupplier.get(),
                handle,
                email,
                passwordEncoder.encode(rawPassword),
                displayName,
                Account.ACTIVE,
                clock.instant());
        repository.saveAccount(account);
        return account.id();
    }

    public SessionToken login(String email, String rawPassword) {
        requireText(email, "email");
        requireText(rawPassword, "password");
        Account account = repository.findAccountByEmail(email)
                .filter(candidate -> passwordEncoder.matches(rawPassword, candidate.passwordHash()))
                .filter(Account::isActive)
                .orElseThrow(InvalidCredentialsException::new);
        return issueSession(account.id(), null);
    }

    public SessionToken refresh(String rawRefreshToken) {
        requireText(rawRefreshToken, "refreshToken");
        RefreshSession current = repository.findSessionByTokenHash(hash(rawRefreshToken))
                .orElseThrow(RefreshTokenRejectedException::new);
        if (!current.isActive(clock.instant())) {
            // Presenting an already-rotated or revoked token means the secret leaked: revoke the whole family.
            repository.revokeAllSessions(current.userId(), clock.instant());
            throw new RefreshTokenRejectedException();
        }
        if (!repository.revokeSession(current.id(), clock.instant())) {
            // Lost a concurrent rotation of the same token: exactly one caller may consume it, so reject this one.
            throw new RefreshTokenRejectedException();
        }
        return issueSession(current.userId(), current.id());
    }

    public void revoke(String rawRefreshToken) {
        requireText(rawRefreshToken, "refreshToken");
        repository.findSessionByTokenHash(hash(rawRefreshToken))
                .ifPresent(session -> repository.revokeSession(session.id(), clock.instant()));
    }

    private SessionToken issueSession(UUID userId, UUID rotatedFromId) {
        String rawToken = refreshTokenSupplier.get();
        RefreshSession session = new RefreshSession(
                uuidSupplier.get(),
                userId,
                hash(rawToken),
                clock.instant().plus(REFRESH_TTL),
                rotatedFromId,
                null,
                clock.instant());
        repository.saveSession(session);
        return new SessionToken(userId, rawToken);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
