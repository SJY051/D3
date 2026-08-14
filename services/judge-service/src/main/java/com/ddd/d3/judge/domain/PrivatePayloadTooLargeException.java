package com.ddd.d3.judge.domain;

public final class PrivatePayloadTooLargeException extends IllegalArgumentException {
    public PrivatePayloadTooLargeException(String field, int maximumBytes) {
        super(field + " exceeds the " + maximumBytes + "-byte limit");
    }
}
