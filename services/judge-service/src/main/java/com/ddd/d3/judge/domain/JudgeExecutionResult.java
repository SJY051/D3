package com.ddd.d3.judge.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record JudgeExecutionResult(
        JudgeStatus status,
        int passedCount,
        int totalCount,
        List<RuntimeMeasurement> runtimeMeasurements,
        String adapterVersion,
        String runtimeVersion,
        Instant completedAt) {

    public JudgeExecutionResult {
        Objects.requireNonNull(status, "status");
        runtimeMeasurements = List.copyOf(runtimeMeasurements);
        Objects.requireNonNull(adapterVersion, "adapterVersion");
        Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        Objects.requireNonNull(completedAt, "completedAt");
        if (passedCount < 0 || totalCount < 0 || passedCount > totalCount) {
            throw new IllegalArgumentException("correctness counts are out of range");
        }
        if (status == JudgeStatus.QUEUED || status == JudgeStatus.RUNNING) {
            throw new IllegalArgumentException("execution result must be terminal");
        }
    }
}
