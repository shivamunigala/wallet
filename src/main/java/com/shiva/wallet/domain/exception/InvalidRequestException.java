package com.shiva.wallet.domain.exception;

import org.springframework.http.HttpStatus;

/** A semantically invalid request that bean validation cannot express, e.g. from == to. */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }
}
