package com.ddd.d3.judge.application;

import com.ddd.d3.judge.domain.JudgeLanguage;

public final class RuntimeUnavailableException extends RuntimeException {
    public RuntimeUnavailableException(JudgeLanguage language) {
        super("judge runtime is unavailable: " + language);
    }
}
