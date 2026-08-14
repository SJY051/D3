package com.ddd.d3.judge.adapter.async;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ddd.d3.judge.application.JudgeSubmissionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AsyncJudgeEvaluationSchedulerTest {

    @Test
    void d3Jdg001CommitsPlatformFailureOnlyAfterTransportRetriesAreExhausted() {
        UUID submissionId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        JudgeSubmissionService submissionService = mock(JudgeSubmissionService.class);
        doThrow(new IllegalStateException("temporary transport failure"))
                .when(submissionService)
                .evaluate(submissionId);
        AsyncJudgeEvaluationScheduler scheduler =
                new AsyncJudgeEvaluationScheduler(submissionService, Runnable::run);

        scheduler.schedule(submissionId);

        verify(submissionService, times(3)).evaluate(submissionId);
        verify(submissionService).completePlatformFailure(submissionId);
    }
}
