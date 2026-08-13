package com.ddd.d3.judge.application;

import com.ddd.d3.judge.domain.JudgeExecutionResult;
import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.SubmissionCommand;

public interface JudgeExecutionAdapter {
    boolean isAvailable(JudgeLanguage language);

    default JudgeExecutionResult execute(SubmissionCommand command) {
        throw new UnsupportedOperationException("judge execution is not implemented");
    }
}
