package com.shiva.wallet.web;

import com.shiva.wallet.domain.exception.ApiException;
import com.shiva.wallet.web.dto.ErrorResponse;
import com.shiva.wallet.web.filter.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into the uniform error body.
 *
 * <p>Every response carries the correlation id, so a failure reported by the burst script
 * can be found in the log stream without guessing. Unexpected exceptions are the only ones
 * logged at ERROR with a stack trace — declines and idempotency conflicts are normal
 * outcomes of a correct system under load, and logging them as errors would drown the real
 * signal during a burst.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorResponse(e.getCode(), e.getMessage(), correlationId(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid_request", "Request validation failed",
                        correlationId(), fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid_request", "Request body is missing or malformed",
                        correlationId(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("request.failed_unexpectedly", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal_error", "Unexpected error", correlationId(), null));
    }

    private static String correlationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }
}
