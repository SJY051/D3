package com.ddd.d3.judge.adapter.judge0;

import java.util.Optional;
import java.util.UUID;

public interface JudgeProblemCatalog {
    Optional<JudgeProblem> find(UUID problemId, int problemVersion);
}
