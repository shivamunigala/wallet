package com.shiva.wallet.web.dto;

import com.shiva.wallet.domain.Transfer;

import java.time.Instant;

/**
 * Representation of a transfer, identical whether it was just applied, replayed from an
 * earlier identical request, or fetched by id. That sameness is the point: a retry cannot
 * tell it did not do the work.
 */
public class TransferResponse {

    private final String id;
    private final long from;
    private final long to;
    private final long amountPaise;
    private final String status;
    private final String idempotencyKey;
    private final Instant createdAt;

    public TransferResponse(Transfer transfer) {
        this.id = transfer.getPublicId().toString();
        this.from = transfer.getFromWalletId();
        this.to = transfer.getToWalletId();
        this.amountPaise = transfer.getAmountPaise();
        this.status = transfer.getStatus().name();
        this.idempotencyKey = transfer.getIdempotencyKey();
        this.createdAt = transfer.getCreatedAt();
    }

    public String getId() {
        return id;
    }

    public long getFrom() {
        return from;
    }

    public long getTo() {
        return to;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
