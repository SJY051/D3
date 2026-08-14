package com.ddd.d3.judge.application;

import java.util.UUID;

public final class EvidenceNotReadyException extends RuntimeException {
    public EvidenceNotReadyException(UUID submissionId) {
        super("judge evidence is not ready: " + submissionId);
    }
}
