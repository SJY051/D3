package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.RankedMatchmaker;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RankedMatchStore {

    record RankedMatch(
            UUID matchId,
            UUID problemId,
            RankedMatchmaker.Entry playerOne,
            RankedMatchmaker.Entry playerTwo,
            Instant createdAt) {}

    RankedMatch create(RankedMatchmaker.Pair pair, Instant createdAt);

    Optional<UUID> findMatchIdByTicket(UUID ticketId, UUID playerId);

    Optional<UUID> findActiveMatchIdByPlayer(UUID playerId);
}
