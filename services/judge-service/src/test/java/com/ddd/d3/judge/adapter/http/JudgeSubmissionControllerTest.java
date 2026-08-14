package com.ddd.d3.judge.adapter.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ddd.d3.judge.application.JudgeEvaluationScheduler;
import com.ddd.d3.judge.application.JudgeSubmissionService;
import com.ddd.d3.judge.config.JudgeSecurityConfiguration;
import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.JudgeStatus;
import com.ddd.d3.judge.domain.SafeEvaluationEvidence;
import com.ddd.d3.judge.domain.SubmissionAcceptance;
import com.ddd.d3.judge.domain.SubmissionMode;
import java.time.Instant;
import java.util.List;
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

@WebMvcTest(JudgeSubmissionController.class)
@Import({JudgeSecurityConfiguration.class, JudgeHttpExceptionHandler.class})
class JudgeSubmissionControllerTest {

    private static final UUID SUBMISSION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID MATCH_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PROBLEM_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Autowired MockMvc mockMvc;
    @MockitoBean JudgeSubmissionService submissionService;
    @MockitoBean JudgeEvaluationScheduler evaluationScheduler;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void d3Sec001RejectsMissingAndPlayerCredentialsAtTheInternalBoundary() throws Exception {
        mockMvc.perform(post("/internal/v1/judge/submissions")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("print(1)")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/internal/v1/judge/submissions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_profile.read")))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("print(1)")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/internal/v1/judge/submissions")
                        .with(jwt().jwt(token -> token.claim("client_id", "browser-user"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_judge.submit")))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("print(1)")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void d3Jdg001AcceptsAndSchedulesAServiceAuthorizedSubmission() throws Exception {
        when(submissionService.accept(any())).thenReturn(new SubmissionAcceptance(
                SUBMISSION_ID,
                JudgeStatus.QUEUED,
                SubmissionMode.SUBMIT,
                JudgeLanguage.PYTHON3,
                Instant.parse("2026-08-13T12:00:00Z")));

        mockMvc.perform(post("/internal/v1/judge/submissions")
                        .with(serviceJwt("SCOPE_judge.submit"))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("print(1)")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.submissionId").value(SUBMISSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        verify(evaluationScheduler).schedule(SUBMISSION_ID);
    }

    @Test
    void d3Sec001RejectsSourceAtBoundaryPlusOneWithoutCallingTheApplication() throws Exception {
        mockMvc.perform(post("/internal/v1/judge/submissions")
                        .with(serviceJwt("SCOPE_judge.submit"))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Correlation-Id", "corr-oversized")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("x".repeat(65_537))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.correlationId").value("corr-oversized"));

        verify(submissionService, never()).accept(any());
    }

    @Test
    void d3Sec001RejectsAnOversizedJsonEnvelopeBeforeDeserialization() throws Exception {
        String oversized = "{\"padding\":\"" + "x".repeat(JudgeRequestSizeFilter.MAX_REQUEST_BYTES) + "\"}";

        mockMvc.perform(post("/internal/v1/judge/submissions")
                        .with(serviceJwt("SCOPE_judge.submit"))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Correlation-Id", "corr-envelope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.correlationId").value("corr-envelope"));

        verify(submissionService, never()).accept(any());
    }

    @Test
    void d3Sec001AppliesTheEnvelopeLimitToMatrixParameterPaths() throws Exception {
        String oversized = "{\"padding\":\"" + "x".repeat(JudgeRequestSizeFilter.MAX_REQUEST_BYTES) + "\"}";

        for (String path : List.of(
                "/internal;x/v1/judge/submissions",
                "/internal/v1;x/judge/submissions",
                "/internal/v1/judge;x/submissions",
                "/internal/v1/judge/submissions;x=y")) {
            mockMvc.perform(post(path)
                            .with(serviceJwt("SCOPE_judge.submit"))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .header("X-Correlation-Id", "corr-matrix")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(oversized))
                    .andExpect(status().is4xxClientError());
        }

        verify(submissionService, never()).accept(any());
    }

    @Test
    void d3Btl003ReturnsOnlyPersistedSafeEvidenceToAReadAuthorizedService() throws Exception {
        when(submissionService.readEvidence(SUBMISSION_ID)).thenReturn(new SafeEvaluationEvidence(
                SUBMISSION_ID,
                JudgeStatus.ACCEPTED,
                SubmissionMode.SUBMIT,
                JudgeLanguage.PYTHON3,
                PROBLEM_ID,
                1,
                3,
                3,
                List.of(),
                "judge0-ce-1.13.1",
                "Python 3.8.1",
                "judge-evidence-v1",
                Instant.parse("2026-08-13T12:00:01Z")));

        mockMvc.perform(get("/internal/v1/judge/submissions/{submissionId}/evidence", SUBMISSION_ID)
                        .with(serviceJwt("SCOPE_judge.read"))
                        .header("X-Correlation-Id", "corr-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.sourceCode").doesNotExist())
                .andExpect(jsonPath("$.diagnostics").doesNotExist());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor serviceJwt(String authority) {
        return jwt().jwt(token -> token.claim("client_id", "battle-service"))
                .authorities(new SimpleGrantedAuthority(authority));
    }

    private static String requestJson(String sourceCode) {
        return """
                {
                  "userId": "%s",
                  "matchId": "%s",
                  "problemId": "%s",
                  "problemVersion": 1,
                  "mode": "SUBMIT",
                  "language": "PYTHON3",
                  "sourceCode": "%s",
                  "attemptNumber": 1
                }
                """.formatted(USER_ID, MATCH_ID, PROBLEM_ID, sourceCode);
    }
}
