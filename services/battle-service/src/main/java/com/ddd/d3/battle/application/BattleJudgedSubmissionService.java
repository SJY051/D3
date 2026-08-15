package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

public final class BattleJudgedSubmissionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleJudgedSubmissionService.class);

    private final BattleJudgeReferenceStore references;
    private final BattleJudgeGateway judge;
    private final JudgeServiceTokenProvider tokens;
    private final BattleMatchRepository matches;
    private final BattleSnapshotPublisher snapshots;
    private final Clock clock;
    private final TransactionOperations transactions;

    public BattleJudgedSubmissionService(
            BattleJudgeReferenceStore references,
            BattleJudgeGateway judge,
            JudgeServiceTokenProvider tokens,
            BattleMatchRepository matches,
            BattleSnapshotPublisher snapshots,
            Clock clock,
            TransactionOperations transactions) {
        this.references = Objects.requireNonNull(references, "references");
        this.judge = Objects.requireNonNull(judge, "judge");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public boolean receive(BattleJudgeReferenceStore.JudgedEvent event) {
        Objects.requireNonNull(event, "event");
        return Boolean.TRUE.equals(transactions.execute(status -> references.receiveJudgedEvent(event)));
    }

    public int processPending(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        int processed = 0;
        for (BattleJudgeReferenceStore.PendingJudgedEvent pending : references.findProcessablePending(limit)) {
            try {
                process(pending);
                processed++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Judge result application deferred for submissionId={}", pending.submissionId(), exception);
            }
        }
        return processed;
    }

    private void process(BattleJudgeReferenceStore.PendingJudgedEvent pending) {
        String authorization = tokens.acquire(JudgeServiceTokenProvider.Scope.READ).authorizationHeader();
        BattleJudgeGateway.Evidence evidence = judge.readEvidence(pending.submissionId(), authorization);
        if (!evidence.submissionId().equals(pending.submissionId())) {
            throw new IllegalStateException("Judge evidence submissionId mismatch");
        }
        Optional<UUID> affectedMatch = transactions.execute(status -> applyInsideTransaction(pending, evidence));
        if (affectedMatch != null && affectedMatch.isPresent()) {
            try {
                snapshots.publish(affectedMatch.orElseThrow());
            } catch (RuntimeException exception) {
                LOGGER.warn("Committed judged snapshot fan-out failed for matchId={}", affectedMatch.orElseThrow());
            }
        }
    }

    private Optional<UUID> applyInsideTransaction(
            BattleJudgeReferenceStore.PendingJudgedEvent pending,
            BattleJudgeGateway.Evidence evidence) {
        Optional<BattleJudgeReferenceStore.Reference> locked = references.lockPendingReference(pending.eventId());
        if (locked.isEmpty()) return Optional.empty();
        BattleJudgeReferenceStore.Reference reference = locked.orElseThrow();
        if (!reference.submissionId().equals(evidence.submissionId())) {
            throw new IllegalStateException("Pending event reference mismatch");
        }

        references.recordEvidence(pending.eventId(), new BattleJudgeReferenceStore.Evidence(
                evidence.submissionId(),
                evidence.status(),
                evidence.passedCount(),
                evidence.totalCount(),
                evidence.runtimeMeasurements(),
                evidence.adapterVersion(),
                evidence.runtimeVersion(),
                evidence.evidenceVersion(),
                evidence.completedAt()));

        if (!"PLATFORM_FAILURE".equals(evidence.status())) {
            return Optional.empty();
        }
        BattleMatch.Snapshot snapshot = matches.findById(reference.matchId())
                .orElseThrow(BattleMatchNotFoundException::new);
        BattleMatch match = BattleMatch.restore(snapshot, Clock.fixed(evidence.completedAt(), clock.getZone()));
        long expectedVersion = match.aggregateVersion();
        match.handle(new BattleMatch.PlatformIncident("judge-submission:" + evidence.submissionId()));
        if (match.aggregateVersion() != expectedVersion) {
            matches.save(match.snapshot(), expectedVersion);
            return Optional.of(reference.matchId());
        }
        return Optional.empty();
    }
}
