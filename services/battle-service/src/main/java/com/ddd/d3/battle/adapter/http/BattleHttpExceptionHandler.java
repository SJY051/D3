package com.ddd.d3.battle.adapter.http;

import com.ddd.d3.battle.application.NoActiveRankedProblemException;
import com.ddd.d3.battle.application.RankedQueueBusyException;
import com.ddd.d3.battle.application.RankedQueueConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class BattleHttpExceptionHandler {

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<BattleErrorResponse> invalidRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "request is invalid", request);
    }

    @ExceptionHandler(RankedQueueConflictException.class)
    ResponseEntity<BattleErrorResponse> queueConflict(
            RankedQueueConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "QUEUE_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler({RankedQueueBusyException.class, NoActiveRankedProblemException.class})
    ResponseEntity<BattleErrorResponse> unavailable(RuntimeException exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_UNAVAILABLE", exception.getMessage(), request);
    }

    private static ResponseEntity<BattleErrorResponse> error(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new BattleErrorResponse(code, message, correlationId(request)));
    }

    private static String correlationId(HttpServletRequest request) {
        String value = request.getHeader("X-Correlation-Id");
        if (value == null || value.isBlank() || value.length() > 128) {
            return "unavailable";
        }
        return value;
    }
}
