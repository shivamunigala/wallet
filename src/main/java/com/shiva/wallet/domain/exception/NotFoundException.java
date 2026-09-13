package com.shiva.wallet.domain.exception;

import org.springframework.http.HttpStatus;

/** A referenced wallet or transfer does not exist. */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "not_found", message);
    }
}
