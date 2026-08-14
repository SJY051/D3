package com.ddd.d3.identity.adapter.http;

public record IdentityErrorResponse(String code, String message, String correlationId) {}
