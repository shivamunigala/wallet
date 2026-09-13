package com.shiva.wallet.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single attempt to move money, successful or declined.
 *
 * <p>This row is the idempotency record. The UNIQUE constraint on
 * {@code (created_by_user, idempotency_key)} is inserted in the <em>same transaction</em>
 * as the debit and credit, so the key and the money commit or roll back together — there
 * is no window in which one exists without the other.
 *
 * <p>{@code requestHash} fingerprints the request body. A replay carrying the same key
 * with a different body is a conflict, not a second debit.
 *
 * <p>Two identifiers on purpose: {@code id} is the internal BIGSERIAL used by foreign keys,
 * {@code publicId} is the UUID exposed over the API so that internal row counts are not
 * leaked and ids are not guessable.
 *
 * <p>See docs/CONCURRENCY.md.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_by_user", nullable = false, updatable = false)
    private Long createdByUser;

    @Column(name = "from_wallet_id", nullable = false, updatable = false)
    private Long fromWalletId;

    @Column(name = "to_wallet_id", nullable = false, updatable = false)
    private Long toWalletId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransferStatus status;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Transfer() {
    }

    public Transfer(UUID publicId,
                    String idempotencyKey,
                    Long createdByUser,
                    Long fromWalletId,
                    Long toWalletId,
                    long amountPaise,
                    TransferStatus status,
                    String requestHash) {
        this.publicId = publicId;
        this.idempotencyKey = idempotencyKey;
        this.createdByUser = createdByUser;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amountPaise = amountPaise;
        this.status = status;
        this.requestHash = requestHash;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getCreatedByUser() {
        return createdByUser;
    }

    public Long getFromWalletId() {
        return fromWalletId;
    }

    public Long getToWalletId() {
        return toWalletId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
