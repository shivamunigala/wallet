package com.shiva.wallet.domain;

/**
 * Why a ledger entry exists.
 *
 * <p>The distinction matters for the conservation check: TRANSFER entries always sum to
 * zero across the system, while DEPOSIT entries are the money-in boundary and are the
 * only thing that legitimately changes the system total.
 */
public enum LedgerEntryType {

    /** One half of a transfer. Always paired with an equal and opposite entry. */
    TRANSFER,

    /** External money entering the system. Unpaired by definition. */
    DEPOSIT
}
