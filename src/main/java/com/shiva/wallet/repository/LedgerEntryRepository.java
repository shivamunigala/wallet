package com.shiva.wallet.repository;

import com.shiva.wallet.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Append-only audit trail.
 *
 * <p>{@link #transferEntriesNetSum} is the independent conservation check: summing every
 * TRANSFER entry in the system must yield exactly zero, because each transfer writes an
 * equal and opposite pair. It does not read the balance column at all, so it catches a
 * class of bug that comparing balances to themselves would miss.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    @Query(value = "SELECT COALESCE(SUM(delta_paise), 0) FROM ledger_entries WHERE entry_type = 'TRANSFER'",
            nativeQuery = true)
    long transferEntriesNetSum();

    List<LedgerEntry> findByWalletIdOrderByIdAsc(Long walletId);
}
