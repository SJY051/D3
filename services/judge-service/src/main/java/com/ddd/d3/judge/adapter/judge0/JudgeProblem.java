package com.ddd.d3.judge.adapter.judge0;

import java.util.List;

public record JudgeProblem(
        List<JudgeCase> publicCases,
        List<JudgeCase> hiddenCorrectnessCases,
        List<JudgeCase> performanceCases) {

    public JudgeProblem {
        publicCases = List.copyOf(publicCases);
        hiddenCorrectnessCases = List.copyOf(hiddenCorrectnessCases);
        performanceCases = List.copyOf(performanceCases);
    }
}
