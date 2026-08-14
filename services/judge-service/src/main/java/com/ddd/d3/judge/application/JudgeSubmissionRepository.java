package com.ddd.d3.judge.application;

import com.ddd.d3.judge.domain.JudgeSubmission;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JudgeSubmissionRepository {
    Optional<JudgeSubmission> findByIdempotencyKey(UUID idempotencyKey);

    Optional<JudgeSubmission> findById(UUID submissionId);

    List<UUID> findPendingEvaluationIds(int maximumCount);

    /**
     * Atomically inserts by idempotency key or returns the already committed submission.
     * Implementations must never replace the winner of a concurrent insert.
     */
    JudgeSubmission insertOrGet(JudgeSubmission submission);

    /** Atomically transitions QUEUED to RUNNING and returns the claim only to its winner. */
    Optional<JudgeSubmission> claimForEvaluation(UUID submissionId);

    /** Durably records that provider execution may have started before making the provider call. */
    JudgeSubmission markEvaluationStarted(UUID submissionId, UUID evaluationClaimId);

    /** Completes a submission only while its evaluation claim is held. */
    JudgeSubmission completeEvaluation(JudgeSubmission submission);

    /** Returns a failed pre-execution claim to QUEUED; started provider work must never be replayed. */
    void releaseEvaluationClaim(UUID submissionId, UUID evaluationClaimId);
}
