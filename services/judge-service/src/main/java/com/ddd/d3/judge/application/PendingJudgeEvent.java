package com.ddd.d3.judge.application;

import java.util.UUID;

public record PendingJudgeEvent(UUID eventId, String aggregateId, String payload) {}
