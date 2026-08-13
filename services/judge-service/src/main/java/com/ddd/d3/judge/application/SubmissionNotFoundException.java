package com.ddd.d3.judge.application;

import java.util.UUID;

public final class SubmissionNotFoundException extends RuntimeException {
    public SubmissionNotFoundException(UUID submissionId) {
        super("judge submission was not found: " + submissionId);
    }
}
