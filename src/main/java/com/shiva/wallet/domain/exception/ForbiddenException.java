package com.shiva.wallet.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * The caller is authenticated but does not own the wallet being debited.
 *
 * <p>Kept distinct from 401 so the burst script can tell "bad token" from "wrong wallet".
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "forbidden", message);
    }
}
