package com.ddd.d3.judge.adapter.judge0;

public record Judge0Result(
        String statusDescription,
        long cpuTimeMicros,
        long memoryKib,
        String runtimeVersion) {}
