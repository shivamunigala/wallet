package com.shiva.wallet.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

/**
 * A user's wallet. One per user, enforced by a UNIQUE constraint on {@code user_id} —
 * that constraint is what makes get-or-create race-free.
 *
 * <p>The balance is authoritative and always integer <strong>paise</strong>. It is never
 * mutated through this entity: every balance change goes through the conditional SQL in
 * {@link com.shiva.wallet.repository.WalletRepository}, so the no-overdraft predicate and
 * the row lock are always applied. Treat this class as a read model.
 *
 * <p>See docs/DATA-MODEL.md and docs/CONCURRENCY.md.
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "balance_paise", nullable = false)
    private long balancePaise;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected Wallet() {
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
