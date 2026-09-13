package com.shiva.wallet.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

/**
 * Body of POST /transfers.
 *
 * <p>{@code amountPaise} is a {@code Long} rather than a primitive so that a missing field
 * fails validation instead of silently defaulting to zero, and integer paise rather than a
 * decimal amount so that no floating point arithmetic can ever touch money.
 */
public class TransferRequest {

    @NotNull
    private Long from;

    @NotNull
    private Long to;

    @NotNull
    @Positive
    private Long amountPaise;

    @NotBlank
    @Size(max = 255)
    private String idempotencyKey;

    public Long getFrom() {
        return from;
    }

    public void setFrom(Long from) {
        this.from = from;
    }

    public Long getTo() {
        return to;
    }

    public void setTo(Long to) {
        this.to = to;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public void setAmountPaise(Long amountPaise) {
        this.amountPaise = amountPaise;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
