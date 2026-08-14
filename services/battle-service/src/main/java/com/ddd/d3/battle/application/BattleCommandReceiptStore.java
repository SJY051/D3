package com.ddd.d3.battle.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BattleCommandReceiptStore {

    record Receipt(
            UUID commandId,
            UUID matchId,
            UUID playerId,
            String commandType,
            String payloadFingerprint,
            long aggregateVersion,
            Instant acceptedAt) {}

    Optional<Receipt> findByCommandId(UUID commandId);

    void record(Receipt receipt);
}
