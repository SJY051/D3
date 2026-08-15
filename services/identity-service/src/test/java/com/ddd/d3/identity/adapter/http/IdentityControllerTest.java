package com.ddd.d3.identity.adapter.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ddd.d3.identity.application.DuplicateAccountException;
import com.ddd.d3.identity.application.IdentityRepository;
import com.ddd.d3.identity.application.IdentityService;
import com.ddd.d3.identity.application.InvalidCredentialsException;
import com.ddd.d3.identity.application.SessionToken;
import com.ddd.d3.identity.config.IdentityJwtConfiguration;
import com.ddd.d3.identity.config.IdentitySecurityConfiguration;
import com.ddd.d3.identity.domain.Account;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        value = IdentityController.class,
        properties = {
            "spring.profiles.active=test",
            "D3_REFRESH_COOKIE_SECURE=true",
            "D3_BATTLE_SERVICE_CLIENT_SECRET=test-battle-secret"
        })
@Import({
    IdentityJwtConfiguration.class,
    IdentitySecurityConfiguration.class,
    IdentityHttpExceptionHandler.class
})
class IdentityControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired MockMvc mockMvc;
    @MockitoBean IdentityService identityService;
    @MockitoBean IdentityRepository identityRepository;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void d3Id001RegistersANewAccount() throws Exception {
        when(identityService.register(any(), any(), any(), any())).thenReturn(USER_ID);

        mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dev@d3.dev","handle":"dev","displayName":"Dev","password":"correct horse"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()));
    }

    @Test
    void d3Sec001RejectsADuplicateRegistration() throws Exception {
        when(identityService.register(any(), any(), any(), any())).thenThrow(new DuplicateAccountException());

        mockMvc.perform(post("/v1/auth/register")
                        .header("X-Correlation-Id", "corr-dup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dev@d3.dev","handle":"dev","displayName":"Dev","password":"correct horse"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT"))
                .andExpect(jsonPath("$.correlationId").value("corr-dup"));
    }

    @Test
    void d3Id001LoginReturnsAccessAndRefreshTokens() throws Exception {
        when(identityService.login("dev@d3.dev", "correct horse"))
                .thenReturn(new SessionToken(USER_ID, "refresh-secret-1"));

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dev@d3.dev","password":"correct horse"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("D3_REFRESH_TOKEN=refresh-secret-1")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Secure")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Lax")));
    }

    @Test
    void d3Id001RefreshRotatesTheHttpOnlyCookie() throws Exception {
        when(identityService.refresh("refresh-secret-1")).thenReturn(new SessionToken(USER_ID, "refresh-secret-2"));

        mockMvc.perform(post("/v1/auth/refresh")
                        .cookie(new Cookie("D3_REFRESH_TOKEN", "refresh-secret-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("D3_REFRESH_TOKEN=refresh-secret-2")));
    }

    @Test
    void d3Id001LogoutRevokesOnlyTheCookieSession() throws Exception {
        mockMvc.perform(post("/v1/auth/logout")
                        .cookie(new Cookie("D3_REFRESH_TOKEN", "refresh-secret-1")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("D3_REFRESH_TOKEN=")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        verify(identityService).revoke("refresh-secret-1");
    }

    @Test
    void d3Sec001RejectsAWrongPassword() throws Exception {
        when(identityService.login(any(), any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dev@d3.dev","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void d3Sec001ProfileRequiresAuthenticationAtTheServiceBoundary() throws Exception {
        mockMvc.perform(get("/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void d3Id001ProfileReturnsTheAuthenticatedAccount() throws Exception {
        when(identityRepository.findAccountById(USER_ID)).thenReturn(Optional.of(account(Account.ACTIVE)));

        mockMvc.perform(get("/v1/users/me").with(profileToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.handle").value("dev"))
                .andExpect(jsonPath("$.email").value("dev@d3.dev"));
    }

    @Test
    void d3Id001ProfileCanUpdateDisplayName() throws Exception {
        Account updated = new Account(
                USER_ID, "dev", "dev@d3.dev", "hash", "Dev Updated", Account.ACTIVE, Instant.parse("2026-08-14T00:00:00Z"));
        when(identityService.updateProfile(USER_ID, "Dev Updated")).thenReturn(updated);

        mockMvc.perform(patch("/v1/users/me")
                        .with(profileToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Dev Updated"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Dev Updated"));
    }

    @Test
    void d3Sec001RejectsAProfileTokenWithoutTheProfileScope() throws Exception {
        mockMvc.perform(get("/v1/users/me")
                        .with(jwt().jwt(token -> token.subject(USER_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_other"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void d3Sec001DoesNotServeADisabledAccount() throws Exception {
        when(identityRepository.findAccountById(USER_ID)).thenReturn(Optional.of(account("DISABLED")));

        mockMvc.perform(get("/v1/users/me").with(profileToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor profileToken() {
        return jwt().jwt(token -> token.subject(USER_ID.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_identity.profile"));
    }

    private static Account account(String status) {
        return new Account(USER_ID, "dev", "dev@d3.dev", "hash", "Dev", status, Instant.parse("2026-08-14T00:00:00Z"));
    }

    @Test
    void d3Id001RejectsAnInvalidRegistrationBody() throws Exception {
        mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","handle":"dev","displayName":"Dev","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(identityService, org.mockito.Mockito.never()).register(any(), any(), any(), any());
    }

    @Test
    void d3Id001RejectsAHandleThatIsNotLowercaseSlug() throws Exception {
        mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dev@d3.dev","handle":"Dev Handle","displayName":"Dev","password":"correct horse"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(identityService, org.mockito.Mockito.never()).register(any(), any(), any(), any());
    }
}
