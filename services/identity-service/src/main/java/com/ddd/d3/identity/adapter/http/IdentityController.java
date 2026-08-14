package com.ddd.d3.identity.adapter.http;

import com.ddd.d3.identity.application.AccessTokenIssuer;
import com.ddd.d3.identity.application.AccountNotFoundException;
import com.ddd.d3.identity.application.IdentityRepository;
import com.ddd.d3.identity.application.IdentityService;
import com.ddd.d3.identity.application.RefreshTokenRejectedException;
import com.ddd.d3.identity.application.SessionToken;
import com.ddd.d3.identity.domain.Account;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class IdentityController {

    static final String REFRESH_COOKIE = "D3_REFRESH_TOKEN";
    private static final Duration REFRESH_COOKIE_MAX_AGE = Duration.ofDays(14);
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final IdentityService identityService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final IdentityRepository identityRepository;
    private final boolean refreshCookieSecure;

    public IdentityController(
            IdentityService identityService,
            AccessTokenIssuer accessTokenIssuer,
            IdentityRepository identityRepository,
            @Value("${D3_REFRESH_COOKIE_SECURE:false}") boolean refreshCookieSecure) {
        this.identityService = identityService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.identityRepository = identityRepository;
        this.refreshCookieSecure = refreshCookieSecure;
    }

    @PostMapping("/v1/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        UUID userId = identityService.register(
                request.email(), request.handle(), request.displayName(), request.password());
        return new RegisterResponse(userId);
    }

    @PostMapping("/v1/auth/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        SessionToken token = identityService.login(request.email(), request.password());
        return tokens(token);
    }

    @PostMapping("/v1/auth/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new RefreshTokenRejectedException();
        }
        SessionToken token = identityService.refresh(refreshToken);
        return tokens(token);
    }

    @PostMapping("/v1/auth/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            identityService.revoke(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .build();
    }

    @GetMapping("/v1/users/me")
    public ProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        Account account = identityRepository.findAccountById(UUID.fromString(jwt.getSubject()))
                .filter(Account::isActive)
                .orElseThrow(AccountNotFoundException::new);
        return new ProfileResponse(account.id(), account.handle(), account.email(), account.displayName());
    }

    @PatchMapping("/v1/users/me")
    public ProfileResponse updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        Account account = identityService.updateProfile(UUID.fromString(jwt.getSubject()), request.displayName());
        return new ProfileResponse(account.id(), account.handle(), account.email(), account.displayName());
    }

    private ResponseEntity<TokenResponse> tokens(SessionToken token) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(token.refreshToken()).toString())
                .body(new TokenResponse(token.userId(), accessTokenIssuer.issue(token.userId())));
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(REFRESH_COOKIE_MAX_AGE)
                .build();
    }

    private ResponseCookie expiredRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            // Lowercase public identifier: reject mixed case so the unique handle stays canonical.
            @NotBlank @Pattern(regexp = "^[a-z0-9-]{1,39}$") String handle,
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Size(min = 8, max = 200) String password) {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record RegisterResponse(UUID userId) {}

    public record TokenResponse(UUID userId, String accessToken) {}

    public record ProfileResponse(UUID userId, String handle, String email, String displayName) {}

    public record UpdateProfileRequest(@NotBlank @Size(max = 80) String displayName) {}
}
