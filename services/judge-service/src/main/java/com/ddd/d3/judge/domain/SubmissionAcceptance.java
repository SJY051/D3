package com.ddd.d3.judge.domain;

import java.time.Instant;
import java.util.UUID;

public record SubmissionAcceptance(
        UUID submissionId,
        JudgeStatus status,
        SubmissionMode mode,
        JudgeLanguage language,
        Instant acceptedAt) {}
