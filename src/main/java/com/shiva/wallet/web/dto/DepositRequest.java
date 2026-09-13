package com.shiva.wallet.web.dto;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

/** Body of POST /wallets/{id}/deposit. */
public class DepositRequest {

    @NotNull
    @Positive
    private Long amountPaise;

    public Long getAmountPaise() {
        return amountPaise;
    }

    public void setAmountPaise(Long amountPaise) {
        this.amountPaise = amountPaise;
    }
}
