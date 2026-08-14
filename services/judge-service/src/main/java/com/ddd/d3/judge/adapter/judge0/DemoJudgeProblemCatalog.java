package com.ddd.d3.judge.adapter.judge0;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DemoJudgeProblemCatalog implements JudgeProblemCatalog {

    public static final UUID DEMO_SUM_PROBLEM_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000001");

    private final JudgeProblem problem = new JudgeProblem(
            List.of(new JudgeCase(null, 5, "5\n1 2 3 4 5\n", "15\n")),
            List.of(
                    new JudgeCase(null, 1, "1\n7\n", "7\n"),
                    new JudgeCase(null, 4, "4\n-5 10 -3 2\n", "4\n")),
            List.of(
                    sumOfOnes("SMALL", 100),
                    sumOfOnes("MEDIUM", 10_000),
                    sumOfOnes("LARGE", 100_000)));

    @Override
    public Optional<JudgeProblem> find(UUID problemId, int problemVersion) {
        return DEMO_SUM_PROBLEM_ID.equals(problemId) && problemVersion == 1
                ? Optional.of(problem)
                : Optional.empty();
    }

    private static JudgeCase sumOfOnes(String tier, int count) {
        return new JudgeCase(tier, count, count + "\n" + "1 ".repeat(count) + "\n", count + "\n");
    }
}
