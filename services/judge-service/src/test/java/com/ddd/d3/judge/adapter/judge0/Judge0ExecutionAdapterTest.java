package com.ddd.d3.judge.adapter.judge0;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.JudgeStatus;
import com.ddd.d3.judge.domain.SubmissionCommand;
import com.ddd.d3.judge.domain.SubmissionMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Judge0ExecutionAdapterTest {

    private static final UUID PROBLEM_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-13T12:00:01Z");

    @Test
    void d3Jdg001UsesOnlyThePinnedSixRuntimeMappings() {
        RecordingClient client = new RecordingClient(Set.of(50, 54, 62, 71, 63, 74));
        Judge0ExecutionAdapter adapter = adapter(client, problem(oneCase("", "ok\n")));

        for (JudgeLanguage language : JudgeLanguage.values()) {
            assertTrue(adapter.isAvailable(language));
        }
        client.languageIds = Set.of(50, 54, 62, 71, 63);
        assertFalse(adapter.isAvailable(JudgeLanguage.TYPESCRIPT));
    }

    @Test
    void d3Sec001AcceptsExactByteLimitsAndNeverCallsJudge0AboveThem() {
        RecordingClient client = new RecordingClient(Set.of(71));
        Judge0ExecutionAdapter exact = adapter(
                client,
                problem(new JudgeCase(
                        null,
                        1,
                        "가".repeat(Judge0ExecutionAdapter.MAX_STDIN_BYTES / 3) + "a",
                        "나".repeat(Judge0ExecutionAdapter.MAX_EXPECTED_OUTPUT_BYTES / 3) + "b")));

        assertDoesNotThrow(() -> exact.execute(command("x".repeat(Judge0ExecutionAdapter.MAX_SOURCE_BYTES))));
        assertEquals(1, client.requests.size());

        List<JudgeCase> oversizedCases = List.of(
                new JudgeCase(null, 1, "x".repeat(Judge0ExecutionAdapter.MAX_STDIN_BYTES + 1), "ok"),
                new JudgeCase(null, 1, "", "x".repeat(Judge0ExecutionAdapter.MAX_EXPECTED_OUTPUT_BYTES + 1)));
        for (JudgeCase oversized : oversizedCases) {
            RecordingClient blockedClient = new RecordingClient(Set.of(71));
            Judge0ExecutionAdapter blocked = adapter(blockedClient, problem(oversized));
            assertThrows(IllegalArgumentException.class, () -> blocked.execute(command("print(1)")));
            assertTrue(blockedClient.requests.isEmpty());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> command("x".repeat(Judge0ExecutionAdapter.MAX_SOURCE_BYTES + 1)));
    }

    @Test
    void d3Jdg001RunUsesOnlyPublicCasesWhileSubmitAddsHiddenAndRepeatedPerformanceCases() {
        RecordingClient client = new RecordingClient(Set.of(71));
        JudgeProblem problem = new JudgeProblem(
                List.of(oneCase("public", "ok\n")),
                List.of(oneCase("hidden-1", "ok\n"), oneCase("hidden-2", "ok\n")),
                List.of(new JudgeCase("SMALL", 100, "small", "ok\n")));
        Judge0ExecutionAdapter adapter = adapter(client, problem);

        var runResult = adapter.execute(runCommand("print('ok')"));
        assertEquals(1, client.requests.size());
        assertEquals(1, runResult.totalCount());
        assertTrue(runResult.runtimeMeasurements().isEmpty());

        client.requests.clear();
        var submitResult = adapter.execute(command("print('ok')"));
        assertEquals(5, client.requests.size());
        assertEquals(2, submitResult.totalCount());
        assertEquals(1, submitResult.runtimeMeasurements().size());
        assertEquals(3, submitResult.runtimeMeasurements().getFirst().sampleCount());
    }

    @Test
    void d3Jdg001NormalizesUserFailuresAndProviderFailuresWithoutLeakingDiagnostics() {
        RecordingClient client = new RecordingClient(Set.of(71));
        Judge0ExecutionAdapter adapter = adapter(client, problem(oneCase("", "ok\n")));

        client.next = new Judge0Result("Compilation Error", 0, 0, "Python 3.8.1");
        assertEquals(JudgeStatus.COMPILATION_ERROR, adapter.execute(command("bad")).status());
        client.next = new Judge0Result("Runtime Error (NZEC)", 1, 262_144, "Python 3.8.1");
        assertEquals(JudgeStatus.MEMORY_LIMIT, adapter.execute(command("memory")).status());
        client.failure = new IllegalStateException("private provider diagnostic");
        var platformFailure = adapter.execute(command("private source"));
        assertEquals(JudgeStatus.PLATFORM_FAILURE, platformFailure.status());
        assertEquals(0, platformFailure.passedCount());
    }

    @Test
    void d3Jdg001CountsEveryHiddenCorrectnessCaseAfterWrongAnswers() {
        RecordingClient client = new RecordingClient(Set.of(71));
        client.next = new Judge0Result("Wrong Answer", 1_000, 1024, "Python 3.8.1");
        JudgeProblem problem = new JudgeProblem(
                List.of(oneCase("public", "ok\n")),
                List.of(oneCase("hidden-1", "ok\n"), oneCase("hidden-2", "ok\n")),
                List.of());

        var result = adapter(client, problem).execute(command("print('wrong')"));

        assertEquals(JudgeStatus.WRONG_ANSWER, result.status());
        assertEquals(0, result.passedCount());
        assertEquals(2, result.totalCount());
        assertEquals(2, client.requests.size());
    }

    private static Judge0ExecutionAdapter adapter(RecordingClient client, JudgeProblem problem) {
        return new Judge0ExecutionAdapter(
                client,
                (problemId, version) -> problemId.equals(PROBLEM_ID) && version == 1
                        ? java.util.Optional.of(problem)
                        : java.util.Optional.empty(),
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));
    }

    private static JudgeProblem problem(JudgeCase judgeCase) {
        return new JudgeProblem(List.of(judgeCase), List.of(judgeCase), List.of());
    }

    private static JudgeCase oneCase(String stdin, String expectedOutput) {
        return new JudgeCase(null, 1, stdin, expectedOutput);
    }

    private static SubmissionCommand command(String source) {
        return new SubmissionCommand(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                PROBLEM_ID,
                1,
                SubmissionMode.SUBMIT,
                JudgeLanguage.PYTHON3,
                source,
                1,
                "corr-1");
    }

    private static SubmissionCommand runCommand(String source) {
        return new SubmissionCommand(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                PROBLEM_ID,
                1,
                SubmissionMode.RUN,
                JudgeLanguage.PYTHON3,
                source,
                null,
                "corr-1");
    }

    private static final class RecordingClient implements Judge0Client {
        private Set<Integer> languageIds;
        private final List<Judge0Request> requests = new ArrayList<>();
        private Judge0Result next = new Judge0Result("Accepted", 1_000, 1024, "Python 3.8.1");
        private RuntimeException failure;

        private RecordingClient(Set<Integer> languageIds) {
            this.languageIds = languageIds;
        }

        @Override
        public Set<Integer> availableLanguageIds() {
            return languageIds;
        }

        @Override
        public Judge0Result execute(Judge0Request request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return next;
        }
    }
}
