package com.ddd.d3.battle.domain.attack;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

public final class GarbageAttackExchange {

    public enum Phase {
        WARNING,
        ACTIVE,
        RESOLVED
    }

    public enum Resolution {
        BLOCKED,
        EXPIRED
    }

    public enum EventType {
        ENERGY_GRANTED,
        ATTACK_WARNED,
        ATTACK_BLOCKED,
        ATTACK_REFLECTED,
        OVERLAY_ACTIVATED,
        OVERLAY_EXPIRED
    }

    public record Policy(
            int maximumEnergy,
            int progressGain,
            int passiveGain,
            int attackCost,
            int blockCost,
            int reflectCost,
            Duration passiveInterval,
            Duration warningDuration,
            Duration effectDuration) {

        public Policy {
            if (maximumEnergy <= 0
                    || progressGain < 0
                    || passiveGain < 0
                    || attackCost <= 0
                    || blockCost <= 0
                    || reflectCost <= 0) {
                throw new IllegalArgumentException("energy policy values are invalid");
            }
            if (maximumEnergy < Math.max(attackCost, Math.max(blockCost, reflectCost))) {
                throw new IllegalArgumentException("maximumEnergy must cover every action cost");
            }
            passiveInterval = requirePositiveDuration(passiveInterval, "passiveInterval");
            warningDuration = requirePositiveDuration(warningDuration, "warningDuration");
            effectDuration = requirePositiveDuration(effectDuration, "effectDuration");
            if (passiveInterval.toMillis() == 0) {
                throw new IllegalArgumentException("passiveInterval must be at least one millisecond");
            }
        }

        public static Policy initial() {
            return new Policy(
                    100,
                    20,
                    5,
                    40,
                    20,
                    30,
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(3));
        }
    }

    public record AttackState(
            String attackId,
            String originalActorId,
            String targetPlayerId,
            boolean reflected,
            Phase phase,
            Instant warningDeadline,
            Instant overlayExpiresAt,
            long overlaySeed,
            Resolution resolution) {

        public AttackState {
            attackId = requireText(attackId, "attackId");
            originalActorId = requireText(originalActorId, "originalActorId");
            targetPlayerId = requireText(targetPlayerId, "targetPlayerId");
            Objects.requireNonNull(phase, "phase must not be null");
            Objects.requireNonNull(warningDeadline, "warningDeadline must not be null");
            switch (phase) {
                case WARNING -> {
                    if (overlayExpiresAt != null || resolution != null) {
                        throw new IllegalArgumentException("warning attack must not have an effect or resolution");
                    }
                }
                case ACTIVE -> {
                    if (overlayExpiresAt == null || resolution != null) {
                        throw new IllegalArgumentException("active attack requires only an expiry");
                    }
                }
                case RESOLVED -> {
                    if (resolution == null) {
                        throw new IllegalArgumentException("resolved attack requires a resolution");
                    }
                    if ((resolution == Resolution.EXPIRED) != (overlayExpiresAt != null)) {
                        throw new IllegalArgumentException("expired resolution requires its effect expiry");
                    }
                }
            }
        }
    }

    public record OverlayEffect(
            String attackId,
            String targetPlayerId,
            long overlaySeed,
            Instant expiresAt) {

        public OverlayEffect {
            attackId = requireText(attackId, "attackId");
            targetPlayerId = requireText(targetPlayerId, "targetPlayerId");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    public record AttackEvent(
            long sequence,
            EventType type,
            String playerId,
            String key,
            int energyDelta,
            int energyAfter,
            AttackState attackState,
            Instant occurredAt) {

        public AttackEvent {
            if (sequence <= 0) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            Objects.requireNonNull(type, "type must not be null");
            playerId = requireText(playerId, "playerId");
            key = requireText(key, "key");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            if (type == EventType.ENERGY_GRANTED) {
                if (energyDelta < 0 || attackState != null) {
                    throw new IllegalArgumentException("energy grant event is inconsistent");
                }
            } else {
                Objects.requireNonNull(attackState, "attackState must not be null");
            }
            validateAttackTransition(type, attackState);
        }
    }

    private final String matchId;
    private final String playerOneId;
    private final String playerTwoId;
    private final Clock clock;
    private final Policy policy;
    private final LongSupplier overlaySeeds;
    private final Map<String, Integer> energies = new LinkedHashMap<>();
    private final Map<String, Set<String>> grantedKeys = new LinkedHashMap<>();
    private final Set<String> usedAttackIds = new LinkedHashSet<>();
    private final List<AttackEvent> events = new ArrayList<>();
    private AttackState currentAttack;

    public GarbageAttackExchange(
            String matchId,
            String playerOneId,
            String playerTwoId,
            Clock clock,
            Policy policy,
            LongSupplier overlaySeeds) {
        this.matchId = requireText(matchId, "matchId");
        this.playerOneId = requireText(playerOneId, "playerOneId");
        this.playerTwoId = requireText(playerTwoId, "playerTwoId");
        if (playerOneId.equals(playerTwoId)) {
            throw new IllegalArgumentException("attack participants must be distinct");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.overlaySeeds = Objects.requireNonNull(overlaySeeds, "overlaySeeds must not be null");
        energies.put(playerOneId, 0);
        energies.put(playerTwoId, 0);
        grantedKeys.put(playerOneId, new LinkedHashSet<>());
        grantedKeys.put(playerTwoId, new LinkedHashSet<>());
    }

    public static GarbageAttackExchange diagnosticReplay(
            String matchId,
            String playerOneId,
            String playerTwoId,
            Clock clock,
            Policy policy,
            LongSupplier overlaySeeds,
            List<AttackEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        GarbageAttackExchange replayed = new GarbageAttackExchange(
                matchId, playerOneId, playerTwoId, clock, policy, overlaySeeds);
        for (AttackEvent event : events) {
            replayed.apply(Objects.requireNonNull(event, "event must not be null"));
            replayed.events.add(event);
        }
        return replayed;
    }

    public synchronized boolean awardProgress(String playerId, String progressMarker) {
        requireParticipant(playerId);
        return grantEnergy(
                playerId,
                "progress:" + requireText(progressMarker, "progressMarker"),
                policy.progressGain());
    }

    public synchronized boolean awardPassive(String playerId) {
        long bucket = Math.floorDiv(clock.millis(), policy.passiveInterval().toMillis());
        return awardPassive(playerId, bucket);
    }

    public synchronized boolean awardPassive(String playerId, long bucket) {
        requireParticipant(playerId);
        if (bucket <= 0) {
            throw new IllegalArgumentException("passive bucket must be positive");
        }
        return grantEnergy(playerId, "passive:" + bucket, policy.passiveGain());
    }

    public synchronized AttackState launch(String attackId, String actorPlayerId) {
        advanceTime();
        requireParticipant(actorPlayerId);
        attackId = requireText(attackId, "attackId");
        if (currentAttack != null && currentAttack.phase() != Phase.RESOLVED) {
            throw new IllegalStateException("another attack is still active");
        }
        if (usedAttackIds.contains(attackId)) {
            throw new IllegalArgumentException("attackId has already been used");
        }
        requireEnergy(actorPlayerId, policy.attackCost());
        Instant now = clock.instant();
        AttackState warned = new AttackState(
                attackId,
                actorPlayerId,
                opponent(actorPlayerId),
                false,
                Phase.WARNING,
                now.plus(policy.warningDuration()),
                null,
                overlaySeeds.getAsLong(),
                null);
        emit(
                EventType.ATTACK_WARNED,
                actorPlayerId,
                attackId,
                -policy.attackCost(),
                energy(actorPlayerId) - policy.attackCost(),
                warned,
                now);
        return currentAttack;
    }

    public synchronized AttackState block(String attackId, String playerId) {
        advanceTime();
        AttackState warning = requireCounterable(attackId, playerId);
        requireEnergy(playerId, policy.blockCost());
        AttackState blocked = new AttackState(
                warning.attackId(),
                warning.originalActorId(),
                warning.targetPlayerId(),
                warning.reflected(),
                Phase.RESOLVED,
                warning.warningDeadline(),
                null,
                warning.overlaySeed(),
                Resolution.BLOCKED);
        emit(
                EventType.ATTACK_BLOCKED,
                playerId,
                warning.attackId(),
                -policy.blockCost(),
                energy(playerId) - policy.blockCost(),
                blocked,
                clock.instant());
        return currentAttack;
    }

    public synchronized AttackState reflect(String attackId, String playerId) {
        advanceTime();
        AttackState warning = requireCounterable(attackId, playerId);
        if (warning.reflected()) {
            throw new IllegalStateException("attack was already reflected");
        }
        requireEnergy(playerId, policy.reflectCost());
        Instant now = clock.instant();
        AttackState reflected = new AttackState(
                warning.attackId(),
                warning.originalActorId(),
                warning.originalActorId(),
                true,
                Phase.WARNING,
                now.plus(policy.warningDuration()),
                null,
                warning.overlaySeed(),
                null);
        emit(
                EventType.ATTACK_REFLECTED,
                playerId,
                warning.attackId(),
                -policy.reflectCost(),
                energy(playerId) - policy.reflectCost(),
                reflected,
                now);
        return currentAttack;
    }

    public synchronized void advanceTime() {
        Instant now = clock.instant();
        boolean advanced;
        do {
            advanced = false;
            if (currentAttack != null
                    && currentAttack.phase() == Phase.WARNING
                    && !now.isBefore(currentAttack.warningDeadline())) {
                Instant expiresAt = currentAttack.warningDeadline().plus(policy.effectDuration());
                AttackState active = new AttackState(
                        currentAttack.attackId(),
                        currentAttack.originalActorId(),
                        currentAttack.targetPlayerId(),
                        currentAttack.reflected(),
                        Phase.ACTIVE,
                        currentAttack.warningDeadline(),
                        expiresAt,
                        currentAttack.overlaySeed(),
                        null);
                emit(
                        EventType.OVERLAY_ACTIVATED,
                        active.targetPlayerId(),
                        active.attackId(),
                        0,
                        energy(active.targetPlayerId()),
                        active,
                        active.warningDeadline());
                advanced = true;
            }
            if (currentAttack != null
                    && currentAttack.phase() == Phase.ACTIVE
                    && !now.isBefore(currentAttack.overlayExpiresAt())) {
                AttackState expired = new AttackState(
                        currentAttack.attackId(),
                        currentAttack.originalActorId(),
                        currentAttack.targetPlayerId(),
                        currentAttack.reflected(),
                        Phase.RESOLVED,
                        currentAttack.warningDeadline(),
                        currentAttack.overlayExpiresAt(),
                        currentAttack.overlaySeed(),
                        Resolution.EXPIRED);
                emit(
                        EventType.OVERLAY_EXPIRED,
                        expired.targetPlayerId(),
                        expired.attackId(),
                        0,
                        energy(expired.targetPlayerId()),
                        expired,
                        expired.overlayExpiresAt());
                advanced = true;
            }
        } while (advanced && currentAttack.phase() != Phase.RESOLVED);
    }

    public synchronized int energy(String playerId) {
        requireParticipant(playerId);
        return energies.get(playerId);
    }

    public synchronized Optional<AttackState> currentAttack() {
        return Optional.ofNullable(currentAttack);
    }

    public synchronized Optional<OverlayEffect> activeOverlay() {
        if (currentAttack == null || currentAttack.phase() != Phase.ACTIVE) {
            return Optional.empty();
        }
        return Optional.of(new OverlayEffect(
                currentAttack.attackId(),
                currentAttack.targetPlayerId(),
                currentAttack.overlaySeed(),
                currentAttack.overlayExpiresAt()));
    }

    public synchronized List<AttackEvent> events() {
        return List.copyOf(events);
    }

    public String matchId() {
        return matchId;
    }

    private boolean grantEnergy(String playerId, String key, int requestedGain) {
        if (grantedKeys.get(playerId).contains(key)) {
            return false;
        }
        int before = energy(playerId);
        int after = Math.min(policy.maximumEnergy(), before + requestedGain);
        emit(
                EventType.ENERGY_GRANTED,
                playerId,
                key,
                after - before,
                after,
                null,
                clock.instant());
        return true;
    }

    private AttackState requireCounterable(String attackId, String playerId) {
        requireParticipant(playerId);
        if (currentAttack == null || currentAttack.phase() != Phase.WARNING) {
            throw new IllegalStateException("attack is outside its warning window");
        }
        if (!currentAttack.attackId().equals(attackId)) {
            throw new IllegalArgumentException("attackId does not match the active warning");
        }
        if (!currentAttack.targetPlayerId().equals(playerId)) {
            throw new IllegalArgumentException("only the warned target can counter");
        }
        return currentAttack;
    }

    private void requireEnergy(String playerId, int required) {
        if (energy(playerId) < required) {
            throw new IllegalStateException("insufficient attack energy");
        }
    }

    private String opponent(String playerId) {
        return playerOneId.equals(playerId) ? playerTwoId : playerOneId;
    }

    private void emit(
            EventType type,
            String playerId,
            String key,
            int energyDelta,
            int energyAfter,
            AttackState attackState,
            Instant occurredAt) {
        AttackEvent event = new AttackEvent(
                events.size() + 1L,
                type,
                playerId,
                key,
                energyDelta,
                energyAfter,
                attackState,
                occurredAt);
        apply(event);
        events.add(event);
    }

    private void apply(AttackEvent event) {
        if (event.sequence() != events.size() + 1L) {
            throw new IllegalArgumentException("attack event sequence is not contiguous");
        }
        requireParticipant(event.playerId());
        if (!events.isEmpty() && event.occurredAt().isBefore(events.getLast().occurredAt())) {
            throw new IllegalArgumentException("attack events must use monotonic server time");
        }
        int before = energies.get(event.playerId());
        if (event.energyAfter() < 0
                || event.energyAfter() > policy.maximumEnergy()
                || before + event.energyDelta() != event.energyAfter()) {
            throw new IllegalArgumentException("attack event energy transition is invalid");
        }
        validateEventSemantics(event, before);

        energies.put(event.playerId(), event.energyAfter());
        if (event.type() == EventType.ENERGY_GRANTED) {
            if (!grantedKeys.get(event.playerId()).add(event.key())) {
                throw new IllegalArgumentException("attack energy grant key was replayed");
            }
            return;
        }

        AttackState state = event.attackState();
        if (event.type() == EventType.ATTACK_WARNED && !usedAttackIds.add(state.attackId())) {
            throw new IllegalArgumentException("attackId was replayed");
        }
        currentAttack = state;
    }

    private void validateEventSemantics(AttackEvent event, int energyBefore) {
        if (event.type() == EventType.ENERGY_GRANTED) {
            int configuredGain;
            if (event.key().startsWith("progress:")) {
                configuredGain = policy.progressGain();
            } else if (event.key().startsWith("passive:")) {
                configuredGain = policy.passiveGain();
            } else {
                throw new IllegalArgumentException("attack energy grant key type is invalid");
            }
            int expectedAfter = Math.min(policy.maximumEnergy(), energyBefore + configuredGain);
            if (event.energyDelta() != expectedAfter - energyBefore
                    || event.energyAfter() != expectedAfter) {
                throw new IllegalArgumentException("attack energy grant amount is invalid");
            }
            return;
        }

        AttackState state = event.attackState();
        requireParticipant(state.originalActorId());
        requireParticipant(state.targetPlayerId());
        if (!state.attackId().equals(event.key())) {
            throw new IllegalArgumentException("attack event key does not match its state");
        }
        if (event.type() != EventType.ATTACK_WARNED
                && (currentAttack == null || !currentAttack.attackId().equals(state.attackId()))) {
            throw new IllegalArgumentException("attack transition has no matching predecessor");
        }

        switch (event.type()) {
            case ATTACK_WARNED -> validateWarnedEvent(event, state);
            case ATTACK_BLOCKED -> validateBlockedEvent(event, state);
            case ATTACK_REFLECTED -> validateReflectedEvent(event, state);
            case OVERLAY_ACTIVATED -> validateActivatedEvent(event, state);
            case OVERLAY_EXPIRED -> validateExpiredEvent(event, state);
            case ENERGY_GRANTED -> throw new IllegalStateException("energy grant was already validated");
        }
    }

    private void validateWarnedEvent(AttackEvent event, AttackState state) {
        if (currentAttack != null && currentAttack.phase() != Phase.RESOLVED) {
            throw new IllegalArgumentException("attack warning overlaps an unresolved attack");
        }
        requireEnergyCost(event, policy.attackCost());
        AttackState expected = new AttackState(
                state.attackId(),
                event.playerId(),
                opponent(event.playerId()),
                false,
                Phase.WARNING,
                event.occurredAt().plus(policy.warningDuration()),
                null,
                state.overlaySeed(),
                null);
        requireExpectedState(state, expected, "warning");
        if (usedAttackIds.contains(state.attackId())) {
            throw new IllegalArgumentException("attackId was replayed");
        }
    }

    private void validateBlockedEvent(AttackEvent event, AttackState state) {
        AttackState warning = requireReplayWarning(event);
        requireEnergyCost(event, policy.blockCost());
        AttackState expected = new AttackState(
                warning.attackId(),
                warning.originalActorId(),
                warning.targetPlayerId(),
                warning.reflected(),
                Phase.RESOLVED,
                warning.warningDeadline(),
                null,
                warning.overlaySeed(),
                Resolution.BLOCKED);
        requireExpectedState(state, expected, "block");
    }

    private void validateReflectedEvent(AttackEvent event, AttackState state) {
        AttackState warning = requireReplayWarning(event);
        if (warning.reflected()) {
            throw new IllegalArgumentException("attack replay contains a second reflection");
        }
        requireEnergyCost(event, policy.reflectCost());
        AttackState expected = new AttackState(
                warning.attackId(),
                warning.originalActorId(),
                warning.originalActorId(),
                true,
                Phase.WARNING,
                event.occurredAt().plus(policy.warningDuration()),
                null,
                warning.overlaySeed(),
                null);
        requireExpectedState(state, expected, "reflection");
    }

    private AttackState requireReplayWarning(AttackEvent event) {
        if (currentAttack == null || currentAttack.phase() != Phase.WARNING) {
            throw new IllegalArgumentException("attack counter has no warning predecessor");
        }
        if (!currentAttack.targetPlayerId().equals(event.playerId())) {
            throw new IllegalArgumentException("attack counter actor is not the warned target");
        }
        if (!event.occurredAt().isBefore(currentAttack.warningDeadline())) {
            throw new IllegalArgumentException("attack counter occurred outside the warning window");
        }
        return currentAttack;
    }

    private void validateActivatedEvent(AttackEvent event, AttackState state) {
        if (currentAttack == null || currentAttack.phase() != Phase.WARNING) {
            throw new IllegalArgumentException("overlay activation has no warning predecessor");
        }
        requireZeroEnergyDelta(event);
        AttackState expected = new AttackState(
                currentAttack.attackId(),
                currentAttack.originalActorId(),
                currentAttack.targetPlayerId(),
                currentAttack.reflected(),
                Phase.ACTIVE,
                currentAttack.warningDeadline(),
                currentAttack.warningDeadline().plus(policy.effectDuration()),
                currentAttack.overlaySeed(),
                null);
        requireExpectedState(state, expected, "activation");
        if (!event.playerId().equals(expected.targetPlayerId())
                || !event.occurredAt().equals(expected.warningDeadline())) {
            throw new IllegalArgumentException("overlay activation actor or time is invalid");
        }
    }

    private void validateExpiredEvent(AttackEvent event, AttackState state) {
        if (currentAttack == null || currentAttack.phase() != Phase.ACTIVE) {
            throw new IllegalArgumentException("overlay expiry has no active predecessor");
        }
        requireZeroEnergyDelta(event);
        AttackState expected = new AttackState(
                currentAttack.attackId(),
                currentAttack.originalActorId(),
                currentAttack.targetPlayerId(),
                currentAttack.reflected(),
                Phase.RESOLVED,
                currentAttack.warningDeadline(),
                currentAttack.overlayExpiresAt(),
                currentAttack.overlaySeed(),
                Resolution.EXPIRED);
        requireExpectedState(state, expected, "expiry");
        if (!event.playerId().equals(expected.targetPlayerId())
                || !event.occurredAt().equals(expected.overlayExpiresAt())) {
            throw new IllegalArgumentException("overlay expiry actor or time is invalid");
        }
    }

    private static void requireEnergyCost(AttackEvent event, int expectedCost) {
        if (event.energyDelta() != -expectedCost) {
            throw new IllegalArgumentException("attack event cost is invalid");
        }
    }

    private static void requireZeroEnergyDelta(AttackEvent event) {
        if (event.energyDelta() != 0) {
            throw new IllegalArgumentException("attack lifecycle event must not change energy");
        }
    }

    private static void requireExpectedState(
            AttackState actual, AttackState expected, String transition) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("attack " + transition + " state is invalid");
        }
    }

    private void requireParticipant(String playerId) {
        if (!energies.containsKey(playerId)) {
            throw new IllegalArgumentException("player is not a match participant");
        }
    }

    private static void validateAttackTransition(EventType type, AttackState state) {
        if (type == EventType.ENERGY_GRANTED) {
            return;
        }
        boolean valid = switch (type) {
            case ATTACK_WARNED -> state.phase() == Phase.WARNING && !state.reflected();
            case ATTACK_BLOCKED ->
                state.phase() == Phase.RESOLVED && state.resolution() == Resolution.BLOCKED;
            case ATTACK_REFLECTED -> state.phase() == Phase.WARNING && state.reflected();
            case OVERLAY_ACTIVATED -> state.phase() == Phase.ACTIVE;
            case OVERLAY_EXPIRED ->
                state.phase() == Phase.RESOLVED && state.resolution() == Resolution.EXPIRED;
            case ENERGY_GRANTED -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("attack event type does not match its state");
        }
    }

    private static Duration requirePositiveDuration(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
