package com.ddd.d3.identity.adapter.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IdentityController.class)
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
                .andExpect(jsonPath("$.refreshToken").value("refresh-secret-1"));
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
        mockMvc.perform(get("/v1/profile/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void d3Id001ProfileReturnsTheAuthenticatedAccount() throws Exception {
        when(identityRepository.findAccountById(USER_ID)).thenReturn(Optional.of(new Account(
                USER_ID, "dev", "dev@d3.dev", "hash", "Dev", Account.ACTIVE, Instant.parse("2026-08-14T00:00:00Z"))));

        mockMvc.perform(get("/v1/profile/me").with(jwt().jwt(token -> token.subject(USER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.handle").value("dev"))
                .andExpect(jsonPath("$.email").value("dev@d3.dev"));
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
}
