package com.ddd.d3.judge.adapter.http;

import com.ddd.d3.judge.application.EvidenceNotReadyException;
import com.ddd.d3.judge.application.IdempotencyConflictException;
import com.ddd.d3.judge.application.RuntimeUnavailableException;
import com.ddd.d3.judge.application.SubmissionNotFoundException;
import com.ddd.d3.judge.domain.PrivatePayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class JudgeHttpExceptionHandler {

    @ExceptionHandler(PrivatePayloadTooLargeException.class)
    ResponseEntity<JudgeErrorResponse> payloadTooLarge(
            PrivatePayloadTooLargeException exception, HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<JudgeErrorResponse> idempotencyConflict(
            IdempotencyConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(EvidenceNotReadyException.class)
    ResponseEntity<JudgeErrorResponse> evidenceNotReady(
            EvidenceNotReadyException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "EVIDENCE_NOT_READY", exception.getMessage(), request);
    }

    @ExceptionHandler(SubmissionNotFoundException.class)
    ResponseEntity<JudgeErrorResponse> notFound(
            SubmissionNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(RuntimeUnavailableException.class)
    ResponseEntity<JudgeErrorResponse> runtimeUnavailable(
            RuntimeUnavailableException exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_UNAVAILABLE", exception.getMessage(), request);
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<JudgeErrorResponse> badRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "judge request is invalid", request);
    }

    private static ResponseEntity<JudgeErrorResponse> error(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            correlationId = "unavailable";
        }
        return ResponseEntity.status(status).body(new JudgeErrorResponse(code, message, correlationId));
    }
}
