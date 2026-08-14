package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

public class BattleMatchCommandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleMatchCommandService.class);
    private final BattleMatchRepository matches;
    private final BattleCommandReceiptStore receipts;
    private final Clock clock;
    private final Duration matchDuration;
    private final TransactionOperations transactions;
    private final BattleSnapshotPublisher snapshots;

    public BattleMatchCommandService(
            BattleMatchRepository matches,
            BattleCommandReceiptStore receipts,
            Clock clock,
            Duration matchDuration,
            TransactionOperations transactions,
            BattleSnapshotPublisher snapshots) {
        this.matches = Objects.requireNonNull(matches, "matches must not be null");
        this.receipts = Objects.requireNonNull(receipts, "receipts must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.matchDuration = requirePositive(matchDuration);
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
    }

    public BattleMatch.Snapshot handle(
            UUID matchId, UUID commandId, UUID actorId, BattleMatch.Command command) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        CommandExecution execution = Objects.requireNonNull(transactions.execute(
                status -> handleInsideTransaction(matchId, commandId, actorId, command)));
        try {
            snapshots.publish(matchId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Committed battle snapshot fan-out failed for matchId={}", matchId);
        }
        if (!execution.commandAccepted()) {
            throw new IllegalStateException("player command lost to an authoritative lifecycle transition");
        }
        return execution.snapshot();
    }

    private CommandExecution handleInsideTransaction(
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
            return CommandExecution.accepted(
                    matches.findById(matchId).orElseThrow(BattleMatchNotFoundException::new));
        }

        BattleMatch.Snapshot loaded = matches.findById(matchId)
                .orElseThrow(BattleMatchNotFoundException::new);
        BattleMatch match = BattleMatch.restore(loaded, clock);
        long initialVersion = match.aggregateVersion();
        long expectedVersion = match.aggregateVersion();
        if (command instanceof BattleMatch.Surrender) {
            match.handle(new BattleMatch.AdvanceTime());
            if (match.aggregateVersion() != expectedVersion) {
                matches.save(match.snapshot(), expectedVersion);
                return CommandExecution.rejected(match.snapshot());
            }
        }
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
        if (committed.aggregateVersion() == initialVersion) {
            throw new IllegalStateException("new commandId must change match state");
        }
        receipts.record(new BattleCommandReceiptStore.Receipt(
                commandId,
                matchId,
                actorId,
                descriptor.type(),
                descriptor.fingerprint(),
                committed.aggregateVersion(),
                clock.instant()));
        return CommandExecution.accepted(committed);
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

    private record CommandExecution(BattleMatch.Snapshot snapshot, boolean commandAccepted) {

        private static CommandExecution accepted(BattleMatch.Snapshot snapshot) {
            return new CommandExecution(snapshot, true);
        }

        private static CommandExecution rejected(BattleMatch.Snapshot snapshot) {
            return new CommandExecution(snapshot, false);
        }
    }
}
