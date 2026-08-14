package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import java.util.Optional;
import java.util.UUID;

public interface BattleMatchRepository {

    Optional<BattleMatch.Snapshot> findById(UUID matchId);

    void save(BattleMatch.Snapshot snapshot, long expectedVersion);
}
