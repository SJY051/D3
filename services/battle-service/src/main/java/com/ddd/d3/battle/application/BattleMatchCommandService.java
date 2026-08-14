package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

public class BattleMatchCommandService {

    private final BattleMatchRepository matches;
    private final BattleCommandReceiptStore receipts;
    private final Clock clock;
    private final Duration matchDuration;
    private final TransactionOperations transactions;

    public BattleMatchCommandService(
            BattleMatchRepository matches,
            BattleCommandReceiptStore receipts,
            Clock clock,
            Duration matchDuration,
            TransactionOperations transactions) {
        this.matches = Objects.requireNonNull(matches, "matches must not be null");
        this.receipts = Objects.requireNonNull(receipts, "receipts must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.matchDuration = requirePositive(matchDuration);
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    public BattleMatch.Snapshot handle(
            UUID matchId, UUID commandId, UUID actorId, BattleMatch.Command command) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        return Objects.requireNonNull(transactions.execute(
                status -> handleInsideTransaction(matchId, commandId, actorId, command)));
    }

    private BattleMatch.Snapshot handleInsideTransaction(
            UUID matchId, UUID commandId, UUID actorId, BattleMatch.Command command) {
        CommandDescriptor descriptor = descriptor(command);
        if (!descriptor.playerId().equals(actorId.toString())) {
            throw new IllegalArgumentException("command player must match authenticated actor");
        }

        var existing = receipts.findByCommandId(commandId);
        if (existing.isPresent()) {
            BattleCommandReceiptStore.Receipt receipt = existing.orElseThrow();
            if (!receipt.matchId().equals(matchId)
                    || !receipt.playerId().equals(actorId)
                    || !receipt.commandType().equals(descriptor.type())
                    || !receipt.payloadFingerprint().equals(descriptor.fingerprint())) {
                throw new CommandIdConflictException();
            }
            return matches.findById(matchId).orElseThrow(BattleMatchNotFoundException::new);
        }

        BattleMatch.Snapshot loaded = matches.findById(matchId)
                .orElseThrow(BattleMatchNotFoundException::new);
        BattleMatch match = BattleMatch.restore(loaded, clock);
        long expectedVersion = match.aggregateVersion();
        match.handle(command);
        if (match.aggregateVersion() != expectedVersion) {
            matches.save(match.snapshot(), expectedVersion);
        }
        if (command instanceof BattleMatch.Ready && match.state() == BattleMatch.State.READY) {
            expectedVersion = match.aggregateVersion();
            match.handle(new BattleMatch.Start(matchDuration));
            matches.save(match.snapshot(), expectedVersion);
        }

        BattleMatch.Snapshot committed = match.snapshot();
        receipts.record(new BattleCommandReceiptStore.Receipt(
                commandId,
                matchId,
                actorId,
                descriptor.type(),
                descriptor.fingerprint(),
                committed.aggregateVersion(),
                clock.instant()));
        return committed;
    }

    private static CommandDescriptor descriptor(BattleMatch.Command command) {
        return switch (command) {
            case BattleMatch.Ready ready -> new CommandDescriptor("READY", "ready", ready.playerId());
            case BattleMatch.Disconnect disconnect -> new CommandDescriptor(
                    "DISCONNECT",
                    "generation=" + disconnect.connectionGeneration(),
                    disconnect.playerId());
            case BattleMatch.Reconnect reconnect -> new CommandDescriptor(
                    "RECONNECT",
                    "generation=" + reconnect.connectionGeneration(),
                    reconnect.playerId());
            case BattleMatch.Surrender surrender ->
                new CommandDescriptor("SURRENDER", "surrender", surrender.playerId());
            default -> throw new IllegalArgumentException("command is not a player command");
        };
    }

    private static Duration requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "matchDuration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("matchDuration must be positive");
        }
        return duration;
    }

    private record CommandDescriptor(String type, String fingerprint, String playerId) {}
}
