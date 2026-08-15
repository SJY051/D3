package com.ddd.d3.battle.infrastructure.persistence;

import com.ddd.d3.battle.application.BattleMatchRepository;
import com.ddd.d3.battle.application.BattleDeadlineClaimStore;
import com.ddd.d3.battle.application.OptimisticMatchConflictException;
import com.ddd.d3.battle.domain.BattleMatch;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBattleMatchRepository implements BattleMatchRepository, BattleDeadlineClaimStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcBattleMatchRepository(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<BattleMatch.Snapshot> findById(UUID matchId) {
        Objects.requireNonNull(matchId, "matchId must not be null");
        return transactions.execute(status -> findInsideTransaction(matchId));
    }

    @Override
    public void save(BattleMatch.Snapshot snapshot, long expectedVersion) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (expectedVersion < 0 || snapshot.aggregateVersion() != expectedVersion + 1) {
            throw new IllegalArgumentException("snapshot must advance expectedVersion exactly once");
        }
        Objects.requireNonNull(transactions.execute(status -> {
            saveInsideTransaction(snapshot, expectedVersion);
            return Boolean.TRUE;
        }));
    }

    @Override
    public Optional<BattleMatch.Snapshot> claimNextDue(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        return Objects.requireNonNull(transactions.execute(status -> jdbc.sql("""
                        select battle_match.id
                        from match battle_match
                        where battle_match.status = 'RUNNING'
                          and (
                              battle_match.deadline_at <= :cutoff
                              or exists (
                                  select 1
                                  from match_player player
                                  where player.match_id = battle_match.id
                                    and player.connection_state = 'DISCONNECTED'
                                    and player.reconnect_deadline_at <= :cutoff
                              )
                          )
                        order by least(
                            battle_match.deadline_at,
                            coalesce(
                                (
                                    select min(player.reconnect_deadline_at)
                                    from match_player player
                                    where player.match_id = battle_match.id
                                      and player.connection_state = 'DISCONNECTED'
                                ),
                                battle_match.deadline_at
                            )
                        ), battle_match.id
                        limit 1
                        for update of battle_match skip locked
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .query(UUID.class)
                .optional()
                .flatMap(this::findInsideTransaction)));
    }

    private Optional<BattleMatch.Snapshot> findInsideTransaction(UUID matchId) {
        List<SnapshotRow> rows = jdbc.sql("""
                        select battle_match.status,
                               battle_match.result,
                               battle_match.server_started_at,
                               battle_match.deadline_at,
                               battle_match.finished_at,
                               battle_match.void_reason,
                               battle_match.resolution_reason,
                               battle_match.aggregate_version,
                               player.user_id as player_user_id,
                               player.seat as player_seat,
                               player.ready as player_ready,
                               player.connection_state as player_connection_state,
                               player.connection_generation as player_connection_generation,
                               player.reconnect_deadline_at as player_reconnect_deadline_at
                        from match battle_match
                        left join match_player player on player.match_id = battle_match.id
                        where battle_match.id = :matchId
                        order by player.seat
                        """)
                .param("matchId", matchId)
                .query((resultSet, rowNumber) -> {
                    MatchRow match = new MatchRow(
                            resultSet.getString("status"),
                            resultSet.getString("result"),
                            instant(resultSet.getTimestamp("server_started_at")),
                            instant(resultSet.getTimestamp("deadline_at")),
                            instant(resultSet.getTimestamp("finished_at")),
                            resultSet.getString("void_reason"),
                            resultSet.getString("resolution_reason"),
                            resultSet.getLong("aggregate_version"));
                    UUID playerId = resultSet.getObject("player_user_id", UUID.class);
                    PlayerRow player = playerId == null
                            ? null
                            : new PlayerRow(
                                    playerId,
                                    resultSet.getInt("player_seat"),
                                    resultSet.getBoolean("player_ready"),
                                    BattleMatch.ConnectionState.valueOf(
                                            resultSet.getString("player_connection_state")),
                                    resultSet.getLong("player_connection_generation"),
                                    instant(resultSet.getTimestamp("player_reconnect_deadline_at")));
                    return new SnapshotRow(match, player);
                })
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<PlayerRow> players = rows.stream().map(SnapshotRow::player).toList();
        if (players.size() != 2
                || players.get(0) == null
                || players.get(1) == null
                || players.get(0).seat() != 1
                || players.get(1).seat() != 2) {
            throw new IllegalStateException("Battle match must have two ordered players");
        }

        MatchRow row = rows.get(0).match();
        if (!row.equals(rows.get(1).match())) {
            throw new IllegalStateException("Battle match snapshot rows are inconsistent");
        }
        String playerOneId = players.get(0).userId().toString();
        String playerTwoId = players.get(1).userId().toString();
        BattleMatch.State state = BattleMatch.State.valueOf(row.status());
        BattleMatch.Result result = restoreResult(row, playerOneId, playerTwoId);
        return Optional.of(new BattleMatch.Snapshot(
                matchId.toString(),
                playerOneId,
                playerTwoId,
                state,
                row.startedAt(),
                row.deadlineAt(),
                state == BattleMatch.State.JUDGING,
                result,
                row.aggregateVersion(),
                players.stream().map(JdbcBattleMatchRepository::snapshot).toList()));
    }

    private void saveInsideTransaction(BattleMatch.Snapshot snapshot, long expectedVersion) {
        UUID matchId = UUID.fromString(snapshot.matchId());
        String persistedResult = persistedResult(snapshot);
        BattleMatch.Result result = snapshot.result();
        int updated = jdbc.sql("""
                        update match
                        set status = :status,
                            result = :result,
                            server_started_at = :startedAt,
                            deadline_at = :deadlineAt,
                            finished_at = :finishedAt,
                            void_reason = :voidReason,
                            resolution_reason = :resolutionReason,
                            aggregate_version = :newVersion
                        where id = :matchId and aggregate_version = :expectedVersion
                        """)
                .param("status", snapshot.state().name())
                .param("result", persistedResult)
                .param("startedAt", timestamp(snapshot.startedAt()))
                .param("deadlineAt", timestamp(snapshot.matchDeadline()))
                .param("finishedAt", timestamp(result == null ? null : result.resolvedAt()))
                .param("voidReason", result == null ? null : result.incidentReference())
                .param("resolutionReason", result == null ? null : result.reason().name())
                .param("newVersion", snapshot.aggregateVersion())
                .param("matchId", matchId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated != 1) {
            throw new OptimisticMatchConflictException();
        }
        for (BattleMatch.PlayerSnapshot player : snapshot.players()) {
            long connectionGeneration = Optional.ofNullable(player.activeConnectionGeneration())
                    .orElse(Optional.ofNullable(player.completedConnectionGeneration()).orElse(0L));
            int playerUpdated = jdbc.sql("""
                            update match_player
                            set ready = :ready,
                                connection_state = :connectionState,
                                connection_generation = :connectionGeneration,
                                reconnect_deadline_at = :reconnectDeadline
                            where match_id = :matchId and user_id = :playerId
                            """)
                    .param("ready", player.ready())
                    .param("connectionState", player.connectionState().name())
                    .param("connectionGeneration", connectionGeneration)
                    .param("reconnectDeadline", timestamp(player.reconnectDeadline()))
                    .param("matchId", matchId)
                    .param("playerId", UUID.fromString(player.playerId()))
                    .update();
            if (playerUpdated != 1) {
                throw new IllegalStateException("Battle match player is missing");
            }
        }
    }

    private static BattleMatch.PlayerSnapshot snapshot(PlayerRow player) {
        boolean disconnected = player.connectionState() == BattleMatch.ConnectionState.DISCONNECTED;
        Long generation = player.connectionGeneration() == 0 ? null : player.connectionGeneration();
        return new BattleMatch.PlayerSnapshot(
                player.userId().toString(),
                player.ready(),
                player.connectionState(),
                disconnected ? generation : null,
                disconnected ? null : generation,
                player.reconnectDeadline());
    }

    private static BattleMatch.Result restoreResult(MatchRow row, String playerOneId, String playerTwoId) {
        if (row.result() == null) {
            return null;
        }
        BattleMatch.ResolutionReason reason = BattleMatch.ResolutionReason.valueOf(
                Objects.requireNonNull(row.resolutionReason(), "terminal match resolution reason"));
        return switch (row.result()) {
            case "PLAYER_ONE_WIN" -> new BattleMatch.Result(
                    BattleMatch.Outcome.WIN, playerOneId, reason, row.finishedAt(), null);
            case "PLAYER_TWO_WIN" -> new BattleMatch.Result(
                    BattleMatch.Outcome.WIN, playerTwoId, reason, row.finishedAt(), null);
            case "DRAW" -> new BattleMatch.Result(
                    BattleMatch.Outcome.DRAW, null, reason, row.finishedAt(), null);
            case "VOIDED" -> new BattleMatch.Result(
                    BattleMatch.Outcome.VOID, null, reason, row.finishedAt(), row.voidReason());
            default -> throw new IllegalStateException("Unsupported BattleMatch result: " + row.result());
        };
    }

    private static String persistedResult(BattleMatch.Snapshot snapshot) {
        BattleMatch.Result result = snapshot.result();
        if (result == null) {
            return null;
        }
        if (result.outcome() == BattleMatch.Outcome.VOID) {
            return "VOIDED";
        }
        if (result.outcome() == BattleMatch.Outcome.DRAW) {
            return "DRAW";
        }
        if (snapshot.playerOneId().equals(result.winnerId())) {
            return "PLAYER_ONE_WIN";
        }
        if (snapshot.playerTwoId().equals(result.winnerId())) {
            return "PLAYER_TWO_WIN";
        }
        throw new IllegalArgumentException("winner must be a match participant");
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record MatchRow(
            String status,
            String result,
            Instant startedAt,
            Instant deadlineAt,
            Instant finishedAt,
            String voidReason,
            String resolutionReason,
            long aggregateVersion) {}

    private record PlayerRow(
            UUID userId,
            int seat,
            boolean ready,
            BattleMatch.ConnectionState connectionState,
            long connectionGeneration,
            Instant reconnectDeadline) {}

    private record SnapshotRow(MatchRow match, PlayerRow player) {}
}
