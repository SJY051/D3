package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.attack.GarbageAttackExchange;
import java.util.List;
import java.util.UUID;

public interface GarbageAttackEventStore {

    void lock(UUID matchId);

    List<GarbageAttackExchange.AttackEvent> findByMatchId(UUID matchId);

    void append(UUID matchId, List<GarbageAttackExchange.AttackEvent> events);
}
