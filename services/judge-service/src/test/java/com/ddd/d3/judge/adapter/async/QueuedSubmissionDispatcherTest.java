package com.ddd.d3.judge.adapter.async;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ddd.d3.judge.application.JudgeSubmissionRepository;
import com.ddd.d3.judge.domain.JudgeSubmission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueuedSubmissionDispatcherTest {

    @Test
    void d3Jdg001ReschedulesDurableQueuedSubmissionsAfterProcessRecovery() {
        UUID first = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID second = UUID.fromString("22222222-2222-4222-8222-222222222222");
        List<UUID> scheduled = new ArrayList<>();
        RecordingRepository repository = new RecordingRepository(List.of(first, second));
        QueuedSubmissionDispatcher dispatcher =
                new QueuedSubmissionDispatcher(repository, scheduled::add);

        assertEquals(2, dispatcher.scheduleBatch());
        assertEquals(List.of(first, second), scheduled);
        assertEquals(20, repository.requestedMaximum);
    }

    private static final class RecordingRepository implements JudgeSubmissionRepository {
        private final List<UUID> queued;
        private int requestedMaximum;

        private RecordingRepository(List<UUID> queued) {
            this.queued = queued;
        }

        @Override
        public List<UUID> findPendingEvaluationIds(int maximumCount) {
            requestedMaximum = maximumCount;
            return queued;
        }

        @Override public Optional<JudgeSubmission> findByIdempotencyKey(UUID idempotencyKey) { return Optional.empty(); }
        @Override public Optional<JudgeSubmission> findById(UUID submissionId) { return Optional.empty(); }
        @Override public JudgeSubmission insertOrGet(JudgeSubmission submission) { throw new UnsupportedOperationException(); }
        @Override public Optional<JudgeSubmission> claimForEvaluation(UUID submissionId) { return Optional.empty(); }
        @Override public JudgeSubmission markEvaluationStarted(UUID submissionId, UUID evaluationClaimId) { throw new UnsupportedOperationException(); }
        @Override public JudgeSubmission completeEvaluation(JudgeSubmission submission) { throw new UnsupportedOperationException(); }
        @Override public void releaseEvaluationClaim(UUID submissionId, UUID evaluationClaimId) {}
    }
}
