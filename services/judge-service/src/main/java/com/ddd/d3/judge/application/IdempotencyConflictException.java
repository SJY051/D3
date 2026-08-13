package com.ddd.d3.judge.application;

public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("idempotency key was already used for a different submission command");
    }
}
