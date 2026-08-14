package com.ddd.d3.judge.application;

import com.ddd.d3.judge.domain.SubmissionAcceptance;
import com.ddd.d3.judge.domain.SubmissionCommand;
import com.ddd.d3.judge.domain.JudgeStatus;
import com.ddd.d3.judge.domain.JudgeSubmission;
import com.ddd.d3.judge.domain.SafeEvaluationEvidence;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class JudgeSubmissionService {

    private final JudgeSubmissionRepository repository;
    private final JudgeExecutionAdapter executionAdapter;
    private final Clock clock;
    private final Supplier<UUID> submissionIdSupplier;

    public JudgeSubmissionService(
            JudgeSubmissionRepository repository,
            JudgeExecutionAdapter executionAdapter,
            Clock clock,
            Supplier<UUID> submissionIdSupplier) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.executionAdapter = Objects.requireNonNull(executionAdapter, "executionAdapter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.submissionIdSupplier = Objects.requireNonNull(submissionIdSupplier, "submissionIdSupplier");
    }

    public SubmissionAcceptance accept(SubmissionCommand command) {
        Objects.requireNonNull(command, "command");
        String fingerprint = fingerprint(command);

        var existing = repository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.orElseThrow().requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException();
            }
            return existing.orElseThrow().acceptance();
        }

        if (!executionAdapter.isAvailable(command.language())) {
            throw new RuntimeUnavailableException(command.language());
        }

        JudgeSubmission submission = new JudgeSubmission(
                submissionIdSupplier.get(),
                command,
                fingerprint,
                JudgeStatus.QUEUED,
                clock.instant());
        JudgeSubmission stored = repository.insertOrGet(submission);
        if (!stored.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException();
        }
        return stored.acceptance();
    }

    public SafeEvaluationEvidence evaluate(UUID submissionId) {
        Objects.requireNonNull(submissionId, "submissionId");
        JudgeSubmission current = repository.findById(submissionId)
                .orElseThrow(() -> new SubmissionNotFoundException(submissionId));
        if (current.evidence() != null) {
            return current.evidence();
        }

        JudgeSubmission claimed = repository.claimForEvaluation(submissionId).orElseGet(() -> {
            JudgeSubmission latest = repository.findById(submissionId)
                    .orElseThrow(() -> new SubmissionNotFoundException(submissionId));
            if (latest.evidence() != null) {
                return latest;
            }
            throw new EvaluationInProgressException(submissionId);
        });
        if (claimed.evidence() != null) {
            return claimed.evidence();
        }

        try {
            var result = executionAdapter.execute(claimed.command());
            SafeEvaluationEvidence evidence = SafeEvaluationEvidence.from(claimed, result);
            return repository.completeEvaluation(claimed.complete(evidence)).evidence();
        } catch (RuntimeException exception) {
            repository.releaseEvaluationClaim(submissionId, claimed.evaluationClaimId());
            throw exception;
        }
    }

    public SafeEvaluationEvidence readEvidence(UUID submissionId) {
        Objects.requireNonNull(submissionId, "submissionId");
        JudgeSubmission submission = repository.findById(submissionId)
                .orElseThrow(() -> new SubmissionNotFoundException(submissionId));
        if (submission.evidence() == null) {
            throw new EvidenceNotReadyException(submissionId);
        }
        return submission.evidence();
    }

    private static String fingerprint(SubmissionCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, command.userId().toString());
            update(digest, command.matchId() == null ? null : command.matchId().toString());
            update(digest, command.problemId().toString());
            update(digest, Integer.toString(command.problemVersion()));
            update(digest, command.mode().name());
            update(digest, command.language().name());
            update(digest, command.sourceCode());
            update(digest, command.attemptNumber() == null ? null : command.attemptNumber().toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
