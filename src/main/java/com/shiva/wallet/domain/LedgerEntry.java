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

/**
 * One half of a balance change, written in the same transaction as the balance itself.
 *
 * <p>The ledger exists to make conservation <em>provable without trusting the balance
 * column</em>: summing {@code delta_paise} over all TRANSFER entries must yield zero, no
 * matter how many transfers ran concurrently. The burst script asserts exactly that.
 *
 * <p>Append-only. Nothing updates or deletes a ledger entry.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for DEPOSIT entries, which have no originating transfer. */
    @Column(name = "transfer_id", updatable = false)
    private Long transferId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private Long walletId;

    /** Negative for a debit, positive for a credit. Never zero. */
    @Column(name = "delta_paise", nullable = false, updatable = false)
    private long deltaPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false)
    private LedgerEntryType entryType;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    private LedgerEntry(Long transferId, Long walletId, long deltaPaise, LedgerEntryType entryType) {
        this.transferId = transferId;
        this.walletId = walletId;
        this.deltaPaise = deltaPaise;
        this.entryType = entryType;
    }

    public static LedgerEntry forTransfer(Long transferId, Long walletId, long deltaPaise) {
        return new LedgerEntry(transferId, walletId, deltaPaise, LedgerEntryType.TRANSFER);
    }

    public static LedgerEntry forDeposit(Long walletId, long amountPaise) {
        return new LedgerEntry(null, walletId, amountPaise, LedgerEntryType.DEPOSIT);
    }

    public Long getId() {
        return id;
    }

    public Long getTransferId() {
        return transferId;
    }

    public Long getWalletId() {
        return walletId;
    }

    public long getDeltaPaise() {
        return deltaPaise;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
