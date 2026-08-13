package com.ddd.d3.judge.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SafeEvaluationEvidence(
        UUID submissionId,
        JudgeStatus status,
        SubmissionMode mode,
        JudgeLanguage language,
        UUID problemId,
        int problemVersion,
        int passedCount,
        int totalCount,
        List<RuntimeMeasurement> runtimeMeasurements,
        String adapterVersion,
        String runtimeVersion,
        String evidenceVersion,
        Instant completedAt) {

    public SafeEvaluationEvidence {
        runtimeMeasurements = List.copyOf(runtimeMeasurements);
    }

    public static SafeEvaluationEvidence from(JudgeSubmission submission, JudgeExecutionResult result) {
        return new SafeEvaluationEvidence(
                submission.id(),
                result.status(),
                submission.command().mode(),
                submission.command().language(),
                submission.command().problemId(),
                submission.command().problemVersion(),
                result.passedCount(),
                result.totalCount(),
                result.runtimeMeasurements(),
                result.adapterVersion(),
                result.runtimeVersion(),
                "judge-evidence-v1",
                result.completedAt());
    }
}
