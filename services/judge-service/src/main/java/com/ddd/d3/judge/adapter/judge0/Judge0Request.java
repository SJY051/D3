package com.ddd.d3.judge.adapter.judge0;

public record Judge0Request(
        int languageId,
        String sourceCode,
        String stdin,
        String expectedOutput,
        int cpuTimeLimitSeconds,
        int wallTimeLimitSeconds,
        int memoryLimitKib,
        int stackLimitKib,
        int processLimit,
        int fileSizeLimitKib,
        boolean networkEnabled) {}
