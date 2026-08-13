package com.ddd.d3.judge.application;

import com.ddd.d3.judge.domain.JudgeSubmission;
import java.util.Optional;
import java.util.UUID;

public interface JudgeSubmissionRepository {
    Optional<JudgeSubmission> findByIdempotencyKey(UUID idempotencyKey);

    Optional<JudgeSubmission> findById(UUID submissionId);

    /**
     * Atomically inserts by idempotency key or returns the already committed submission.
     * Implementations must never replace the winner of a concurrent insert.
     */
    JudgeSubmission insertOrGet(JudgeSubmission submission);

    JudgeSubmission save(JudgeSubmission submission);
}
