package com.ddd.d3.battle.infrastructure.persistence;

import com.ddd.d3.battle.application.BattleCommandReceiptStore;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBattleCommandReceiptStore implements BattleCommandReceiptStore {

    private final JdbcClient jdbc;

    public JdbcBattleCommandReceiptStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public Optional<Receipt> findByCommandId(UUID commandId) {
        Objects.requireNonNull(commandId, "commandId must not be null");
        return jdbc.sql("""
                        select command_id, match_id, player_user_id, command_type,
                               payload_fingerprint, aggregate_version, accepted_at
                        from match_command_receipt
                        where command_id = :commandId
                        """)
                .param("commandId", commandId)
                .query((resultSet, rowNumber) -> new Receipt(
                        resultSet.getObject("command_id", UUID.class),
                        resultSet.getObject("match_id", UUID.class),
                        resultSet.getObject("player_user_id", UUID.class),
                        resultSet.getString("command_type"),
                        resultSet.getString("payload_fingerprint"),
                        resultSet.getLong("aggregate_version"),
                        resultSet.getTimestamp("accepted_at").toInstant()))
                .optional();
    }

    @Override
    public void record(Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        jdbc.sql("""
                        insert into match_command_receipt (
                            command_id, match_id, player_user_id, command_type,
                            payload_fingerprint, aggregate_version, accepted_at
                        ) values (
                            :commandId, :matchId, :playerId, :commandType,
                            :payloadFingerprint, :aggregateVersion, :acceptedAt
                        )
                        """)
                .param("commandId", receipt.commandId())
                .param("matchId", receipt.matchId())
                .param("playerId", receipt.playerId())
                .param("commandType", receipt.commandType())
                .param("payloadFingerprint", receipt.payloadFingerprint())
                .param("aggregateVersion", receipt.aggregateVersion())
                .param("acceptedAt", Timestamp.from(receipt.acceptedAt()))
                .update();
    }
}
