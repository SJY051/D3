package com.ddd.d3.judge.domain;

import java.time.Instant;
import java.util.UUID;

public record JudgeSubmission(
        UUID id,
        SubmissionCommand command,
        String requestFingerprint,
        JudgeStatus status,
        Instant acceptedAt,
        SafeEvaluationEvidence evidence,
        UUID evaluationClaimId,
        Instant evaluationStartedAt) {

    public JudgeSubmission(
            UUID id,
            SubmissionCommand command,
            String requestFingerprint,
            JudgeStatus status,
            Instant acceptedAt,
            SafeEvaluationEvidence evidence,
            UUID evaluationClaimId) {
        this(id, command, requestFingerprint, status, acceptedAt, evidence, evaluationClaimId, null);
    }

    public JudgeSubmission(
            UUID id,
            SubmissionCommand command,
            String requestFingerprint,
            JudgeStatus status,
            Instant acceptedAt) {
        this(id, command, requestFingerprint, status, acceptedAt, null, null, null);
    }

    public SubmissionAcceptance acceptance() {
        return new SubmissionAcceptance(id, JudgeStatus.QUEUED, command.mode(), command.language(), acceptedAt);
    }

    public JudgeSubmission startEvaluation(UUID claimId) {
        if (status != JudgeStatus.QUEUED) {
            throw new IllegalStateException("only a queued submission can start evaluation");
        }
        return new JudgeSubmission(
                id,
                command,
                requestFingerprint,
                JudgeStatus.RUNNING,
                acceptedAt,
                null,
                java.util.Objects.requireNonNull(claimId, "claimId"),
                null);
    }

    public JudgeSubmission requeueEvaluation() {
        if (status != JudgeStatus.RUNNING) {
            throw new IllegalStateException("only a running submission can be requeued");
        }
        return new JudgeSubmission(id, command, requestFingerprint, JudgeStatus.QUEUED, acceptedAt, null, null, null);
    }

    public JudgeSubmission complete(SafeEvaluationEvidence completedEvidence) {
        return new JudgeSubmission(
                id,
                command,
                requestFingerprint,
                completedEvidence.status(),
                acceptedAt,
                completedEvidence,
                evaluationClaimId,
                evaluationStartedAt);
    }

    public JudgeSubmission markEvaluationStarted(Instant startedAt) {
        if (status != JudgeStatus.RUNNING || evaluationStartedAt != null) {
            throw new IllegalStateException("only an unstarted running submission can start provider execution");
        }
        return new JudgeSubmission(
                id, command, requestFingerprint, status, acceptedAt, evidence, evaluationClaimId,
                java.util.Objects.requireNonNull(startedAt, "startedAt"));
    }
}
