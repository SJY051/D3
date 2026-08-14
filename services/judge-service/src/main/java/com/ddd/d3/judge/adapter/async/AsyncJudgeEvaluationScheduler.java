package com.ddd.d3.judge.adapter.async;

import com.ddd.d3.judge.application.EvaluationInProgressException;
import com.ddd.d3.judge.application.JudgeEvaluationScheduler;
import com.ddd.d3.judge.application.JudgeSubmissionService;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

public final class AsyncJudgeEvaluationScheduler implements JudgeEvaluationScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncJudgeEvaluationScheduler.class);
    private final JudgeSubmissionService submissionService;
    private final TaskExecutor taskExecutor;

    public AsyncJudgeEvaluationScheduler(JudgeSubmissionService submissionService, TaskExecutor taskExecutor) {
        this.submissionService = Objects.requireNonNull(submissionService, "submissionService");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    }

    @Override
    public void schedule(UUID submissionId) {
        try {
            taskExecutor.execute(() -> evaluateOnce(submissionId));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Judge evaluation scheduling deferred for submission {} with {}",
                    submissionId,
                    exception.getClass().getSimpleName());
        }
    }

    private void evaluateOnce(UUID submissionId) {
        try {
            submissionService.evaluate(submissionId);
        } catch (EvaluationInProgressException exception) {
            // Another fenced worker owns this submission.
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Judge evaluation deferred for submission {} with {}",
                    submissionId,
                    exception.getClass().getSimpleName());
        }
    }
}
