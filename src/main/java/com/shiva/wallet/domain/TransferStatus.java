package com.shiva.wallet.domain;

/**
 * Terminal states of a transfer. There is deliberately no PENDING state in committed
 * data: the debit, the credit and the status are written in one transaction, so a
 * transfer is either fully applied or absent. See docs/CONCURRENCY.md.
 */
public enum TransferStatus {

    /**
     * Transient only. The transfer row is inserted with this status before any money
     * moves, so that a concurrent request carrying the same idempotency key blocks on the
     * unique index immediately rather than duplicating work. It is always overwritten
     * with a terminal status before the transaction commits.
     */
    PENDING,

    /** Money moved. Exactly two ledger entries exist, summing to zero. */
    COMPLETED,

    /** Source wallet could not cover the amount. No money moved, no ledger entries. */
    DECLINED_INSUFFICIENT_FUNDS
}
