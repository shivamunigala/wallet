package com.shiva.wallet.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * The idempotency key has been used before, with a <em>different</em> request body.
 *
 * <p>This is the case the brief calls out explicitly: a reused key with a different body
 * must be a 409, never a second debit. An identical replay is not an error and never
 * reaches this exception — it returns the original result.
 */
public class IdempotencyConflictException extends ApiException {

    public IdempotencyConflictException(String idempotencyKey) {
        super(HttpStatus.CONFLICT, "idempotency_conflict",
                "Idempotency key '" + idempotencyKey + "' was already used with a different request body");
    }
}
