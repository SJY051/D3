package com.ddd.d3.battle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class BattleJudgeCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PROBLEM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SUBMISSION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void d3Btl001ForwardsPrivateSourceWithoutPersistingItAndRetriesIdempotently() {
        FakeReferences references = new FakeReferences();
        FakeReceipts receipts = new FakeReceipts();
        FakeJudge judge = new FakeJudge();
        List<JudgeServiceTokenProvider.Scope> scopes = new ArrayList<>();
        BattleJudgeCommandService service = new BattleJudgeCommandService(
                references,
                receipts,
                judge,
                scope -> {
                    scopes.add(scope);
                    return new JudgeServiceTokenProvider.Token("service-token", 300);
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                DirectTransactions.INSTANCE);
        UUID commandId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        String source = "class Main { public static void main(String[] args) { } }";

        BattleJudgeCommandService.Acceptance first = service.handle(
                MATCH_ID, commandId, PLAYER_ID, 9, BattleJudgeGateway.Mode.SUBMIT, source);
        BattleJudgeCommandService.Acceptance retried = service.handle(
                MATCH_ID, commandId, PLAYER_ID, 9, BattleJudgeGateway.Mode.SUBMIT, source);

        assertEquals(first, retried);
        assertEquals(SUBMISSION_ID, first.submissionId());
        assertEquals(3, first.attemptNumber());
        assertEquals(1, judge.accepted.size());
        BattleJudgeGateway.Command forwarded = judge.accepted.getFirst();
        assertEquals(source, forwarded.sourceCode());
        assertEquals(3, forwarded.attemptNumber());
        assertEquals("Bearer service-token", judge.authorization);
        assertEquals(List.of(JudgeServiceTokenProvider.Scope.SUBMIT), scopes);
        assertEquals(1, references.recorded.size());
        assertEquals(1, receipts.values.size());
        assertFalse(receipts.values.get(commandId).payloadFingerprint().contains(source));
    }

    @Test
    void d3Btl001RunUsesNoJudgeOrPersistentAttemptNumber() {
        FakeReferences references = new FakeReferences();
        FakeJudge judge = new FakeJudge();
        BattleJudgeCommandService service = service(references, new FakeReceipts(), judge);

        BattleJudgeCommandService.Acceptance accepted = service.handle(
                MATCH_ID,
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                PLAYER_ID,
                9,
                BattleJudgeGateway.Mode.RUN,
                "class Main {}\n");

        assertEquals(0, accepted.attemptNumber());
        assertNull(judge.accepted.getFirst().attemptNumber());
        assertNull(references.recorded.getFirst().attemptNumber());
    }

    @Test
    void d3Btl001RejectsCommandIdReuseWithDifferentPrivateSource() {
        FakeReferences references = new FakeReferences();
        FakeReceipts receipts = new FakeReceipts();
        FakeJudge judge = new FakeJudge();
        BattleJudgeCommandService service = service(references, receipts, judge);
        UUID commandId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        service.handle(MATCH_ID, commandId, PLAYER_ID, 9, BattleJudgeGateway.Mode.SUBMIT, "class Main {}");

        assertThrows(CommandIdConflictException.class, () -> service.handle(
                MATCH_ID, commandId, PLAYER_ID, 9, BattleJudgeGateway.Mode.SUBMIT, "class Different {}"));
        assertEquals(1, judge.accepted.size());
    }

    private static BattleJudgeCommandService service(
            FakeReferences references, FakeReceipts receipts, FakeJudge judge) {
        return new BattleJudgeCommandService(
                references,
                receipts,
                judge,
                scope -> new JudgeServiceTokenProvider.Token("service-token", 300),
                Clock.fixed(NOW, ZoneOffset.UTC),
                DirectTransactions.INSTANCE);
    }

    private static final class FakeReferences implements BattleJudgeReferenceStore {
        private final List<Reference> recorded = new ArrayList<>();

        @Override
        public SubmissionContext lockSubmissionContext(
                UUID matchId, UUID playerId, long connectionGeneration, BattleJudgeGateway.Mode mode) {
            assertEquals(MATCH_ID, matchId);
            assertEquals(PLAYER_ID, playerId);
            assertEquals(9, connectionGeneration);
            return new SubmissionContext(PROBLEM_ID, 4, "JAVA", 3, 12);
        }

        @Override
        public Optional<Reference> findByCommandId(UUID commandId) {
            return recorded.stream().filter(value -> value.commandId().equals(commandId)).findFirst();
        }

        @Override public void record(Reference reference) { recorded.add(reference); }
        @Override public boolean receiveJudgedEvent(JudgedEvent event) { throw new UnsupportedOperationException(); }
        @Override public List<PendingJudgedEvent> findProcessablePending(int limit) { throw new UnsupportedOperationException(); }
        @Override public Optional<Reference> lockPendingReference(UUID eventId) { throw new UnsupportedOperationException(); }
        @Override public void recordEvidence(UUID eventId, Evidence evidence) { throw new UnsupportedOperationException(); }
        @Override public boolean bothParticipantsAccepted(UUID matchId) { throw new UnsupportedOperationException(); }
    }

    private static final class FakeReceipts implements BattleCommandReceiptStore {
        private final Map<UUID, Receipt> values = new HashMap<>();
        @Override public Optional<Receipt> findByCommandId(UUID commandId) { return Optional.ofNullable(values.get(commandId)); }
        @Override public void record(Receipt receipt) { values.put(receipt.commandId(), receipt); }
    }

    private static final class FakeJudge implements BattleJudgeGateway {
        private final List<Command> accepted = new ArrayList<>();
        private String authorization;

        @Override
        public Acceptance accept(Command command, String authorizationHeader) {
            accepted.add(command);
            authorization = authorizationHeader;
            return new Acceptance(SUBMISSION_ID, "QUEUED", NOW);
        }

        @Override public Evidence readEvidence(UUID submissionId, String authorizationHeader) {
            throw new UnsupportedOperationException();
        }
    }

    private enum DirectTransactions implements TransactionOperations {
        INSTANCE;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }
}
