package com.ddd.d3.battle.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class BattleMatch {

    private static final Duration RECONNECT_GRACE_PERIOD = Duration.ofSeconds(30);

    public enum State {
        LOBBY,
        READY,
        RUNNING,
        JUDGING,
        FINISHED
    }

    public enum Outcome {
        WIN,
        VOID
    }

    public enum ResolutionReason {
        SURRENDER,
        DISCONNECT_TIMEOUT,
        PLATFORM_INCIDENT
    }

    public sealed interface Command permits Ready, Start, BeginJudging, AdvanceTime, Disconnect,
            Reconnect, Surrender, PlatformIncident {}

    public record Ready(String playerId) implements Command {}

    public record Start(Duration duration) implements Command {}

    public record BeginJudging() implements Command {}

    public record AdvanceTime() implements Command {}

    public record Disconnect(String playerId) implements Command {}

    public record Reconnect(String playerId) implements Command {}

    public record Surrender(String playerId) implements Command {}

    public record PlatformIncident(String incidentReference) implements Command {}

    public record Result(
            Outcome outcome,
            String winnerId,
            ResolutionReason reason,
            Instant resolvedAt,
            String incidentReference) {}

    private final String matchId;
    private final String playerOneId;
    private final String playerTwoId;
    private final Clock clock;
    private final Set<String> readyPlayers = new HashSet<>();
    private final Set<String> successfullyReconnectedPlayers = new HashSet<>();
    private final Map<String, Instant> reconnectDeadlines = new LinkedHashMap<>();
    private State state = State.LOBBY;
    private Instant startedAt;
    private Instant matchDeadline;
    private Result result;

    public BattleMatch(String matchId, String playerOneId, String playerTwoId, Clock clock) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.playerOneId = Objects.requireNonNull(playerOneId, "playerOneId");
        this.playerTwoId = Objects.requireNonNull(playerTwoId, "playerTwoId");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (playerOneId.equals(playerTwoId)) {
            throw new IllegalArgumentException("match participants must be distinct");
        }
    }

    public synchronized void handle(Command command) {
        Objects.requireNonNull(command, "command");
        switch (command) {
            case Ready ready -> ready(ready.playerId());
            case Start start -> start(start.duration());
            case BeginJudging ignored -> beginJudging();
            case AdvanceTime ignored -> advanceTime();
            case Disconnect disconnect -> disconnect(disconnect.playerId());
            case Reconnect reconnect -> reconnect(reconnect.playerId());
            case Surrender surrender -> surrender(surrender.playerId());
            case PlatformIncident incident -> platformIncident(incident.incidentReference());
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Instant startedAt() {
        return startedAt;
    }

    public synchronized Instant matchDeadline() {
        return matchDeadline;
    }

    public synchronized Optional<Result> result() {
        return Optional.ofNullable(result);
    }

    public synchronized boolean isDisconnected(String playerId) {
        requireParticipant(playerId);
        return reconnectDeadlines.containsKey(playerId);
    }

    public synchronized Optional<Instant> reconnectDeadline(String playerId) {
        requireParticipant(playerId);
        return Optional.ofNullable(reconnectDeadlines.get(playerId));
    }

    private void ready(String playerId) {
        requireParticipant(playerId);
        if (readyPlayers.contains(playerId)) {
            return;
        }
        requireState(State.LOBBY);
        readyPlayers.add(playerId);
        if (readyPlayers.size() == 2) {
            state = State.READY;
        }
    }

    private void start(Duration duration) {
        requireState(State.READY);
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        startedAt = clock.instant();
        matchDeadline = startedAt.plus(duration);
        state = State.RUNNING;
    }

    private void beginJudging() {
        requireState(State.RUNNING);
        state = State.JUDGING;
    }

    private void advanceTime() {
        if (result != null) {
            return;
        }

        Instant now = clock.instant();
        Optional<String> expiredPlayer = reconnectDeadlines.entrySet().stream()
                .filter(entry -> !now.isBefore(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
        if (expiredPlayer.isPresent()) {
            finish(new Result(
                    Outcome.WIN,
                    opponentOf(expiredPlayer.orElseThrow()),
                    ResolutionReason.DISCONNECT_TIMEOUT,
                    now,
                    null));
            return;
        }

        if (state == State.RUNNING && !now.isBefore(matchDeadline)) {
            state = State.JUDGING;
        }
    }

    private void disconnect(String playerId) {
        requireParticipant(playerId);
        advanceTime();
        requireState(State.RUNNING);
        successfullyReconnectedPlayers.remove(playerId);
        reconnectDeadlines.putIfAbsent(playerId, clock.instant().plus(RECONNECT_GRACE_PERIOD));
    }

    private void reconnect(String playerId) {
        requireParticipant(playerId);
        if (!reconnectDeadlines.containsKey(playerId)) {
            if (successfullyReconnectedPlayers.contains(playerId)) {
                return;
            }
            throw new IllegalStateException("Player is not disconnected");
        }
        advanceTime();
        if (result == null) {
            reconnectDeadlines.remove(playerId);
            successfullyReconnectedPlayers.add(playerId);
        }
    }

    private void surrender(String playerId) {
        requireParticipant(playerId);
        if (result != null) {
            return;
        }
        advanceTime();
        if (result != null) {
            return;
        }
        requireState(State.RUNNING);
        finish(new Result(
                Outcome.WIN,
                opponentOf(playerId),
                ResolutionReason.SURRENDER,
                clock.instant(),
                null));
    }

    private void platformIncident(String incidentReference) {
        if (incidentReference == null || incidentReference.isBlank()) {
            throw new IllegalArgumentException("incidentReference must not be blank");
        }
        if (result != null) {
            return;
        }
        advanceTime();
        if (result != null) {
            return;
        }
        if (state != State.RUNNING && state != State.JUDGING) {
            throw new IllegalStateException("Platform incident cannot finish match from " + state);
        }
        finish(new Result(
                Outcome.VOID,
                null,
                ResolutionReason.PLATFORM_INCIDENT,
                clock.instant(),
                incidentReference));
    }

    private void finish(Result terminalResult) {
        if (result != null) {
            return;
        }
        result = terminalResult;
        state = State.FINISHED;
    }

    private String opponentOf(String playerId) {
        return playerOneId.equals(playerId) ? playerTwoId : playerOneId;
    }

    private void requireParticipant(String playerId) {
        if (!playerOneId.equals(playerId) && !playerTwoId.equals(playerId)) {
            throw new IllegalArgumentException("player is not part of match " + matchId);
        }
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected " + expected + " but match was " + state);
        }
    }
}
