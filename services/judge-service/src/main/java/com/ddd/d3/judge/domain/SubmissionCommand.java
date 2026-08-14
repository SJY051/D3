package com.ddd.d3.judge.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record SubmissionCommand(
        UUID idempotencyKey,
        UUID userId,
        UUID matchId,
        UUID problemId,
        int problemVersion,
        SubmissionMode mode,
        JudgeLanguage language,
        String sourceCode,
        Integer attemptNumber,
        String correlationId) {

    private static final int MAX_SOURCE_BYTES = 65_536;
    private static final int MAX_CORRELATION_ID_LENGTH = 128;

    public SubmissionCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(problemId, "problemId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(correlationId, "correlationId");
        if (problemVersion <= 0) {
            throw new IllegalArgumentException("problemVersion must be positive");
        }
        if (sourceCode.isBlank()) {
            throw new IllegalArgumentException("sourceCode must not be blank");
        }
        if (sourceCode.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new PrivatePayloadTooLargeException("sourceCode", MAX_SOURCE_BYTES);
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (correlationId.length() > MAX_CORRELATION_ID_LENGTH) {
            throw new IllegalArgumentException("correlationId exceeds the 128-character limit");
        }
        if (mode == SubmissionMode.RUN && attemptNumber != null) {
            throw new IllegalArgumentException("RUN must not carry an attemptNumber");
        }
        if (mode == SubmissionMode.SUBMIT && (attemptNumber == null || attemptNumber <= 0)) {
            throw new IllegalArgumentException("SUBMIT requires a positive attemptNumber");
        }
    }
}
