package com.ddd.d3.judge.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.JudgeStatus;
import com.ddd.d3.judge.domain.JudgeSubmission;
import com.ddd.d3.judge.domain.RuntimeMeasurement;
import com.ddd.d3.judge.domain.SafeEvaluationEvidence;
import com.ddd.d3.judge.domain.SubmissionCommand;
import com.ddd.d3.judge.domain.SubmissionMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class JdbcJudgeSubmissionRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    private static final UUID SUBMISSION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-13T12:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-13T12:00:01Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DriverManagerDataSource dataSource;
    private JdbcClient jdbc;
    private JdbcJudgeSubmissionRepository repository;

    @BeforeEach
    void migrateAndReset() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcJudgeSubmissionRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                objectMapper);
    }

    @Test
    void d3Jdg001CommitsOneIdempotentSubmissionAndOneEvaluationOutboxEvent() throws Exception {
        JudgeSubmission queued = queuedSubmission();

        assertEquals(queued, repository.insertOrGet(queued));
        assertEquals(queued, repository.insertOrGet(queued));
        JudgeSubmission running = repository.claimForEvaluation(SUBMISSION_ID).orElseThrow();
        assertTrue(repository.claimForEvaluation(SUBMISSION_ID).isEmpty());
        running = repository.markEvaluationStarted(SUBMISSION_ID, running.evaluationClaimId());
        assertTrue(running.evaluationStartedAt() != null);

        SafeEvaluationEvidence evidence = evidence();
        JudgeSubmission completed = repository.completeEvaluation(running.complete(evidence));

        assertEquals(evidence, completed.evidence());
        assertEquals(evidence, repository.findById(SUBMISSION_ID).orElseThrow().evidence());
        assertEquals(1, jdbc.sql("select count(*) from submission").query(Integer.class).single());
        assertEquals(1, jdbc.sql("select count(*) from judge_run").query(Integer.class).single());
        assertEquals(1, jdbc.sql("select count(*) from evaluation_evidence").query(Integer.class).single());
        assertEquals(1, jdbc.sql("select count(*) from outbox_event").query(Integer.class).single());

        String payload = jdbc.sql("select payload::text from outbox_event").query(String.class).single();
        JsonNode event = objectMapper.readTree(payload);
        assertEquals("submission.judged", event.path("eventType").asText());
        assertEquals(SUBMISSION_ID.toString(), event.path("data").path("submissionId").asText());
        assertEquals("ACCEPTED", event.path("data").path("status").asText());
        assertFalse(payload.contains("private-source"));
        assertFalse(payload.contains("hidden"));
    }

    @Test
    void d3Jdg001ConcurrentEquivalentAcceptsKeepTheFirstCommittedSubmission() throws Exception {
        JudgeSubmission first = queuedSubmission();
        JudgeSubmission second = new JudgeSubmission(
                UUID.fromString("99999999-9999-4999-8999-999999999999"),
                first.command(),
                first.requestFingerprint(),
                first.status(),
                first.acceptedAt());
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return repository.insertOrGet(first);
            });
            var secondResult = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return repository.insertOrGet(second);
            });
            start.countDown();

            UUID winner = firstResult.get(5, TimeUnit.SECONDS).id();
            assertEquals(winner, secondResult.get(5, TimeUnit.SECONDS).id());
            assertEquals(1, jdbc.sql("select count(*) from submission").query(Integer.class).single());
        }
    }

    @Test
    void d3Jdg001ReleasesAFailedClaimForBoundedTransportRetry() {
        repository.insertOrGet(queuedSubmission());
        JudgeSubmission claim = repository.claimForEvaluation(SUBMISSION_ID).orElseThrow();

        repository.releaseEvaluationClaim(SUBMISSION_ID, claim.evaluationClaimId());

        assertTrue(repository.claimForEvaluation(SUBMISSION_ID).isPresent());
    }

    @Test
    void d3Jdg001RecoversAStaleWorkerClaimWithoutTakingAnActiveClaim() {
        repository.insertOrGet(queuedSubmission());
        JudgeSubmission staleClaim = repository.claimForEvaluation(SUBMISSION_ID).orElseThrow();
        assertTrue(repository.findPendingEvaluationIds(20).isEmpty());

        jdbc.sql("""
                        update submission
                        set claim_started_at = now() - interval '11 minutes'
                        where id = :submissionId
                        """)
                .param("submissionId", SUBMISSION_ID)
                .update();

        assertEquals(List.of(SUBMISSION_ID), repository.findPendingEvaluationIds(20));
        JudgeSubmission activeClaim = repository.claimForEvaluation(SUBMISSION_ID).orElseThrow();
        assertNotEquals(staleClaim.evaluationClaimId(), activeClaim.evaluationClaimId());

        assertThrows(
                IllegalStateException.class,
                () -> repository.completeEvaluation(staleClaim.complete(evidence())));
        repository.releaseEvaluationClaim(SUBMISSION_ID, staleClaim.evaluationClaimId());
        assertEquals(JudgeStatus.RUNNING, repository.findById(SUBMISSION_ID).orElseThrow().status());

        activeClaim = repository.markEvaluationStarted(SUBMISSION_ID, activeClaim.evaluationClaimId());
        JudgeSubmission completed = repository.completeEvaluation(activeClaim.complete(evidence()));
        assertEquals(JudgeStatus.ACCEPTED, completed.status());
        assertTrue(repository.claimForEvaluation(SUBMISSION_ID).isEmpty());
    }

    @Test
    void d3Jdg001PreservesTheNoReplayFenceAcrossAStaleClaimRecovery() {
        repository.insertOrGet(queuedSubmission());
        JudgeSubmission staleClaim = repository.claimForEvaluation(SUBMISSION_ID).orElseThrow();
        repository.markEvaluationStarted(SUBMISSION_ID, staleClaim.evaluationClaimId());
        repository.releaseEvaluationClaim(SUBMISSION_ID, staleClaim.evaluationClaimId());
        assertEquals(JudgeStatus.RUNNING, repository.findById(SUBMISSION_ID).orElseThrow().status());

        jdbc.sql("""
                        update submission
                        set claim_started_at = now() - interval '11 minutes'
                        where id = :submissionId
                        """)
                .param("submissionId", SUBMISSION_ID)
                .update();

        JudgeSubmission recovered = repository.claimForEvaluation(SUBMISSION_ID).orElseThrow();
        assertTrue(recovered.evaluationStartedAt() != null);
    }

    @Test
    void d3Jdg001RejectsAttemptNumbersThatDoNotMatchTheSubmissionMode() {
        assertThrows(DataIntegrityViolationException.class, () -> insertRawSubmission("SUBMIT", null));
        assertThrows(DataIntegrityViolationException.class, () -> insertRawSubmission("RUN", 1));

        assertEquals(1, insertRawSubmission("RUN", null));
    }

    @Test
    void d3Btl003StoresAtMostOneRuntimeMeasurementPerSizeTier() {
        repository.insertOrGet(queuedSubmission());
        JudgeSubmission running = repository.claimForEvaluation(SUBMISSION_ID).orElseThrow();
        running = repository.markEvaluationStarted(SUBMISSION_ID, running.evaluationClaimId());
        SafeEvaluationEvidence duplicateTierEvidence = new SafeEvaluationEvidence(
                SUBMISSION_ID,
                JudgeStatus.ACCEPTED,
                SubmissionMode.SUBMIT,
                JudgeLanguage.PYTHON3,
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                1,
                3,
                3,
                List.of(
                        new RuntimeMeasurement("SMALL", 100, 3, 700),
                        new RuntimeMeasurement("SMALL", 100, 3, 710)),
                "judge0-ce-1.13.1",
                "Python 3.8.1",
                "judge-evidence-v1",
                COMPLETED_AT);

        JudgeSubmission completed = running.complete(duplicateTierEvidence);
        assertThrows(DataIntegrityViolationException.class, () -> repository.completeEvaluation(completed));
        assertEquals(0, jdbc.sql("select count(*) from judge_run").query(Integer.class).single());
    }

    @Test
    void d3Qlt001UpgradesExistingJudgeEvidenceWithoutChangingV1OrV2() {
        migrateOnlyThrough("2");
        UUID submissionId = UUID.randomUUID();
        UUID legacyNullAttemptSubmissionId = UUID.randomUUID();
        UUID judgeRunId = UUID.randomUUID();
        UUID weakerEvidenceId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        UUID representativeEvidenceId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        insertRawSubmission(submissionId, "RUN", null);
        insertRawSubmission(legacyNullAttemptSubmissionId, "SUBMIT", null);
        assertEquals(1, jdbc.sql("update submission set status = 'ACCEPTED' where id = :id")
                .param("id", submissionId)
                .update());
        assertEquals(1, jdbc.sql("""
                        insert into judge_run (
                            id, submission_id, adapter_version, runtime_version, status,
                            passed_count, total_count, completed_at, correlation_id
                        ) values (
                            :id, :submissionId, 'legacy-adapter', 'legacy-runtime', 'ACCEPTED',
                            1, 1, now(), 'corr-upgrade'
                        )
                        """)
                .param("id", judgeRunId)
                .param("submissionId", submissionId)
                .update());
        assertEquals(2, jdbc.sql("""
                        insert into evaluation_evidence (
                            id, judge_run_id, tier, input_size, sample_count,
                            median_runtime_micros, created_at
                        ) values (
                            :weakerId, :judgeRunId, 'SMALL', 100, 3, 700,
                            timestamptz '2026-08-13 00:00:00+00'
                        ), (
                            :representativeId, :judgeRunId, 'SMALL', 200, 5, 900,
                            timestamptz '2026-08-12 00:00:00+00'
                        )
                        """)
                .param("weakerId", weakerEvidenceId)
                .param("representativeId", representativeEvidenceId)
                .param("judgeRunId", judgeRunId)
                .update());

        int applied = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;

        assertEquals(1, applied);
        assertEquals(1, jdbc.sql("select count(*) from submission where id = :id")
                .param("id", submissionId)
                .query(Integer.class)
                .single());
        assertEquals(1, jdbc.sql("select count(*) from evaluation_evidence where judge_run_id = :id")
                .param("id", judgeRunId)
                .query(Integer.class)
                .single());
        assertEquals(representativeEvidenceId, jdbc.sql("""
                        select id
                        from evaluation_evidence
                        where judge_run_id = :judgeRunId and tier = 'SMALL'
                        """)
                .param("judgeRunId", judgeRunId)
                .query(UUID.class)
                .single());
        assertEquals(5, jdbc.sql("""
                        select sample_count
                        from evaluation_evidence
                        where judge_run_id = :judgeRunId and tier = 'SMALL'
                        """)
                .param("judgeRunId", judgeRunId)
                .query(Integer.class)
                .single());
        assertEquals(weakerEvidenceId, jdbc.sql("""
                        select id
                        from evaluation_evidence_legacy_duplicate
                        where judge_run_id = :judgeRunId and tier = 'SMALL'
                        """)
                .param("judgeRunId", judgeRunId)
                .query(UUID.class)
                .single());
        assertEquals(representativeEvidenceId, jdbc.sql("""
                        select canonical_evidence_id
                        from evaluation_evidence_legacy_duplicate
                        where id = :id
                        """)
                .param("id", weakerEvidenceId)
                .query(UUID.class)
                .single());
        assertEquals(700L, jdbc.sql("""
                        select median_runtime_micros
                        from evaluation_evidence_legacy_duplicate
                        where id = :id
                        """)
                .param("id", weakerEvidenceId)
                .query(Long.class)
                .single());
        assertEquals(1, jdbc.sql("""
                        select attempt_number
                        from submission
                        where id = :id
                        """)
                .param("id", legacyNullAttemptSubmissionId)
                .query(Integer.class)
                .single());
        assertEquals(1, jdbc.sql("""
                        select normalized_attempt_number
                        from submission_legacy_attempt_normalization
                        where submission_id = :id and legacy_attempt_number is null
                        """)
                .param("id", legacyNullAttemptSubmissionId)
                .query(Integer.class)
                .single());
        assertEquals(true, jdbc.sql("""
                        select convalidated
                        from pg_constraint
                        where conname = 'submission_attempt_mode'
                        """)
                .query(Boolean.class)
                .single());
        assertEquals(
                1,
                repository.findById(legacyNullAttemptSubmissionId)
                        .orElseThrow()
                        .command()
                        .attemptNumber());
    }

    private int insertRawSubmission(String mode, Integer attemptNumber) {
        return insertRawSubmission(UUID.randomUUID(), mode, attemptNumber);
    }

    private int insertRawSubmission(UUID rowId, String mode, Integer attemptNumber) {
        return jdbc.sql("""
                        insert into submission (
                            id, idempotency_key, user_id, match_id, problem_id, problem_version,
                            mode, language_key, source_code, attempt_number, correlation_id,
                            request_fingerprint, status, accepted_at
                        ) values (
                            :id, :idempotencyKey, :userId, :matchId, :problemId, 1,
                            :mode, 'JAVA', 'fixture', :attemptNumber, 'corr-fixture',
                            :fingerprint, 'QUEUED', now()
                        )
                        """)
                .param("id", rowId)
                .param("idempotencyKey", UUID.randomUUID())
                .param("userId", UUID.randomUUID())
                .param("matchId", UUID.randomUUID())
                .param("problemId", UUID.randomUUID())
                .param("mode", mode)
                .param("attemptNumber", attemptNumber, java.sql.Types.INTEGER)
                .param("fingerprint", "fingerprint-" + rowId)
                .update();
    }

    private void migrateOnlyThrough(String version) {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target(version).load().migrate();
    }

    private static JudgeSubmission queuedSubmission() {
        return new JudgeSubmission(
                SUBMISSION_ID,
                new SubmissionCommand(
                        IDEMPOTENCY_KEY,
                        UUID.fromString("33333333-3333-4333-8333-333333333333"),
                        UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        UUID.fromString("55555555-5555-4555-8555-555555555555"),
                        1,
                        SubmissionMode.SUBMIT,
                        JudgeLanguage.PYTHON3,
                        "private-source",
                        1,
                        "corr-1"),
                "fingerprint",
                JudgeStatus.QUEUED,
                ACCEPTED_AT);
    }

    private static SafeEvaluationEvidence evidence() {
        return new SafeEvaluationEvidence(
                SUBMISSION_ID,
                JudgeStatus.ACCEPTED,
                SubmissionMode.SUBMIT,
                JudgeLanguage.PYTHON3,
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                1,
                3,
                3,
                List.of(new RuntimeMeasurement("SMALL", 100, 3, 700)),
                "judge0-ce-1.13.1",
                "Python 3.8.1",
                "judge-evidence-v1",
                COMPLETED_AT);
    }
}
