package com.ddd.d3.judge.domain;

import java.time.Instant;
import java.util.UUID;

public record JudgeSubmission(
        UUID id,
        SubmissionCommand command,
        String requestFingerprint,
        JudgeStatus status,
        Instant acceptedAt,
        SafeEvaluationEvidence evidence) {

    public JudgeSubmission(
            UUID id,
            SubmissionCommand command,
            String requestFingerprint,
            JudgeStatus status,
            Instant acceptedAt) {
        this(id, command, requestFingerprint, status, acceptedAt, null);
    }

    public SubmissionAcceptance acceptance() {
        return new SubmissionAcceptance(id, JudgeStatus.QUEUED, command.mode(), command.language(), acceptedAt);
    }

    public JudgeSubmission complete(SafeEvaluationEvidence completedEvidence) {
        return new JudgeSubmission(
                id,
                command,
                requestFingerprint,
                completedEvidence.status(),
                acceptedAt,
                completedEvidence);
    }
}
