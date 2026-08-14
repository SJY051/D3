package com.ddd.d3.judge.adapter.async;

import com.ddd.d3.judge.application.JudgeEvaluationScheduler;
import com.ddd.d3.judge.application.JudgeSubmissionRepository;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class QueuedSubmissionDispatcher {

    private static final int BATCH_SIZE = 20;

    private final JudgeSubmissionRepository repository;
    private final JudgeEvaluationScheduler evaluationScheduler;

    public QueuedSubmissionDispatcher(
            JudgeSubmissionRepository repository, JudgeEvaluationScheduler evaluationScheduler) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.evaluationScheduler = Objects.requireNonNull(evaluationScheduler, "evaluationScheduler");
    }

    @Scheduled(fixedDelayString = "${d3.judge.queue-recovery-delay:500ms}")
    public int scheduleBatch() {
        int scheduled = 0;
        for (var submissionId : repository.findPendingEvaluationIds(BATCH_SIZE)) {
            evaluationScheduler.schedule(submissionId);
            scheduled++;
        }
        return scheduled;
    }
}
