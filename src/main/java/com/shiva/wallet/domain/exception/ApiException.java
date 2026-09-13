package com.shiva.wallet.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for errors that map to a deliberate HTTP response rather than a 500.
 *
 * <p>Carrying the status and a stable machine-readable code on the exception keeps
 * {@link com.shiva.wallet.web.ApiExceptionHandler} free of type-switching.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
