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
    private JdbcClient jdbc;
    private JdbcJudgeSubmissionRepository repository;

    @BeforeEach
    void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(
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

        JudgeSubmission completed = repository.completeEvaluation(activeClaim.complete(evidence()));
        assertEquals(JudgeStatus.ACCEPTED, completed.status());
        assertTrue(repository.claimForEvaluation(SUBMISSION_ID).isEmpty());
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
