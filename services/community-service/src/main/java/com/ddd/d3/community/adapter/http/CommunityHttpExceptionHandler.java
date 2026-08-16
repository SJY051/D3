package com.ddd.d3.community.adapter.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class CommunityHttpExceptionHandler {

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<CommunityErrorResponse> badRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "community request is invalid", request);
    }

    @ExceptionHandler(CommunityMatchRecordNotFoundException.class)
    ResponseEntity<CommunityErrorResponse> notFound(
            CommunityMatchRecordNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "MATCH_RECORD_NOT_FOUND", "match record was not found", request);
    }

    private static ResponseEntity<CommunityErrorResponse> error(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            correlationId = "unavailable";
        }
        return ResponseEntity.status(status).body(new CommunityErrorResponse(code, message, correlationId));
    }
}

final class CommunityMatchRecordNotFoundException extends RuntimeException {}
