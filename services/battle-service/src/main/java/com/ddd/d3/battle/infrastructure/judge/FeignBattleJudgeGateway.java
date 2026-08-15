package com.ddd.d3.battle.infrastructure.judge;

import com.ddd.d3.battle.application.BattleJudgeGateway;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class FeignBattleJudgeGateway implements BattleJudgeGateway {

    private final JudgeSubmissionClient client;

    public FeignBattleJudgeGateway(JudgeSubmissionClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Acceptance accept(Command command, String authorizationHeader) {
        Objects.requireNonNull(command, "command");
        requireAuthorization(authorizationHeader);
        JudgeSubmissionClient.AcceptanceResponse response = client.accept(
                authorizationHeader,
                command.commandId(),
                command.commandId().toString(),
                new JudgeSubmissionClient.SubmissionRequest(
                        command.userId(),
                        command.matchId(),
                        command.problemId(),
                        command.problemVersion(),
                        command.mode().name(),
                        command.language(),
                        command.sourceCode(),
                        command.attemptNumber()));
        if (!"QUEUED".equals(response.status())
                || !command.mode().name().equals(response.mode())
                || !command.language().equals(response.language())) {
            throw new IllegalStateException("Judge acceptance command metadata mismatch");
        }
        return new Acceptance(response.submissionId(), response.status(), response.acceptedAt());
    }

    @Override
    public Evidence readEvidence(UUID submissionId, String authorizationHeader) {
        Objects.requireNonNull(submissionId, "submissionId");
        requireAuthorization(authorizationHeader);
        JudgeSubmissionClient.EvidenceResponse response = client.evidence(
                authorizationHeader, submissionId.toString(), submissionId);
        return new Evidence(
                response.submissionId(),
                response.status(),
                response.passedCount(),
                response.totalCount(),
                response.runtimeMeasurements().stream()
                        .map(value -> new RuntimeMeasurement(
                                value.tier(),
                                value.inputSize(),
                                value.sampleCount(),
                                value.medianRuntimeMicros()))
                        .toList(),
                response.adapterVersion(),
                response.runtimeVersion(),
                response.evidenceVersion(),
                response.completedAt());
    }

    private static void requireAuthorization(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("authorizationHeader must be Bearer credentials");
        }
    }
}
