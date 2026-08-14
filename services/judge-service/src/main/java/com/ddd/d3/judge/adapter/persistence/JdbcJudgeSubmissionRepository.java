package com.ddd.d3.judge.adapter.persistence;

import com.ddd.d3.judge.application.JudgeSubmissionRepository;
import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.JudgeStatus;
import com.ddd.d3.judge.domain.JudgeSubmission;
import com.ddd.d3.judge.domain.RuntimeMeasurement;
import com.ddd.d3.judge.domain.SafeEvaluationEvidence;
import com.ddd.d3.judge.domain.SubmissionCommand;
import com.ddd.d3.judge.domain.SubmissionMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

public final class JdbcJudgeSubmissionRepository implements JudgeSubmissionRepository {

    private static final String SUBMISSION_COLUMNS = """
            id, idempotency_key, user_id, match_id, problem_id, problem_version, mode,
            language_key, source_code, attempt_number, correlation_id, request_fingerprint,
            status, accepted_at, evaluation_claim_id
            """;

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public JdbcJudgeSubmissionRepository(
            JdbcClient jdbcClient, TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Optional<JudgeSubmission> findByIdempotencyKey(UUID idempotencyKey) {
        return findBase("idempotency_key", idempotencyKey).map(this::attachEvidence);
    }

    @Override
    public Optional<JudgeSubmission> findById(UUID submissionId) {
        return findBase("id", submissionId).map(this::attachEvidence);
    }

    @Override
    public List<UUID> findPendingEvaluationIds(int maximumCount) {
        if (maximumCount <= 0 || maximumCount > 100) {
            throw new IllegalArgumentException("queued submission batch size is out of range");
        }
        return jdbcClient.sql("""
                        select id from submission
                        where status = 'QUEUED'
                           or (status = 'RUNNING' and claim_started_at < now() - interval '10 minutes')
                        order by accepted_at, id
                        limit :maximumCount
                        """)
                .param("maximumCount", maximumCount)
                .query(UUID.class)
                .list();
    }

    @Override
    public JudgeSubmission insertOrGet(JudgeSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            SubmissionCommand command = submission.command();
            jdbcClient.sql("""
                            insert into submission (
                                id, idempotency_key, user_id, match_id, problem_id, problem_version,
                                mode, language_key, source_code, attempt_number, correlation_id,
                                request_fingerprint, status, accepted_at, created_at
                            ) values (
                                :id, :idempotencyKey, :userId, :matchId, :problemId, :problemVersion,
                                :mode, :language, :sourceCode, :attemptNumber, :correlationId,
                                :fingerprint, :submissionStatus, :acceptedAt, :acceptedAt
                            ) on conflict (idempotency_key) do nothing
                            """)
                    .param("id", submission.id())
                    .param("idempotencyKey", command.idempotencyKey())
                    .param("userId", command.userId())
                    .param("matchId", command.matchId())
                    .param("problemId", command.problemId())
                    .param("problemVersion", command.problemVersion())
                    .param("mode", command.mode().name())
                    .param("language", command.language().name())
                    .param("sourceCode", command.sourceCode())
                    .param("attemptNumber", command.attemptNumber())
                    .param("correlationId", command.correlationId())
                    .param("fingerprint", submission.requestFingerprint())
                    .param("submissionStatus", submission.status().name())
                    .param("acceptedAt", Timestamp.from(submission.acceptedAt()))
                    .update();
            return findByIdempotencyKey(command.idempotencyKey()).orElseThrow();
        }));
    }

    @Override
    public Optional<JudgeSubmission> claimForEvaluation(UUID submissionId) {
        UUID evaluationClaimId = UUID.randomUUID();
        return Objects.requireNonNull(transactionTemplate.execute(status -> jdbcClient
                .sql("""
                        update submission
                        set status = 'RUNNING', claim_started_at = now(),
                            evaluation_claim_id = :evaluationClaimId
                        where id = :submissionId
                          and (status = 'QUEUED'
                               or (status = 'RUNNING' and claim_started_at < now() - interval '10 minutes'))
                        returning %s
                        """.formatted(SUBMISSION_COLUMNS))
                .param("submissionId", submissionId)
                .param("evaluationClaimId", evaluationClaimId)
                .query(this::mapSubmission)
                .optional()));
    }

    @Override
    public JudgeSubmission completeEvaluation(JudgeSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        SafeEvaluationEvidence evidence = Objects.requireNonNull(submission.evidence(), "submission.evidence");
        return Objects.requireNonNull(transactionTemplate.execute(transactionStatus -> {
            int updated = jdbcClient.sql("""
                            update submission
                            set status = :completedStatus, claim_started_at = null,
                                evaluation_claim_id = null
                            where id = :submissionId and status = 'RUNNING'
                              and evaluation_claim_id = :evaluationClaimId
                            """)
                    .param("completedStatus", evidence.status().name())
                    .param("submissionId", submission.id())
                    .param("evaluationClaimId", Objects.requireNonNull(
                            submission.evaluationClaimId(), "submission.evaluationClaimId"))
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("submission is not claimed for evaluation");
            }

            UUID runId = UUID.randomUUID();
            jdbcClient.sql("""
                            insert into judge_run (
                                id, submission_id, adapter_version, runtime_version, status,
                                passed_count, total_count, completed_at, correlation_id
                            ) values (
                                :id, :submissionId, :adapterVersion, :runtimeVersion, :runStatus,
                                :passedCount, :totalCount, :completedAt, :correlationId
                            )
                            """)
                    .param("id", runId)
                    .param("submissionId", submission.id())
                    .param("adapterVersion", evidence.adapterVersion())
                    .param("runtimeVersion", evidence.runtimeVersion())
                    .param("runStatus", evidence.status().name())
                    .param("passedCount", evidence.passedCount())
                    .param("totalCount", evidence.totalCount())
                    .param("completedAt", Timestamp.from(evidence.completedAt()))
                    .param("correlationId", submission.command().correlationId())
                    .update();

            for (RuntimeMeasurement measurement : evidence.runtimeMeasurements()) {
                jdbcClient.sql("""
                                insert into evaluation_evidence (
                                    id, judge_run_id, tier, input_size, sample_count,
                                    median_runtime_micros, created_at
                                ) values (
                                    :id, :runId, :tier, :inputSize, :sampleCount,
                                    :medianRuntimeMicros, :createdAt
                                )
                                """)
                        .param("id", UUID.randomUUID())
                        .param("runId", runId)
                        .param("tier", measurement.tier())
                        .param("inputSize", measurement.inputSize())
                        .param("sampleCount", measurement.sampleCount())
                        .param("medianRuntimeMicros", measurement.medianRuntimeMicros())
                        .param("createdAt", Timestamp.from(evidence.completedAt()))
                        .update();
            }

            UUID eventId = UUID.randomUUID();
            jdbcClient.sql("""
                            insert into outbox_event (
                                id, aggregate_id, aggregate_version, event_type, payload,
                                occurred_at, published_at
                            ) values (
                                :id, :aggregateId, 1, 'submission.judged.v1', cast(:payload as jsonb),
                                :occurredAt, null
                            )
                            """)
                    .param("id", eventId)
                    .param("aggregateId", submission.id())
                    .param("payload", eventPayload(eventId, submission, evidence))
                    .param("occurredAt", Timestamp.from(evidence.completedAt()))
                    .update();

            return findById(submission.id()).orElseThrow();
        }));
    }

    @Override
    public void releaseEvaluationClaim(UUID submissionId, UUID evaluationClaimId) {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                        update submission
                        set status = 'QUEUED', claim_started_at = null, evaluation_claim_id = null
                        where id = :submissionId and status = 'RUNNING'
                          and evaluation_claim_id = :evaluationClaimId
                        """)
                .param("submissionId", submissionId)
                .param("evaluationClaimId", Objects.requireNonNull(evaluationClaimId, "evaluationClaimId"))
                .update());
    }

    private Optional<JudgeSubmission> findBase(String column, UUID value) {
        if (!("id".equals(column) || "idempotency_key".equals(column))) {
            throw new IllegalArgumentException("unsupported submission lookup");
        }
        return jdbcClient.sql("select " + SUBMISSION_COLUMNS + " from submission where " + column + " = :value")
                .param("value", value)
                .query(this::mapSubmission)
                .optional();
    }

    private JudgeSubmission mapSubmission(ResultSet resultSet, int rowNumber) throws SQLException {
        SubmissionCommand command = new SubmissionCommand(
                resultSet.getObject("idempotency_key", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getObject("match_id", UUID.class),
                resultSet.getObject("problem_id", UUID.class),
                resultSet.getInt("problem_version"),
                SubmissionMode.valueOf(resultSet.getString("mode")),
                JudgeLanguage.valueOf(resultSet.getString("language_key")),
                resultSet.getString("source_code"),
                resultSet.getObject("attempt_number", Integer.class),
                resultSet.getString("correlation_id"));
        return new JudgeSubmission(
                resultSet.getObject("id", UUID.class),
                command,
                resultSet.getString("request_fingerprint"),
                JudgeStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("accepted_at").toInstant(),
                null,
                resultSet.getObject("evaluation_claim_id", UUID.class));
    }

    private JudgeSubmission attachEvidence(JudgeSubmission submission) {
        if (submission.status() == JudgeStatus.QUEUED || submission.status() == JudgeStatus.RUNNING) {
            return submission;
        }
        RunRow run = jdbcClient.sql("""
                        select id, adapter_version, runtime_version, status, passed_count,
                               total_count, completed_at
                        from judge_run where submission_id = :submissionId
                        """)
                .param("submissionId", submission.id())
                .query((resultSet, rowNumber) -> new RunRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("adapter_version"),
                        resultSet.getString("runtime_version"),
                        JudgeStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("passed_count"),
                        resultSet.getInt("total_count"),
                        resultSet.getTimestamp("completed_at").toInstant()))
                .single();
        List<RuntimeMeasurement> measurements = jdbcClient.sql("""
                        select tier, input_size, sample_count, median_runtime_micros
                        from evaluation_evidence
                        where judge_run_id = :runId
                        order by case tier when 'SMALL' then 1 when 'MEDIUM' then 2 else 3 end
                        """)
                .param("runId", run.id())
                .query((resultSet, rowNumber) -> new RuntimeMeasurement(
                        resultSet.getString("tier"),
                        resultSet.getLong("input_size"),
                        resultSet.getInt("sample_count"),
                        resultSet.getLong("median_runtime_micros")))
                .list();
        SafeEvaluationEvidence evidence = new SafeEvaluationEvidence(
                submission.id(),
                run.status(),
                submission.command().mode(),
                submission.command().language(),
                submission.command().problemId(),
                submission.command().problemVersion(),
                run.passedCount(),
                run.totalCount(),
                measurements,
                run.adapterVersion(),
                run.runtimeVersion(),
                "judge-evidence-v1",
                run.completedAt());
        return new JudgeSubmission(
                submission.id(),
                submission.command(),
                submission.requestFingerprint(),
                submission.status(),
                submission.acceptedAt(),
                evidence,
                null);
    }

    private String eventPayload(UUID eventId, JudgeSubmission submission, SafeEvaluationEvidence evidence) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", submission.id());
        data.put("status", evidence.status().name());
        data.put("language", evidence.language().name());
        data.put("evidenceVersion", evidence.evidenceVersion());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "submission.judged");
        event.put("version", 1);
        event.put("occurredAt", evidence.completedAt());
        event.put("correlationId", submission.command().correlationId());
        event.put("aggregateId", submission.id().toString());
        event.put("aggregateVersion", 1);
        event.put("data", data);
        try {
            return objectMapper.writeValueAsString(event);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("submission event could not be serialized", exception);
        }
    }

    private record RunRow(
            UUID id,
            String adapterVersion,
            String runtimeVersion,
            JudgeStatus status,
            int passedCount,
            int totalCount,
            java.time.Instant completedAt) {}
}
