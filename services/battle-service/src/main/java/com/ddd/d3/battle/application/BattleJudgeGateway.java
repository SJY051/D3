package com.ddd.d3.battle.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface BattleJudgeGateway {

    Acceptance accept(Command command, String authorizationHeader);

    Evidence readEvidence(UUID submissionId, String authorizationHeader);

    enum Mode {
        RUN,
        SUBMIT
    }

    record Command(
            UUID commandId,
            UUID userId,
            UUID matchId,
            UUID problemId,
            int problemVersion,
            Mode mode,
            String language,
            String sourceCode,
            Integer attemptNumber) {
        public Command {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(matchId, "matchId");
            Objects.requireNonNull(problemId, "problemId");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(sourceCode, "sourceCode");
            if (problemVersion <= 0) throw new IllegalArgumentException("problemVersion must be positive");
            if (language.isBlank()) throw new IllegalArgumentException("language must not be blank");
            if (sourceCode.isBlank() || sourceCode.length() > 65_536) {
                throw new IllegalArgumentException("sourceCode length is invalid");
            }
            if ((mode == Mode.RUN) != (attemptNumber == null)) {
                throw new IllegalArgumentException("only SUBMIT requires attemptNumber");
            }
            if (attemptNumber != null && attemptNumber <= 0) {
                throw new IllegalArgumentException("attemptNumber must be positive");
            }
        }
    }

    record Acceptance(UUID submissionId, String status, Instant acceptedAt) {
        public Acceptance {
            Objects.requireNonNull(submissionId, "submissionId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            if (status.isBlank()) throw new IllegalArgumentException("status must not be blank");
        }
    }

    record RuntimeMeasurement(
            String tier,
            long inputSize,
            int sampleCount,
            long medianRuntimeMicros) {
        public RuntimeMeasurement {
            Objects.requireNonNull(tier, "tier");
            if (tier.isBlank() || inputSize <= 0 || sampleCount <= 0 || medianRuntimeMicros < 0) {
                throw new IllegalArgumentException("runtime measurement is invalid");
            }
        }
    }

    record Evidence(
            UUID submissionId,
            String status,
            int passedCount,
            int totalCount,
            List<RuntimeMeasurement> runtimeMeasurements,
            String adapterVersion,
            String runtimeVersion,
            String evidenceVersion,
            Instant completedAt) {
        public Evidence {
            Objects.requireNonNull(submissionId, "submissionId");
            Objects.requireNonNull(status, "status");
            runtimeMeasurements = List.copyOf(runtimeMeasurements);
            Objects.requireNonNull(adapterVersion, "adapterVersion");
            Objects.requireNonNull(runtimeVersion, "runtimeVersion");
            Objects.requireNonNull(evidenceVersion, "evidenceVersion");
            Objects.requireNonNull(completedAt, "completedAt");
            if (status.isBlank()
                    || adapterVersion.isBlank()
                    || runtimeVersion.isBlank()
                    || evidenceVersion.isBlank()) {
                throw new IllegalArgumentException("evidence text fields must not be blank");
            }
            if (passedCount < 0 || totalCount < passedCount) {
                throw new IllegalArgumentException("evidence metrics are invalid");
            }
        }
    }
}
