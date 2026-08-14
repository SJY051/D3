package com.ddd.d3.identity.adapter.http;

import com.ddd.d3.identity.application.AccountNotFoundException;
import com.ddd.d3.identity.application.DuplicateAccountException;
import com.ddd.d3.identity.application.InvalidCredentialsException;
import com.ddd.d3.identity.application.RefreshTokenRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class IdentityHttpExceptionHandler {

    @ExceptionHandler(DuplicateAccountException.class)
    ResponseEntity<IdentityErrorResponse> duplicate(DuplicateAccountException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_ACCOUNT", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<IdentityErrorResponse> invalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), request);
    }

    @ExceptionHandler(RefreshTokenRejectedException.class)
    ResponseEntity<IdentityErrorResponse> refreshRejected(
            RefreshTokenRejectedException exception, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "REFRESH_REJECTED", exception.getMessage(), request);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<IdentityErrorResponse> notFound(AccountNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<IdentityErrorResponse> badRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "identity request is invalid", request);
    }

    private static ResponseEntity<IdentityErrorResponse> error(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            correlationId = "unavailable";
        }
        return ResponseEntity.status(status).body(new IdentityErrorResponse(code, message, correlationId));
    }
}
