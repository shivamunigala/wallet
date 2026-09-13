package com.shiva.wallet.domain.exception;

import org.springframework.http.HttpStatus;

/** Missing or unrecognised bearer token. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }
}
