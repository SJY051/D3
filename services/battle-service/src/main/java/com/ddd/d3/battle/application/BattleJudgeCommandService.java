package com.ddd.d3.battle.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

public final class BattleJudgeCommandService {

    private final BattleJudgeReferenceStore references;
    private final BattleCommandReceiptStore receipts;
    private final BattleJudgeGateway judge;
    private final JudgeServiceTokenProvider tokens;
    private final Clock clock;
    private final TransactionOperations transactions;

    public BattleJudgeCommandService(
            BattleJudgeReferenceStore references,
            BattleCommandReceiptStore receipts,
            BattleJudgeGateway judge,
            JudgeServiceTokenProvider tokens,
            Clock clock,
            TransactionOperations transactions) {
        this.references = Objects.requireNonNull(references, "references");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.judge = Objects.requireNonNull(judge, "judge");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Acceptance handle(
            UUID matchId,
            UUID commandId,
            UUID playerId,
            long connectionGeneration,
            BattleJudgeGateway.Mode mode,
            String sourceCode) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(mode, "mode");
        requireSourceCode(sourceCode);
        if (connectionGeneration <= 0) {
            throw new IllegalArgumentException("connectionGeneration must be positive");
        }
        return Objects.requireNonNull(transactions.execute(status -> handleInsideTransaction(
                matchId, commandId, playerId, connectionGeneration, mode, sourceCode)));
    }

    private Acceptance handleInsideTransaction(
            UUID matchId,
            UUID commandId,
            UUID playerId,
            long connectionGeneration,
            BattleJudgeGateway.Mode mode,
            String sourceCode) {
        String fingerprint = fingerprint(mode, sourceCode);
        var existingReceipt = receipts.findByCommandId(commandId);
        if (existingReceipt.isPresent()) {
            var receipt = existingReceipt.orElseThrow();
            if (!receipt.matchId().equals(matchId)
                    || !receipt.playerId().equals(playerId)
                    || !receipt.commandType().equals(mode.name())
                    || !receipt.payloadFingerprint().equals(fingerprint)) {
                throw new CommandIdConflictException();
            }
            BattleJudgeReferenceStore.Reference reference = references.findByCommandId(commandId)
                    .orElseThrow(() -> new IllegalStateException("judge command receipt has no submission reference"));
            int attemptNumber = reference.attemptNumber() == null ? 0 : reference.attemptNumber();
            return new Acceptance(reference.submissionId(), attemptNumber, reference.status());
        }

        BattleJudgeReferenceStore.SubmissionContext context =
                references.lockSubmissionContext(matchId, playerId, connectionGeneration, mode);
        Integer judgeAttempt = mode == BattleJudgeGateway.Mode.SUBMIT
                ? context.nextSubmitAttempt()
                : null;
        BattleJudgeGateway.Command command = new BattleJudgeGateway.Command(
                commandId,
                playerId,
                matchId,
                context.problemId(),
                context.problemVersion(),
                mode,
                context.language(),
                sourceCode,
                judgeAttempt);
        String authorization = tokens.acquire(JudgeServiceTokenProvider.Scope.SUBMIT).authorizationHeader();
        BattleJudgeGateway.Acceptance accepted = judge.accept(command, authorization);
        int responseAttempt = judgeAttempt == null ? 0 : judgeAttempt;
        references.record(new BattleJudgeReferenceStore.Reference(
                accepted.submissionId(),
                matchId,
                playerId,
                mode,
                commandId,
                judgeAttempt,
                accepted.status(),
                null,
                accepted.acceptedAt(),
                null));
        receipts.record(new BattleCommandReceiptStore.Receipt(
                commandId,
                matchId,
                playerId,
                mode.name(),
                fingerprint,
                context.aggregateVersion(),
                clock.instant()));
        return new Acceptance(accepted.submissionId(), responseAttempt, accepted.status());
    }

    private static void requireSourceCode(String sourceCode) {
        Objects.requireNonNull(sourceCode, "sourceCode");
        if (sourceCode.isBlank() || sourceCode.length() > 65_536) {
            throw new IllegalArgumentException("sourceCode length is invalid");
        }
    }

    private static String fingerprint(BattleJudgeGateway.Mode mode, String sourceCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sourceCode.getBytes(StandardCharsets.UTF_8));
            return "mode=" + mode.name() + ";sourceSha256=" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Acceptance(UUID submissionId, int attemptNumber, String status) {}
}
