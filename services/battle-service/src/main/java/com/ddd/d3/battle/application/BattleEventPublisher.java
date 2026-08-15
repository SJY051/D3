package com.ddd.d3.battle.application;

public interface BattleEventPublisher {
    void publish(PendingBattleEvent event);
}
