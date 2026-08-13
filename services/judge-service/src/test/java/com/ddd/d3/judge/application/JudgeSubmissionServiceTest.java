package com.ddd.d3.judge.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ddd.d3.judge.domain.JudgeExecutionResult;
import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.JudgeStatus;
import com.ddd.d3.judge.domain.JudgeSubmission;
import com.ddd.d3.judge.domain.RuntimeMeasurement;
import com.ddd.d3.judge.domain.SafeEvaluationEvidence;
import com.ddd.d3.judge.domain.SubmissionAcceptance;
import com.ddd.d3.judge.domain.SubmissionCommand;
import com.ddd.d3.judge.domain.SubmissionMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JudgeSubmissionServiceTest {

    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SUBMISSION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID MATCH_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PROBLEM_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-13T12:00:00Z");

    @Test
    void d3Jdg001ReturnsTheSameSubmissionForAnEquivalentRetry() {
        JudgeSubmissionService service = service(true);
        SubmissionCommand command = submitCommand("print(input())");

        SubmissionAcceptance first = service.accept(command);
        SubmissionAcceptance retry = service.accept(command);

        assertEquals(SUBMISSION_ID, first.submissionId());
        assertEquals(first, retry);
    }

    @Test
    void d3Jdg001RejectsIdempotencyKeyReuseWithDifferentSource() {
        JudgeSubmissionService service = service(true);
        service.accept(submitCommand("print(input())"));

        assertThrows(
                IdempotencyConflictException.class,
                () -> service.accept(submitCommand("print('different')")));
    }

    @Test
    void d3Jdg001RejectsAnUnavailableMappedRuntimeBeforeAcceptance() {
        JudgeSubmissionService service = service(false);

        assertThrows(RuntimeUnavailableException.class, () -> service.accept(submitCommand("print(input())")));
    }

    @Test
    void d3Sec001RejectsSourceLargerThanThePrivateRequestBoundary() {
        assertThrows(IllegalArgumentException.class, () -> submitCommand("한".repeat(21_846)));
    }

    @Test
    void d3Jdg001ReturnsOnlyPrivacySafePersistedEvaluationEvidence() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        var result = new JudgeExecutionResult(
                JudgeStatus.ACCEPTED,
                3,
                3,
                List.of(new RuntimeMeasurement("SMALL", 100, 3, 700)),
                "fake-v1",
                "fake-python3-v1",
                ACCEPTED_AT.plusSeconds(1));
        JudgeSubmissionService service = new JudgeSubmissionService(
                repository,
                new JudgeExecutionAdapter() {
                    @Override
                    public boolean isAvailable(JudgeLanguage language) {
                        return true;
                    }

                    @Override
                    public JudgeExecutionResult execute(SubmissionCommand command) {
                        return result;
                    }
                },
                Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
                () -> SUBMISSION_ID);
        service.accept(submitCommand("private-source"));

        SafeEvaluationEvidence evidence = service.evaluate(SUBMISSION_ID);
        String serializedEvidence = new ObjectMapper().writeValueAsString(evidence);

        assertEquals(JudgeStatus.ACCEPTED, evidence.status());
        assertEquals(3, evidence.passedCount());
        assertEquals(evidence, service.evaluate(SUBMISSION_ID));
        assertFalse(serializedEvidence.contains("private-source"));
        assertFalse(serializedEvidence.contains("sourceCode"));
        assertFalse(serializedEvidence.contains("hidden"));
    }

    private static JudgeSubmissionService service(boolean available) {
        return new JudgeSubmissionService(
                new InMemoryRepository(),
                language -> available,
                Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
                () -> SUBMISSION_ID);
    }

    private static SubmissionCommand submitCommand(String sourceCode) {
        return new SubmissionCommand(
                IDEMPOTENCY_KEY,
                USER_ID,
                MATCH_ID,
                PROBLEM_ID,
                1,
                SubmissionMode.SUBMIT,
                JudgeLanguage.PYTHON3,
                sourceCode,
                1,
                "corr-1");
    }

    private static final class InMemoryRepository implements JudgeSubmissionRepository {
        private final Map<UUID, JudgeSubmission> submissions = new HashMap<>();

        @Override
        public Optional<JudgeSubmission> findByIdempotencyKey(UUID idempotencyKey) {
            return submissions.values().stream()
                    .filter(submission -> submission.command().idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public Optional<JudgeSubmission> findById(UUID submissionId) {
            return Optional.ofNullable(submissions.get(submissionId));
        }

        @Override
        public JudgeSubmission save(JudgeSubmission submission) {
            submissions.put(submission.id(), submission);
            return submission;
        }
    }
}
