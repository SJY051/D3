package com.ddd.d3.judge.adapter.async;

import com.ddd.d3.judge.application.EvaluationInProgressException;
import com.ddd.d3.judge.application.JudgeEvaluationScheduler;
import com.ddd.d3.judge.application.JudgeSubmissionService;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

public final class AsyncJudgeEvaluationScheduler implements JudgeEvaluationScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncJudgeEvaluationScheduler.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofMillis(100);

    private final JudgeSubmissionService submissionService;
    private final TaskExecutor taskExecutor;

    public AsyncJudgeEvaluationScheduler(JudgeSubmissionService submissionService, TaskExecutor taskExecutor) {
        this.submissionService = Objects.requireNonNull(submissionService, "submissionService");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    }

    @Override
    public void schedule(UUID submissionId) {
        try {
            taskExecutor.execute(() -> evaluateWithBoundedRetry(submissionId));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Judge evaluation scheduling deferred for submission {} with {}",
                    submissionId,
                    exception.getClass().getSimpleName());
        }
    }

    private void evaluateWithBoundedRetry(UUID submissionId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                submissionService.evaluate(submissionId);
                return;
            } catch (EvaluationInProgressException exception) {
                return;
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Judge evaluation attempt {} failed for submission {} with {}",
                        attempt,
                        submissionId,
                        exception.getClass().getSimpleName());
                if (attempt == MAX_ATTEMPTS) {
                    completePlatformFailure(submissionId);
                    return;
                }
                if (!sleep()) {
                    return;
                }
            }
        }
    }

    private void completePlatformFailure(UUID submissionId) {
        try {
            submissionService.completePlatformFailure(submissionId);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Judge platform failure finalization deferred for submission {} with {}",
                    submissionId,
                    exception.getClass().getSimpleName());
        }
    }

    private static boolean sleep() {
        try {
            Thread.sleep(RETRY_DELAY);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
