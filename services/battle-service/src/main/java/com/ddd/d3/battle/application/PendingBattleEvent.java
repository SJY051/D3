package com.ddd.d3.battle.application;

import java.util.UUID;

public record PendingBattleEvent(UUID eventId, String eventType, String aggregateId, String payload) {}
