package com.ddd.d3.judge.adapter.http;

import com.ddd.d3.judge.application.JudgeEvaluationScheduler;
import com.ddd.d3.judge.application.JudgeSubmissionService;
import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.SafeEvaluationEvidence;
import com.ddd.d3.judge.domain.SubmissionAcceptance;
import com.ddd.d3.judge.domain.SubmissionCommand;
import com.ddd.d3.judge.domain.SubmissionMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/judge/submissions")
public final class JudgeSubmissionController {

    private final JudgeSubmissionService submissionService;
    private final JudgeEvaluationScheduler evaluationScheduler;

    public JudgeSubmissionController(
            JudgeSubmissionService submissionService, JudgeEvaluationScheduler evaluationScheduler) {
        this.submissionService = submissionService;
        this.evaluationScheduler = evaluationScheduler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionAcceptance accept(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @Valid @RequestBody SubmissionRequest request) {
        SubmissionCommand command = new SubmissionCommand(
                idempotencyKey,
                request.userId(),
                request.matchId(),
                request.problemId(),
                request.problemVersion(),
                request.mode(),
                request.language(),
                request.sourceCode(),
                request.attemptNumber(),
                correlationId);
        SubmissionAcceptance acceptance = submissionService.accept(command);
        evaluationScheduler.schedule(acceptance.submissionId());
        return acceptance;
    }

    @GetMapping("/{submissionId}/evidence")
    public SafeEvaluationEvidence evidence(
            @PathVariable UUID submissionId,
            @RequestHeader("X-Correlation-Id") String correlationId) {
        requireCorrelationId(correlationId);
        return submissionService.readEvidence(submissionId);
    }

    private static void requireCorrelationId(String correlationId) {
        if (correlationId.isBlank() || correlationId.length() > 128) {
            throw new IllegalArgumentException("correlationId is invalid");
        }
    }

    public record SubmissionRequest(
            @NotNull UUID userId,
            UUID matchId,
            @NotNull UUID problemId,
            int problemVersion,
            @NotNull SubmissionMode mode,
            @NotNull JudgeLanguage language,
            @NotBlank String sourceCode,
            Integer attemptNumber) {}
}
