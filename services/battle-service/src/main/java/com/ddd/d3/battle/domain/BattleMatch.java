package com.ddd.d3.battle.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
        DRAW,
        VOID
    }

    public enum ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    public enum ResolutionReason {
        SURRENDER,
        DISCONNECT_TIMEOUT,
        PLATFORM_INCIDENT,
        LEGACY_IMPORT
    }

    public sealed interface Command permits Ready, Start, BeginJudging, AdvanceTime, Disconnect,
            Reconnect, Surrender, PlatformIncident {}

    public record Ready(String playerId) implements Command {}

    public record Start(Duration duration) implements Command {}

    public record BeginJudging() implements Command {}

    public record AdvanceTime() implements Command {}

    public record Disconnect(String playerId, long connectionGeneration) implements Command {
        public Disconnect {
            if (connectionGeneration <= 0) {
                throw new IllegalArgumentException("connectionGeneration must be positive");
            }
        }
    }

    public record Reconnect(String playerId, long connectionGeneration) implements Command {
        public Reconnect {
            if (connectionGeneration <= 0) {
                throw new IllegalArgumentException("connectionGeneration must be positive");
            }
        }
    }

    public record Surrender(String playerId) implements Command {}

    public record PlatformIncident(String incidentReference) implements Command {}

    public record Result(
            Outcome outcome,
            String winnerId,
            ResolutionReason reason,
            Instant resolvedAt,
            String incidentReference) {}

    public record PlayerSnapshot(
            String playerId,
            boolean ready,
            ConnectionState connectionState,
            Long activeConnectionGeneration,
            Long completedConnectionGeneration,
            Instant reconnectDeadline) {}

    public record Snapshot(
            String matchId,
            String playerOneId,
            String playerTwoId,
            State state,
            Instant startedAt,
            Instant matchDeadline,
            boolean judgingStarted,
            Result result,
            long aggregateVersion,
            List<PlayerSnapshot> players) {}

    private final String matchId;
    private final String playerOneId;
    private final String playerTwoId;
    private final Clock clock;
    private final Set<String> readyPlayers = new HashSet<>();
    private final Map<String, ConnectionState> connectionStates = new LinkedHashMap<>();
    private final Map<String, Long> reconnectGenerations = new LinkedHashMap<>();
    private final Map<String, Long> successfullyReconnectedGenerations = new LinkedHashMap<>();
    private final Map<String, Instant> reconnectDeadlines = new LinkedHashMap<>();
    private State state = State.LOBBY;
    private Instant startedAt;
    private Instant matchDeadline;
    private boolean judgingStarted;
    private Result result;
    private long aggregateVersion;

    public BattleMatch(String matchId, String playerOneId, String playerTwoId, Clock clock) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.playerOneId = Objects.requireNonNull(playerOneId, "playerOneId");
        this.playerTwoId = Objects.requireNonNull(playerTwoId, "playerTwoId");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (playerOneId.equals(playerTwoId)) {
            throw new IllegalArgumentException("match participants must be distinct");
        }
        connectionStates.put(playerOneId, ConnectionState.CONNECTING);
        connectionStates.put(playerTwoId, ConnectionState.CONNECTING);
    }

    public synchronized void handle(Command command) {
        Objects.requireNonNull(command, "command");
        DomainState before = domainState();
        switch (command) {
            case Ready ready -> ready(ready.playerId());
            case Start start -> start(start.duration());
            case BeginJudging ignored -> beginJudging();
            case AdvanceTime ignored -> advanceTime();
            case Disconnect disconnect -> disconnect(disconnect.playerId(), disconnect.connectionGeneration());
            case Reconnect reconnect -> reconnect(reconnect.playerId(), reconnect.connectionGeneration());
            case Surrender surrender -> surrender(surrender.playerId());
            case PlatformIncident incident -> platformIncident(incident.incidentReference());
        }
        if (!before.equals(domainState())) {
            aggregateVersion++;
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

    public synchronized long aggregateVersion() {
        return aggregateVersion;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                matchId,
                playerOneId,
                playerTwoId,
                state,
                startedAt,
                matchDeadline,
                judgingStarted,
                result,
                aggregateVersion,
                List.of(playerSnapshot(playerOneId), playerSnapshot(playerTwoId)));
    }

    public static BattleMatch restore(Snapshot snapshot, Clock clock) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        BattleMatch match = new BattleMatch(
                snapshot.matchId(), snapshot.playerOneId(), snapshot.playerTwoId(), clock);
        if (snapshot.aggregateVersion() < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        if (snapshot.players() == null || snapshot.players().size() != 2) {
            throw new IllegalArgumentException("snapshot must contain exactly two players");
        }
        Map<String, PlayerSnapshot> players = new LinkedHashMap<>();
        for (PlayerSnapshot player : snapshot.players()) {
            Objects.requireNonNull(player, "player snapshot must not be null");
            if (players.put(player.playerId(), player) != null) {
                throw new IllegalArgumentException("snapshot players must be distinct");
            }
        }
        if (!players.keySet().equals(Set.of(snapshot.playerOneId(), snapshot.playerTwoId()))) {
            throw new IllegalArgumentException("snapshot players do not match the aggregate");
        }

        match.state = Objects.requireNonNull(snapshot.state(), "state must not be null");
        match.startedAt = snapshot.startedAt();
        match.matchDeadline = snapshot.matchDeadline();
        match.judgingStarted = snapshot.judgingStarted();
        match.result = snapshot.result();
        match.aggregateVersion = snapshot.aggregateVersion();
        for (PlayerSnapshot player : players.values()) {
            match.restorePlayer(player);
        }
        match.validateRestoredState();
        return match;
    }

    public synchronized boolean isDisconnected(String playerId) {
        requireParticipant(playerId);
        return connectionStates.get(playerId) == ConnectionState.DISCONNECTED;
    }

    public synchronized ConnectionState connectionState(String playerId) {
        requireParticipant(playerId);
        return connectionStates.get(playerId);
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
        connectionStates.put(playerId, ConnectionState.CONNECTED);
        if (readyPlayers.size() == 2) {
            state = State.READY;
        }
    }

    private void start(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        if (startedAt != null) {
            if (startedAt.plus(duration).equals(matchDeadline)) {
                return;
            }
            throw new IllegalStateException("Start duration does not match the accepted command");
        }
        requireState(State.READY);
        Instant acceptedStartedAt = clock.instant();
        Instant acceptedDeadline = acceptedStartedAt.plus(duration);
        startedAt = acceptedStartedAt;
        matchDeadline = acceptedDeadline;
        state = State.RUNNING;
    }

    private void beginJudging() {
        if (judgingStarted) {
            return;
        }
        requireState(State.RUNNING);
        advanceTime();
        if (result != null || state == State.JUDGING) {
            judgingStarted = state == State.JUDGING;
            return;
        }
        state = State.JUDGING;
        judgingStarted = true;
    }

    private void advanceTime() {
        if (result != null) {
            return;
        }

        Instant now = clock.instant();
        Optional<String> expiredPlayer = reconnectDeadlines.entrySet().stream()
                .filter(entry -> !now.isBefore(entry.getValue()))
                .min(Comparator.comparing(Map.Entry<String, Instant>::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey);
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
            judgingStarted = true;
        }
    }

    private void disconnect(String playerId, long connectionGeneration) {
        requireParticipant(playerId);
        advanceTime();
        if (result != null) {
            return;
        }
        requireState(State.RUNNING);
        Long activeGeneration = reconnectGenerations.get(playerId);
        if (activeGeneration != null) {
            if (connectionGeneration <= activeGeneration) {
                return;
            }
            successfullyReconnectedGenerations.merge(playerId, activeGeneration, Math::max);
            reconnectGenerations.put(playerId, connectionGeneration);
            reconnectDeadlines.put(playerId, clock.instant().plus(RECONNECT_GRACE_PERIOD));
            connectionStates.put(playerId, ConnectionState.DISCONNECTED);
            return;
        }
        Long completedGeneration = successfullyReconnectedGenerations.get(playerId);
        if (completedGeneration != null && connectionGeneration <= completedGeneration) {
            return;
        }
        reconnectGenerations.put(playerId, connectionGeneration);
        reconnectDeadlines.putIfAbsent(playerId, clock.instant().plus(RECONNECT_GRACE_PERIOD));
        connectionStates.put(playerId, ConnectionState.DISCONNECTED);
    }

    private void reconnect(String playerId, long connectionGeneration) {
        requireParticipant(playerId);
        Long activeGeneration = reconnectGenerations.get(playerId);
        if (activeGeneration == null) {
            Long completedGeneration = successfullyReconnectedGenerations.get(playerId);
            if (completedGeneration != null && connectionGeneration <= completedGeneration) {
                return;
            }
            throw new IllegalStateException("Player is not disconnected");
        }
        if (connectionGeneration < activeGeneration) {
            return;
        }
        if (connectionGeneration != activeGeneration) {
            throw new IllegalStateException("Reconnect generation does not match active disconnect");
        }
        advanceTime();
        if (result == null) {
            reconnectDeadlines.remove(playerId);
            reconnectGenerations.remove(playerId);
            successfullyReconnectedGenerations.put(playerId, connectionGeneration);
            connectionStates.put(playerId, ConnectionState.CONNECTED);
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

    private PlayerSnapshot playerSnapshot(String playerId) {
        return new PlayerSnapshot(
                playerId,
                readyPlayers.contains(playerId),
                connectionStates.get(playerId),
                reconnectGenerations.get(playerId),
                successfullyReconnectedGenerations.get(playerId),
                reconnectDeadlines.get(playerId));
    }

    private void restorePlayer(PlayerSnapshot player) {
        Objects.requireNonNull(player.connectionState(), "connectionState must not be null");
        if (player.activeConnectionGeneration() != null && player.activeConnectionGeneration() <= 0) {
            throw new IllegalArgumentException("active connection generation must be positive");
        }
        if (player.completedConnectionGeneration() != null && player.completedConnectionGeneration() <= 0) {
            throw new IllegalArgumentException("completed connection generation must be positive");
        }
        boolean disconnected = player.connectionState() == ConnectionState.DISCONNECTED;
        if (disconnected != (player.activeConnectionGeneration() != null)
                || disconnected != (player.reconnectDeadline() != null)) {
            throw new IllegalArgumentException("disconnected state, generation, and deadline must be atomic");
        }
        if (player.activeConnectionGeneration() != null
                && player.completedConnectionGeneration() != null
                && player.completedConnectionGeneration() >= player.activeConnectionGeneration()) {
            throw new IllegalArgumentException("completed generation must precede the active generation");
        }
        if (player.ready()) {
            readyPlayers.add(player.playerId());
        }
        if (player.ready() && player.connectionState() == ConnectionState.CONNECTING) {
            throw new IllegalArgumentException("ready player cannot remain connecting");
        }
        connectionStates.put(player.playerId(), player.connectionState());
        if (player.activeConnectionGeneration() != null) {
            reconnectGenerations.put(player.playerId(), player.activeConnectionGeneration());
            reconnectDeadlines.put(player.playerId(), player.reconnectDeadline());
        }
        if (player.completedConnectionGeneration() != null) {
            successfullyReconnectedGenerations.put(
                    player.playerId(), player.completedConnectionGeneration());
        }
    }

    private void validateRestoredState() {
        boolean hasClock = startedAt != null && matchDeadline != null;
        if ((startedAt == null) != (matchDeadline == null)
                || (hasClock && !matchDeadline.isAfter(startedAt))) {
            throw new IllegalArgumentException("snapshot match clock is invalid");
        }
        if ((state == State.LOBBY || state == State.READY) && hasClock) {
            throw new IllegalArgumentException("pre-start snapshot must not have a match clock");
        }
        if ((state == State.RUNNING || state == State.JUDGING || state == State.FINISHED) && !hasClock) {
            throw new IllegalArgumentException("started snapshot must have a match clock");
        }
        if ((state == State.FINISHED) != (result != null)) {
            throw new IllegalArgumentException("terminal snapshot and result must be atomic");
        }
        if (state == State.READY && readyPlayers.size() != 2) {
            throw new IllegalArgumentException("ready snapshot requires both players");
        }
        if (state == State.LOBBY && readyPlayers.size() == 2) {
            throw new IllegalArgumentException("lobby snapshot cannot have both players ready");
        }
        if (state != State.LOBBY && readyPlayers.size() != 2) {
            throw new IllegalArgumentException("started snapshot requires both players ready");
        }
        if (state == State.JUDGING && !judgingStarted) {
            throw new IllegalArgumentException("judging snapshot must retain its transition marker");
        }
        if (result != null) {
            validateResult(result);
        }
    }

    private void validateResult(Result restoredResult) {
        Objects.requireNonNull(restoredResult.outcome(), "result outcome must not be null");
        Objects.requireNonNull(restoredResult.reason(), "result reason must not be null");
        Objects.requireNonNull(restoredResult.resolvedAt(), "result resolvedAt must not be null");
        switch (restoredResult.outcome()) {
            case WIN -> {
                requireParticipant(restoredResult.winnerId());
                if (restoredResult.incidentReference() != null
                        || restoredResult.reason() == ResolutionReason.PLATFORM_INCIDENT) {
                    throw new IllegalArgumentException("winning result must have a player resolution reason");
                }
            }
            case DRAW -> {
                if (restoredResult.winnerId() != null
                        || restoredResult.incidentReference() != null
                        || restoredResult.reason() != ResolutionReason.LEGACY_IMPORT) {
                    throw new IllegalArgumentException("legacy draw requires only the imported resolution reason");
                }
            }
            case VOID -> {
                if (restoredResult.winnerId() != null
                        || restoredResult.incidentReference() == null
                        || restoredResult.incidentReference().isBlank()
                        || restoredResult.reason() != ResolutionReason.PLATFORM_INCIDENT) {
                    throw new IllegalArgumentException("void result requires only an incident reference");
                }
            }
        }
    }

    private DomainState domainState() {
        return new DomainState(
                state,
                Set.copyOf(readyPlayers),
                Map.copyOf(connectionStates),
                Map.copyOf(reconnectGenerations),
                Map.copyOf(successfullyReconnectedGenerations),
                Map.copyOf(reconnectDeadlines),
                startedAt,
                matchDeadline,
                judgingStarted,
                result);
    }

    private record DomainState(
            State state,
            Set<String> readyPlayers,
            Map<String, ConnectionState> connectionStates,
            Map<String, Long> reconnectGenerations,
            Map<String, Long> successfullyReconnectedGenerations,
            Map<String, Instant> reconnectDeadlines,
            Instant startedAt,
            Instant matchDeadline,
            boolean judgingStarted,
            Result result) {}
}
