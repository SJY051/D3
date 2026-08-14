package com.ddd.d3.judge.adapter.judge0;

import com.ddd.d3.judge.application.JudgeExecutionAdapter;
import com.ddd.d3.judge.domain.JudgeExecutionResult;
import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.JudgeStatus;
import com.ddd.d3.judge.domain.RuntimeMeasurement;
import com.ddd.d3.judge.domain.SubmissionCommand;
import com.ddd.d3.judge.domain.SubmissionMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Judge0ExecutionAdapter implements JudgeExecutionAdapter {

    static final int MAX_SOURCE_BYTES = 65_536;
    static final int MAX_STDIN_BYTES = 262_144;
    static final int MAX_EXPECTED_OUTPUT_BYTES = 262_144;

    private static final Map<JudgeLanguage, RuntimeBinding> RUNTIMES = Map.of(
            JudgeLanguage.C, new RuntimeBinding(50, "GCC 9.2.0"),
            JudgeLanguage.CPP, new RuntimeBinding(54, "GCC 9.2.0"),
            JudgeLanguage.JAVA, new RuntimeBinding(62, "OpenJDK 13.0.1"),
            JudgeLanguage.PYTHON3, new RuntimeBinding(71, "Python 3.8.1"),
            JudgeLanguage.JAVASCRIPT, new RuntimeBinding(63, "Node.js 12.14.0"),
            JudgeLanguage.TYPESCRIPT, new RuntimeBinding(74, "TypeScript 3.7.4"));
    private static final int CPU_TIME_LIMIT_SECONDS = 2;
    private static final int WALL_TIME_LIMIT_SECONDS = 5;
    private static final int MEMORY_LIMIT_KIB = 262_144;
    private static final int STACK_LIMIT_KIB = 65_536;
    private static final int PROCESS_LIMIT = 60;
    private static final int FILE_SIZE_LIMIT_KIB = 1_024;
    private static final int PERFORMANCE_SAMPLE_COUNT = 3;

    private final Judge0Client client;
    private final JudgeProblemCatalog problemCatalog;
    private final Clock clock;

    public Judge0ExecutionAdapter(Judge0Client client, JudgeProblemCatalog problemCatalog, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.problemCatalog = Objects.requireNonNull(problemCatalog, "problemCatalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean isAvailable(JudgeLanguage language) {
        RuntimeBinding runtime = RUNTIMES.get(language);
        if (runtime == null) {
            return false;
        }
        try {
            return client.availableLanguageIds().contains(runtime.languageId());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public JudgeExecutionResult execute(SubmissionCommand command) {
        Objects.requireNonNull(command, "command");
        JudgeProblem problem = problemCatalog.find(command.problemId(), command.problemVersion()).orElse(null);
        if (problem == null) {
            return platformFailure(command, 0);
        }

        List<JudgeCase> correctnessCases = command.mode() == SubmissionMode.RUN
                ? problem.publicCases()
                : problem.hiddenCorrectnessCases();
        validatePayloads(command, correctnessCases, command.mode() == SubmissionMode.SUBMIT
                ? problem.performanceCases()
                : List.of());

        int passedCount = 0;
        boolean wrongAnswerObserved = false;
        String runtimeVersion = "unavailable";
        try {
            for (JudgeCase judgeCase : correctnessCases) {
                Judge0Result result = client.execute(request(command, judgeCase));
                runtimeVersion = RUNTIMES.get(command.language()).runtimeVersion();
                JudgeStatus status = normalize(result);
                if (status == JudgeStatus.ACCEPTED) {
                    passedCount++;
                    continue;
                }
                if (status == JudgeStatus.WRONG_ANSWER) {
                    wrongAnswerObserved = true;
                    continue;
                }
                return result(
                        status,
                        passedCount,
                        correctnessCases.size(),
                        List.of(),
                        runtimeVersion);
            }

            if (wrongAnswerObserved) {
                return result(
                        JudgeStatus.WRONG_ANSWER,
                        passedCount,
                        correctnessCases.size(),
                        List.of(),
                        runtimeVersion);
            }

            List<RuntimeMeasurement> measurements = new ArrayList<>();
            if (command.mode() == SubmissionMode.SUBMIT) {
                for (JudgeCase judgeCase : problem.performanceCases()) {
                    List<Long> samples = new ArrayList<>();
                    for (int sample = 0; sample < PERFORMANCE_SAMPLE_COUNT; sample++) {
                        Judge0Result result = client.execute(request(command, judgeCase));
                        runtimeVersion = RUNTIMES.get(command.language()).runtimeVersion();
                        JudgeStatus status = normalize(result);
                        if (status != JudgeStatus.ACCEPTED) {
                            return result(status, passedCount, correctnessCases.size(), measurements, runtimeVersion);
                        }
                        samples.add(result.cpuTimeMicros());
                    }
                    samples.sort(Comparator.naturalOrder());
                    measurements.add(new RuntimeMeasurement(
                            judgeCase.tier(),
                            judgeCase.inputSize(),
                            PERFORMANCE_SAMPLE_COUNT,
                            samples.get(PERFORMANCE_SAMPLE_COUNT / 2)));
                }
            }

            return result(
                    JudgeStatus.ACCEPTED,
                    passedCount,
                    correctnessCases.size(),
                    measurements,
                    runtimeVersion);
        } catch (Judge0ClientException exception) {
            return platformFailure(command, correctnessCases.size());
        }
    }

    private static void validatePayloads(
            SubmissionCommand command, List<JudgeCase> correctnessCases, List<JudgeCase> performanceCases) {
        requireWithinLimit("sourceCode", command.sourceCode(), MAX_SOURCE_BYTES);
        if (correctnessCases.isEmpty()) {
            throw new IllegalArgumentException("problem has no evaluation cases for " + command.mode());
        }
        for (JudgeCase judgeCase : correctnessCases) {
            validateCase(judgeCase);
        }
        for (JudgeCase judgeCase : performanceCases) {
            validateCase(judgeCase);
            if (judgeCase.tier() == null || judgeCase.tier().isBlank() || judgeCase.inputSize() <= 0) {
                throw new IllegalArgumentException("performance case requires a tier and positive input size");
            }
        }
    }

    private static void validateCase(JudgeCase judgeCase) {
        Objects.requireNonNull(judgeCase, "judgeCase");
        requireWithinLimit("stdin", judgeCase.stdin(), MAX_STDIN_BYTES);
        requireWithinLimit("expectedOutput", judgeCase.expectedOutput(), MAX_EXPECTED_OUTPUT_BYTES);
    }

    private static void requireWithinLimit(String field, String value, int maximumBytes) {
        Objects.requireNonNull(value, field);
        if (value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(field + " exceeds the " + maximumBytes + "-byte limit");
        }
    }

    private static Judge0Request request(SubmissionCommand command, JudgeCase judgeCase) {
        return new Judge0Request(
                RUNTIMES.get(command.language()).languageId(),
                command.sourceCode(),
                judgeCase.stdin(),
                judgeCase.expectedOutput(),
                CPU_TIME_LIMIT_SECONDS,
                WALL_TIME_LIMIT_SECONDS,
                MEMORY_LIMIT_KIB,
                STACK_LIMIT_KIB,
                PROCESS_LIMIT,
                FILE_SIZE_LIMIT_KIB,
                false);
    }

    private JudgeStatus normalize(Judge0Result result) {
        if (result.statusDescription().startsWith("Runtime Error (")) {
            return result.memoryKib() >= MEMORY_LIMIT_KIB
                    ? JudgeStatus.MEMORY_LIMIT
                    : JudgeStatus.RUNTIME_ERROR;
        }
        return switch (result.statusDescription()) {
            case "Accepted" -> JudgeStatus.ACCEPTED;
            case "Wrong Answer" -> JudgeStatus.WRONG_ANSWER;
            case "Compilation Error" -> JudgeStatus.COMPILATION_ERROR;
            case "Time Limit Exceeded" -> JudgeStatus.TIME_LIMIT;
            default -> JudgeStatus.PLATFORM_FAILURE;
        };
    }

    private JudgeExecutionResult platformFailure(SubmissionCommand command, int totalCount) {
        return result(JudgeStatus.PLATFORM_FAILURE, 0, totalCount, List.of(), "unavailable");
    }

    private JudgeExecutionResult result(
            JudgeStatus status,
            int passedCount,
            int totalCount,
            List<RuntimeMeasurement> measurements,
            String runtimeVersion) {
        return new JudgeExecutionResult(
                status,
                passedCount,
                totalCount,
                measurements,
                "judge0-ce-1.13.1",
                runtimeVersion == null || runtimeVersion.isBlank() ? "unavailable" : runtimeVersion,
                clock.instant());
    }

    private record RuntimeBinding(int languageId, String runtimeVersion) {}
}
