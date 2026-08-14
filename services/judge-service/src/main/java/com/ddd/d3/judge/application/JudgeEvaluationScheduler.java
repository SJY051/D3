package com.ddd.d3.judge.application;

import java.util.UUID;

public interface JudgeEvaluationScheduler {
    void schedule(UUID submissionId);
}
