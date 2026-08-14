package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.BattleMatch;
import java.time.Instant;
import java.util.Optional;

public interface BattleDeadlineClaimStore {

    /**
     * Claims one due aggregate for the caller's enclosing transaction.
     * The caller must keep that transaction open until its aggregate save completes.
     */
    Optional<BattleMatch.Snapshot> claimNextDue(Instant cutoff);
}
