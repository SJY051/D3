package com.ddd.d3.judge.domain;

public record RuntimeMeasurement(
        String tier,
        long inputSize,
        int sampleCount,
        long medianRuntimeMicros) {

    public RuntimeMeasurement {
        if (tier == null || tier.isBlank()) {
            throw new IllegalArgumentException("tier must not be blank");
        }
        if (inputSize <= 0 || sampleCount <= 0 || medianRuntimeMicros < 0) {
            throw new IllegalArgumentException("runtime measurement values are out of range");
        }
    }
}
