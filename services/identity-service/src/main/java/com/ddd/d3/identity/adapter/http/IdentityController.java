package com.ddd.d3.identity.adapter.http;

import com.ddd.d3.identity.application.AccessTokenIssuer;
import com.ddd.d3.identity.application.AccountNotFoundException;
import com.ddd.d3.identity.application.IdentityRepository;
import com.ddd.d3.identity.application.IdentityService;
import com.ddd.d3.identity.application.SessionToken;
import com.ddd.d3.identity.domain.Account;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
public final class IdentityController {

    private final IdentityService identityService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final IdentityRepository identityRepository;

    public IdentityController(
            IdentityService identityService,
            AccessTokenIssuer accessTokenIssuer,
            IdentityRepository identityRepository) {
        this.identityService = identityService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.identityRepository = identityRepository;
    }

    @PostMapping("/v1/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        UUID userId = identityService.register(
                request.email(), request.handle(), request.displayName(), request.password());
        return new RegisterResponse(userId);
    }

    @PostMapping("/v1/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        SessionToken token = identityService.login(request.email(), request.password());
        return tokens(token);
    }

    @PostMapping("/v1/auth/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        SessionToken token = identityService.refresh(request.refreshToken());
        return tokens(token);
    }

    @PostMapping("/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        identityService.revoke(request.refreshToken());
    }

    @GetMapping("/v1/profile/me")
    public ProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        Account account = identityRepository.findAccountById(UUID.fromString(jwt.getSubject()))
                .filter(Account::isActive)
                .orElseThrow(AccountNotFoundException::new);
        return new ProfileResponse(account.id(), account.handle(), account.email(), account.displayName());
    }

    private TokenResponse tokens(SessionToken token) {
        return new TokenResponse(token.userId(), accessTokenIssuer.issue(token.userId()), token.refreshToken());
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            // Lowercase public identifier: reject mixed case so the unique handle stays canonical.
            @NotBlank @Pattern(regexp = "^[a-z0-9-]{1,39}$") String handle,
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Size(min = 8, max = 200) String password) {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record RegisterResponse(UUID userId) {}

    public record TokenResponse(UUID userId, String accessToken, String refreshToken) {}

    public record ProfileResponse(UUID userId, String handle, String email, String displayName) {}
}
