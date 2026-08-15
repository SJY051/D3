package com.ddd.d3.battle.infrastructure.judge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "judge-service", url = "${D3_JUDGE_INTERNAL_URL:}")
interface JudgeSubmissionClient {

    @PostMapping("/internal/v1/judge/submissions")
    AcceptanceResponse accept(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @RequestBody SubmissionRequest request);

    @GetMapping("/internal/v1/judge/submissions/{submissionId}/evidence")
    EvidenceResponse evidence(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @PathVariable UUID submissionId);

    record SubmissionRequest(
            UUID userId,
            UUID matchId,
            UUID problemId,
            int problemVersion,
            String mode,
            String language,
            String sourceCode,
            Integer attemptNumber) {}

    record AcceptanceResponse(
            UUID submissionId,
            String status,
            String mode,
            String language,
            Instant acceptedAt) {}

    record RuntimeMeasurement(
            String tier,
            long inputSize,
            int sampleCount,
            long medianRuntimeMicros) {}

    record EvidenceResponse(
            UUID submissionId,
            String status,
            String mode,
            String language,
            UUID problemId,
            int problemVersion,
            int passedCount,
            int totalCount,
            List<RuntimeMeasurement> runtimeMeasurements,
            String adapterVersion,
            String runtimeVersion,
            String evidenceVersion,
            Instant completedAt) {}
}
