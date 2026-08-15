package com.ddd.d3.battle.application;

import java.util.Objects;

public interface JudgeServiceTokenProvider {

    Token acquire(Scope scope);

    enum Scope {
        SUBMIT("judge.submit"),
        READ("judge.read");

        private final String claimValue;

        Scope(String claimValue) {
            this.claimValue = claimValue;
        }

        public String claimValue() {
            return claimValue;
        }
    }

    record Token(String value, long expiresInSeconds) {
        public Token {
            Objects.requireNonNull(value, "value");
            if (value.isBlank()) {
                throw new IllegalArgumentException("value must not be blank");
            }
            if (expiresInSeconds <= 0) {
                throw new IllegalArgumentException("expiresInSeconds must be positive");
            }
        }

        public String authorizationHeader() {
            return "Bearer " + value;
        }
    }
}
