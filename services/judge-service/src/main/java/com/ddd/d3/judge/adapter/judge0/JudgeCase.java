package com.ddd.d3.judge.adapter.judge0;

public record JudgeCase(
        String tier,
        long inputSize,
        String stdin,
        String expectedOutput) {}
