package com.ddd.d3.judge.adapter.fake;

import com.ddd.d3.judge.application.JudgeExecutionAdapter;
import com.ddd.d3.judge.domain.JudgeExecutionResult;
import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.JudgeStatus;
import com.ddd.d3.judge.domain.RuntimeMeasurement;
import com.ddd.d3.judge.domain.SubmissionCommand;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class DeterministicFakeJudgeAdapter implements JudgeExecutionAdapter {

    private final Clock clock;

    public DeterministicFakeJudgeAdapter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean isAvailable(JudgeLanguage language) {
        return language != null;
    }

    @Override
    public JudgeExecutionResult execute(SubmissionCommand command) {
        Objects.requireNonNull(command, "command");
        JudgeStatus status = switch (command.sourceCode().trim()) {
            case "D3_FAKE_ACCEPTED" -> JudgeStatus.ACCEPTED;
            case "D3_FAKE_COMPILATION_ERROR" -> JudgeStatus.COMPILATION_ERROR;
            case "D3_FAKE_RUNTIME_ERROR" -> JudgeStatus.RUNTIME_ERROR;
            case "D3_FAKE_TIME_LIMIT" -> JudgeStatus.TIME_LIMIT;
            case "D3_FAKE_MEMORY_LIMIT" -> JudgeStatus.MEMORY_LIMIT;
            case "D3_FAKE_PLATFORM_FAILURE" -> JudgeStatus.PLATFORM_FAILURE;
            default -> JudgeStatus.WRONG_ANSWER;
        };
        boolean accepted = status == JudgeStatus.ACCEPTED;
        int passedCount = accepted ? 3 : status == JudgeStatus.WRONG_ANSWER ? 2 : 0;
        List<RuntimeMeasurement> measurements = switch (status) {
            case ACCEPTED -> List.of(
                    new RuntimeMeasurement("SMALL", 100, 3, 800),
                    new RuntimeMeasurement("MEDIUM", 10_000, 3, 2_500),
                    new RuntimeMeasurement("LARGE", 100_000, 3, 9_000));
            case WRONG_ANSWER -> List.of(
                    new RuntimeMeasurement("SMALL", 100, 3, 900),
                    new RuntimeMeasurement("MEDIUM", 10_000, 3, 3_100));
            default -> List.of();
        };

        return new JudgeExecutionResult(
                status,
                passedCount,
                3,
                measurements,
                "fake-v1",
                "fake-" + command.language().name().toLowerCase() + "-v1",
                clock.instant());
    }
}
