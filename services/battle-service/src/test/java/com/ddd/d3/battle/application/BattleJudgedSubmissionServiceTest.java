package com.ddd.d3.battle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class BattleJudgedSubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_ONE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PLAYER_TWO = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SUBMISSION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID EVENT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Test
    void d3Btl001VoidsRunningMatchWhenAuthoritativeJudgeEvidenceReportsPlatformFailure() {
        FakeReferences references = new FakeReferences();
        FakeMatches matches = new FakeMatches(runningSnapshot());
        List<UUID> published = new ArrayList<>();
        List<JudgeServiceTokenProvider.Scope> scopes = new ArrayList<>();
        BattleJudgedSubmissionService service = new BattleJudgedSubmissionService(
                references,
                new PlatformFailureJudge(),
                scope -> {
                    scopes.add(scope);
                    return new JudgeServiceTokenProvider.Token("read-token", 300);
                },
                matches,
                published::add,
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC),
                DirectTransactions.INSTANCE);

        assertEquals(1, service.processPending(10));

        assertEquals(List.of(JudgeServiceTokenProvider.Scope.READ), scopes);
        assertEquals("PLATFORM_FAILURE", references.evidence.status());
        assertEquals(BattleMatch.State.FINISHED, matches.snapshot.state());
        assertEquals(BattleMatch.Outcome.VOID, matches.snapshot.result().outcome());
        assertEquals(BattleMatch.ResolutionReason.PLATFORM_INCIDENT, matches.snapshot.result().reason());
        assertEquals("judge-submission:" + SUBMISSION_ID, matches.snapshot.result().incidentReference());
        assertEquals(List.of(MATCH_ID), published);
    }

    @Test
    void d3Btl002BeginsJudgingEarlyWhenBothParticipantsHoldAnAcceptedSubmit() {
        FakeReferences references = new FakeReferences();
        references.bothAccepted = true;
        FakeMatches matches = new FakeMatches(runningSnapshot());
        List<UUID> published = new ArrayList<>();
        BattleJudgedSubmissionService service = new BattleJudgedSubmissionService(
                references,
                new AcceptedJudge(),
                scope -> new JudgeServiceTokenProvider.Token("read-token", 300),
                matches,
                published::add,
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC),
                DirectTransactions.INSTANCE);

        assertEquals(1, service.processPending(10));

        assertEquals(BattleMatch.State.JUDGING, matches.snapshot.state());
        assertEquals(List.of(MATCH_ID), published);
    }

    @Test
    void d3Btl002WaitsForTheDeadlineWhenOnlyOneParticipantHasAccepted() {
        FakeReferences references = new FakeReferences();
        references.bothAccepted = false;
        FakeMatches matches = new FakeMatches(runningSnapshot());
        List<UUID> published = new ArrayList<>();
        BattleJudgedSubmissionService service = new BattleJudgedSubmissionService(
                references,
                new AcceptedJudge(),
                scope -> new JudgeServiceTokenProvider.Token("read-token", 300),
                matches,
                published::add,
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC),
                DirectTransactions.INSTANCE);

        assertEquals(1, service.processPending(10));

        assertEquals(BattleMatch.State.RUNNING, matches.snapshot.state());
        assertEquals(List.of(), published);
    }

    private static BattleMatch.Snapshot runningSnapshot() {
        BattleMatch match = new BattleMatch(
                MATCH_ID.toString(), PLAYER_ONE.toString(), PLAYER_TWO.toString(), Clock.fixed(NOW, ZoneOffset.UTC));
        match.handle(new BattleMatch.Reconnect(PLAYER_ONE.toString(), 1));
        match.handle(new BattleMatch.Reconnect(PLAYER_TWO.toString(), 2));
        match.handle(new BattleMatch.Ready(PLAYER_ONE.toString()));
        match.handle(new BattleMatch.Ready(PLAYER_TWO.toString()));
        match.handle(new BattleMatch.Start(Duration.ofMinutes(10)));
        return match.snapshot();
    }

    private static final class FakeReferences implements BattleJudgeReferenceStore {
        private final Reference reference = new Reference(
                SUBMISSION_ID,
                MATCH_ID,
                PLAYER_ONE,
                BattleJudgeGateway.Mode.SUBMIT,
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                1,
                "QUEUED",
                null,
                NOW,
                null);
        private Evidence evidence;
        private boolean bothAccepted;

        @Override public List<PendingJudgedEvent> findProcessablePending(int limit) {
            return List.of(new PendingJudgedEvent(EVENT_ID, SUBMISSION_ID));
        }
        @Override public Optional<Reference> lockPendingReference(UUID eventId) { return Optional.of(reference); }
        @Override public void recordEvidence(UUID eventId, Evidence evidence) { this.evidence = evidence; }
        @Override public boolean bothParticipantsAccepted(UUID matchId) { return bothAccepted; }
        @Override public SubmissionContext lockSubmissionContext(UUID matchId, UUID playerId, long generation, BattleJudgeGateway.Mode mode) { throw new UnsupportedOperationException(); }
        @Override public Optional<Reference> findByCommandId(UUID commandId) { throw new UnsupportedOperationException(); }
        @Override public void record(Reference reference) { throw new UnsupportedOperationException(); }
        @Override public boolean receiveJudgedEvent(JudgedEvent event) { throw new UnsupportedOperationException(); }
    }

    private static final class PlatformFailureJudge implements BattleJudgeGateway {
        @Override public Acceptance accept(Command command, String authorizationHeader) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Evidence readEvidence(UUID submissionId, String authorizationHeader) {
            assertEquals(SUBMISSION_ID, submissionId);
            assertEquals("Bearer read-token", authorizationHeader);
            return new Evidence(
                    SUBMISSION_ID,
                    "PLATFORM_FAILURE",
                    0,
                    8,
                    List.of(new RuntimeMeasurement("LARGE", 10_000, 3, 0)),
                    "judge0-v1",
                    "java-21",
                    "judge-evidence-v1",
                    NOW.plusSeconds(20));
        }
    }

    private static final class AcceptedJudge implements BattleJudgeGateway {
        @Override public Acceptance accept(Command command, String authorizationHeader) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Evidence readEvidence(UUID submissionId, String authorizationHeader) {
            return new Evidence(
                    SUBMISSION_ID,
                    "ACCEPTED",
                    8,
                    8,
                    List.of(new RuntimeMeasurement("LARGE", 10_000, 3, 1_200)),
                    "judge0-v1",
                    "java-21",
                    "judge-evidence-v1",
                    NOW.plusSeconds(20));
        }
    }

    private static final class FakeMatches implements BattleMatchRepository {
        private BattleMatch.Snapshot snapshot;
        private FakeMatches(BattleMatch.Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public Optional<BattleMatch.Snapshot> findById(UUID matchId) { return Optional.of(snapshot); }
        @Override public void save(BattleMatch.Snapshot saved, long expectedVersion) { snapshot = saved; }
    }

    private enum DirectTransactions implements TransactionOperations {
        INSTANCE;
        @Override public <T> T execute(TransactionCallback<T> action) { return action.doInTransaction(null); }
    }
}
