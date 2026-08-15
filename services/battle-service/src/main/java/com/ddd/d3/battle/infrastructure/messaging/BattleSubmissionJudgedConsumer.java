package com.ddd.d3.battle.infrastructure.messaging;

import com.ddd.d3.battle.application.BattleJudgeReferenceStore;
import com.ddd.d3.battle.application.BattleJudgedSubmissionService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public final class BattleSubmissionJudgedConsumer {

    private static final Set<String> STATUSES = Set.of(
            "ACCEPTED",
            "WRONG_ANSWER",
            "COMPILATION_ERROR",
            "RUNTIME_ERROR",
            "TIME_LIMIT",
            "MEMORY_LIMIT",
            "PLATFORM_FAILURE");
    private static final Set<String> LANGUAGES =
            Set.of("C", "CPP", "JAVA", "PYTHON3", "JAVASCRIPT", "TYPESCRIPT");

    private final BattleJudgedSubmissionService submissions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BattleSubmissionJudgedConsumer(
            BattleJudgedSubmissionService submissions, ObjectMapper objectMapper, Clock clock) {
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @KafkaListener(
            topics = "${d3.battle.submission-judged-topic:submission.judged.v1}",
            groupId = "${d3.battle.submission-judged-group:${spring.application.name}-submission-judged}")
    public void receive(String payload) {
        JudgedEnvelope envelope = parse(payload);
        submissions.receive(new BattleJudgeReferenceStore.JudgedEvent(
                envelope.eventId(),
                envelope.data().submissionId(),
                envelope.aggregateVersion(),
                clock.instant()));
    }

    private JudgedEnvelope parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("submission.judged payload must not be blank");
        }
        try {
            JudgedEnvelope envelope = objectMapper.readValue(payload, JudgedEnvelope.class);
            if (!"submission.judged".equals(envelope.eventType()) || envelope.version() != 1) {
                throw new IllegalArgumentException("unsupported submission.judged contract");
            }
            if (envelope.aggregateVersion() <= 0
                    || envelope.correlationId().isBlank()
                    || envelope.aggregateId().isBlank()
                    || !envelope.aggregateId().equals(envelope.data().submissionId().toString())
                    || !STATUSES.contains(envelope.data().status())
                    || !LANGUAGES.contains(envelope.data().language())
                    || envelope.data().evidenceVersion().isBlank()) {
                throw new IllegalArgumentException("invalid submission.judged payload");
            }
            return envelope;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("submission.judged payload is malformed", exception);
        }
    }

    record JudgedEnvelope(
            UUID eventId,
            String eventType,
            int version,
            Instant occurredAt,
            String correlationId,
            String aggregateId,
            long aggregateVersion,
            JudgedData data) {
        JudgedEnvelope {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(data, "data");
        }
    }

    record JudgedData(
            UUID submissionId,
            String status,
            String language,
            String evidenceVersion) {
        JudgedData {
            Objects.requireNonNull(submissionId, "submissionId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(evidenceVersion, "evidenceVersion");
        }
    }
}
