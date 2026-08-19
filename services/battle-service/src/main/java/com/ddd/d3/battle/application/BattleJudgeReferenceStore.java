package com.ddd.d3.battle.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BattleJudgeReferenceStore {

    SubmissionContext lockSubmissionContext(UUID matchId, UUID playerId, long connectionGeneration, BattleJudgeGateway.Mode mode);

    Optional<Reference> findByCommandId(UUID commandId);

    Optional<SubmissionVerdict> findLatestSubmissionVerdict(UUID matchId, UUID playerId);

    void record(Reference reference);

    boolean receiveJudgedEvent(JudgedEvent event);

    List<PendingJudgedEvent> findProcessablePending(int limit);

    Optional<Reference> lockPendingReference(UUID eventId);

    void recordEvidence(UUID eventId, Evidence evidence);

    record SubmissionContext(
            UUID problemId,
            int problemVersion,
            String language,
            int nextSubmitAttempt,
            long aggregateVersion) {}

    record Reference(
            UUID submissionId,
            UUID matchId,
            UUID playerId,
            BattleJudgeGateway.Mode mode,
            UUID commandId,
            Integer attemptNumber,
            String status,
            String evidenceVersion,
            Instant acceptedAt,
            Instant lastResultAt) {}

    record JudgedEvent(
            UUID eventId,
            UUID submissionId,
            long aggregateVersion,
            Instant receivedAt) {}

    record PendingJudgedEvent(UUID eventId, UUID submissionId) {}

    record SubmissionVerdict(UUID submissionId, String status, int attemptNumber, Instant completedAt) {}

    record Evidence(
            UUID submissionId,
            String status,
            int passedCount,
            int totalCount,
            List<BattleJudgeGateway.RuntimeMeasurement> runtimeMeasurements,
            String adapterVersion,
            String runtimeVersion,
            String evidenceVersion,
            Instant completedAt) {}
}
