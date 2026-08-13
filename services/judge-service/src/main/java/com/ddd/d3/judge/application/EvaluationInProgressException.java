package com.ddd.d3.judge.application;

import java.util.UUID;

public final class EvaluationInProgressException extends RuntimeException {

    public EvaluationInProgressException(UUID submissionId) {
        super("submission evaluation is already in progress: " + submissionId);
    }
}
