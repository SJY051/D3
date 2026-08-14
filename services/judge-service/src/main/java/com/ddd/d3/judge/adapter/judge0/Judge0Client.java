package com.ddd.d3.judge.adapter.judge0;

import java.util.Set;

public interface Judge0Client {
    Set<Integer> availableLanguageIds();

    Judge0Result execute(Judge0Request request);
}
