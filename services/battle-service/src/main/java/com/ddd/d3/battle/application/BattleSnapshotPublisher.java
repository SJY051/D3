package com.ddd.d3.battle.application;

import java.util.UUID;

@FunctionalInterface
public interface BattleSnapshotPublisher {

    void publish(UUID matchId);
}
