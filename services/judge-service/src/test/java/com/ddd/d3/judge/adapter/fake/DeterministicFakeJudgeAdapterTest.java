package com.ddd.d3.judge.adapter.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.judge.domain.JudgeExecutionResult;
import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.JudgeStatus;
import com.ddd.d3.judge.domain.SubmissionCommand;
import com.ddd.d3.judge.domain.SubmissionMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicFakeJudgeAdapterTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-13T12:00:01Z");
    private final DeterministicFakeJudgeAdapter adapter =
            new DeterministicFakeJudgeAdapter(Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));

    @Test
    void d3Jdg001MapsAllSixSupportedLanguages() {
        for (JudgeLanguage language : JudgeLanguage.values()) {
            assertTrue(adapter.isAvailable(language));
        }
    }

    @Test
    void d3Jdg001NormalizesEveryTerminalOutcome() {
        Map<String, JudgeStatus> scenarios = Map.of(
                "D3_FAKE_ACCEPTED", JudgeStatus.ACCEPTED,
                "D3_FAKE_WRONG_ANSWER", JudgeStatus.WRONG_ANSWER,
                "D3_FAKE_COMPILATION_ERROR", JudgeStatus.COMPILATION_ERROR,
                "D3_FAKE_RUNTIME_ERROR", JudgeStatus.RUNTIME_ERROR,
                "D3_FAKE_TIME_LIMIT", JudgeStatus.TIME_LIMIT,
                "D3_FAKE_MEMORY_LIMIT", JudgeStatus.MEMORY_LIMIT,
                "D3_FAKE_PLATFORM_FAILURE", JudgeStatus.PLATFORM_FAILURE);

        scenarios.forEach((source, expectedStatus) -> {
            JudgeExecutionResult result = adapter.execute(command(source));
            assertEquals(expectedStatus, result.status());
            assertEquals(COMPLETED_AT, result.completedAt());
            assertEquals("fake-v1", result.adapterVersion());
        });
    }

    @Test
    void d3Btl003ReturnsRepeatedSizeTierEvidenceOnlyForAcceptedCode() {
        JudgeExecutionResult accepted = adapter.execute(command("D3_FAKE_ACCEPTED"));
        JudgeExecutionResult rejected = adapter.execute(command("D3_FAKE_WRONG_ANSWER"));

        assertEquals(3, accepted.passedCount());
        assertEquals(3, accepted.totalCount());
        assertEquals(3, accepted.runtimeMeasurements().size());
        assertTrue(accepted.runtimeMeasurements().stream().allMatch(sample -> sample.sampleCount() >= 3));
        assertTrue(rejected.runtimeMeasurements().isEmpty());
    }

    private static SubmissionCommand command(String sourceCode) {
        return new SubmissionCommand(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                1,
                SubmissionMode.SUBMIT,
                JudgeLanguage.PYTHON3,
                sourceCode,
                1,
                "corr-1");
    }
}
