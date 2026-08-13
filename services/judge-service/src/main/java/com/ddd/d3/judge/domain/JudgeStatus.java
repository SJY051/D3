package com.ddd.d3.judge.domain;

public enum JudgeStatus {
    QUEUED,
    RUNNING,
    ACCEPTED,
    WRONG_ANSWER,
    COMPILATION_ERROR,
    RUNTIME_ERROR,
    TIME_LIMIT,
    MEMORY_LIMIT,
    PLATFORM_FAILURE
}
