package com.ddd.d3.judge.application;

import com.ddd.d3.judge.domain.JudgeSubmission;
import java.util.Optional;
import java.util.UUID;

public interface JudgeSubmissionRepository {
    Optional<JudgeSubmission> findByIdempotencyKey(UUID idempotencyKey);

    Optional<JudgeSubmission> findById(UUID submissionId);

    JudgeSubmission save(JudgeSubmission submission);
}
