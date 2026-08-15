package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import com.ddd.d3.battle.domain.attack.GarbageAttackExchange;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

public final class BattleAttackService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BattleAttackService.class);
    private final BattleMatchRepository matches;
    private final GarbageAttackEventStore events;
    private final BattleCommandReceiptStore receipts;
    private final Clock clock;
    private final GarbageAttackExchange.Policy policy;
    private final LongSupplier overlaySeeds;
    private final TransactionOperations transactions;
    private final BattleSnapshotPublisher snapshots;

    public BattleAttackService(BattleMatchRepository matches, GarbageAttackEventStore events,
            BattleCommandReceiptStore receipts, Clock clock, GarbageAttackExchange.Policy policy,
            LongSupplier overlaySeeds, TransactionOperations transactions, BattleSnapshotPublisher snapshots) {
        this.matches = Objects.requireNonNull(matches);
        this.events = Objects.requireNonNull(events);
        this.receipts = Objects.requireNonNull(receipts);
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
        this.overlaySeeds = Objects.requireNonNull(overlaySeeds);
        this.transactions = Objects.requireNonNull(transactions);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    public BattleAttackView launch(UUID matchId, UUID commandId, UUID actorId, long generation, String attackId) {
        return command(matchId, commandId, actorId, generation, "ATTACK_LAUNCH", attackId,
                exchange -> exchange.launch(attackId, actorId.toString()));
    }

    public BattleAttackView block(UUID matchId, UUID commandId, UUID actorId, long generation, String attackId) {
        return command(matchId, commandId, actorId, generation, "ATTACK_BLOCK", attackId,
                exchange -> exchange.block(attackId, actorId.toString()));
    }

    public BattleAttackView reflect(UUID matchId, UUID commandId, UUID actorId, long generation, String attackId) {
        return command(matchId, commandId, actorId, generation, "ATTACK_REFLECT", attackId,
                exchange -> exchange.reflect(attackId, actorId.toString()));
    }

    public BattleAttackView awardProgress(UUID matchId, UUID playerId, String marker) {
        BattleAttackView view = Objects.requireNonNull(transactions.execute(status -> {
            Loaded loaded = load(matchId);
            requireRunningParticipant(loaded.match(), playerId);
            int before = loaded.exchange().events().size();
            loaded.exchange().advanceTime();
            loaded.exchange().awardProgress(playerId.toString(), Objects.requireNonNull(marker));
            appendNew(matchId, loaded.exchange(), before);
            return project(matchId, playerId, loaded.match(), loaded.exchange());
        }));
        publish(matchId);
        return view;
    }

    public BattleAttackView read(UUID matchId, UUID viewerId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            Loaded loaded = load(matchId);
            requireParticipant(loaded.match(), viewerId);
            int before = loaded.exchange().events().size();
            loaded.exchange().advanceTime();
            appendNew(matchId, loaded.exchange(), before);
            return project(matchId, viewerId, loaded.match(), loaded.exchange());
        }));
    }

    private BattleAttackView command(UUID matchId, UUID commandId, UUID actorId, long generation,
            String type, String attackId, AttackAction action) {
        Objects.requireNonNull(commandId);
        if (Objects.requireNonNull(attackId).isBlank()) throw new IllegalArgumentException("attackId must not be blank");
        BattleAttackView view = Objects.requireNonNull(transactions.execute(status -> {
            Loaded loaded = load(matchId);
            requireAuthoritativeRunningConnection(loaded.match(), actorId, generation);
            String fingerprint = "attackId=" + attackId;
            var existing = receipts.findByCommandId(commandId);
            if (existing.isPresent()) {
                var receipt = existing.orElseThrow();
                if (!receipt.matchId().equals(matchId) || !receipt.playerId().equals(actorId)
                        || !receipt.commandType().equals(type) || !receipt.payloadFingerprint().equals(fingerprint)) {
                    throw new CommandIdConflictException();
                }
                return project(matchId, actorId, loaded.match(), loaded.exchange());
            }
            int before = loaded.exchange().events().size();
            loaded.exchange().advanceTime();
            action.apply(loaded.exchange());
            appendNew(matchId, loaded.exchange(), before);
            Instant acceptedAt = clock.instant();
            receipts.record(new BattleCommandReceiptStore.Receipt(commandId, matchId, actorId, type, fingerprint,
                    loaded.exchange().events().getLast().sequence(), acceptedAt));
            return project(matchId, actorId, loaded.match(), loaded.exchange());
        }));
        publish(matchId);
        return view;
    }

    private Loaded load(UUID matchId) {
        Objects.requireNonNull(matchId);
        events.lock(matchId);
        BattleMatch.Snapshot match = matches.findById(matchId).orElseThrow(BattleMatchNotFoundException::new);
        var exchange = GarbageAttackExchange.diagnosticReplay(matchId.toString(), match.playerOneId(),
                match.playerTwoId(), clock, policy, overlaySeeds, events.findByMatchId(matchId));
        return new Loaded(match, exchange);
    }

    private void appendNew(UUID matchId, GarbageAttackExchange exchange, int before) {
        List<GarbageAttackExchange.AttackEvent> all = exchange.events();
        if (all.size() > before) events.append(matchId, List.copyOf(all.subList(before, all.size())));
    }

    private static void requireAuthoritativeRunningConnection(BattleMatch.Snapshot match, UUID actor, long generation) {
        if (generation <= 0) throw new IllegalArgumentException("connectionGeneration must be positive");
        requireRunningParticipant(match, actor);
        var player = participant(match, actor);
        if (player.connectionState() != BattleMatch.ConnectionState.CONNECTED
                || !Objects.equals(player.completedConnectionGeneration(), generation)) {
            throw new IllegalStateException("WebSocket connection is not authoritative");
        }
    }

    private static void requireRunningParticipant(BattleMatch.Snapshot match, UUID player) {
        requireParticipant(match, player);
        if (match.state() != BattleMatch.State.RUNNING) throw new IllegalStateException("attack commands require a running match");
    }

    private static void requireParticipant(BattleMatch.Snapshot match, UUID player) { participant(match, player); }
    private static BattleMatch.PlayerSnapshot participant(BattleMatch.Snapshot match, UUID player) {
        Objects.requireNonNull(player);
        return match.players().stream().filter(candidate -> candidate.playerId().equals(player.toString())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("actor is not a match participant"));
    }

    private BattleAttackView project(UUID matchId, UUID viewer, BattleMatch.Snapshot match,
            GarbageAttackExchange exchange) {
        String self = viewer.toString();
        String opponent = match.playerOneId().equals(self) ? match.playerTwoId() : match.playerOneId();
        var attack = exchange.currentAttack().map(state -> new BattleAttackView.Attack(state.attackId(), state.phase(),
                state.targetPlayerId().equals(self) ? BattleAttackView.Target.SELF : BattleAttackView.Target.OPPONENT,
                state.warningDeadline(), state.overlayExpiresAt(), state.overlaySeed(), state.resolution())).orElse(null);
        var history = exchange.events();
        return new BattleAttackView(matchId, history.isEmpty() ? 0 : history.getLast().sequence(), clock.instant(),
                exchange.energy(self), exchange.energy(opponent), attack);
    }

    private void publish(UUID matchId) {
        try { snapshots.publish(matchId); }
        catch (RuntimeException exception) { LOGGER.warn("Committed battle attack snapshot fan-out failed for matchId={}", matchId); }
    }

    @FunctionalInterface private interface AttackAction { void apply(GarbageAttackExchange exchange); }
    private record Loaded(BattleMatch.Snapshot match, GarbageAttackExchange exchange) {}
}
