package com.shiva.wallet.service;

import com.shiva.wallet.domain.LedgerEntry;
import com.shiva.wallet.domain.Transfer;
import com.shiva.wallet.domain.TransferStatus;
import com.shiva.wallet.domain.exception.NotFoundException;
import com.shiva.wallet.repository.LedgerEntryRepository;
import com.shiva.wallet.repository.TransferRepository;
import com.shiva.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The single transaction in which money moves. This class is the correctness argument of
 * the whole service, so the ordering below is deliberate and should not be rearranged.
 *
 * <p><strong>1. Insert the transfer row first.</strong> The row carries the idempotency key
 * under a UNIQUE constraint. Because the id is IDENTITY-generated, JPA issues the INSERT
 * immediately rather than at flush time, so a concurrent request with the same key blocks
 * on the unique index right here — before any lock is taken and before any money moves.
 * When this transaction commits, the loser wakes, fails cleanly, and reads this row.
 *
 * <p><strong>2. Lock both wallets in ascending id order.</strong> Two explicit
 * {@code SELECT ... FOR UPDATE} statements, lower id first. This is the entire deadlock
 * story: simultaneous A&rarr;B and B&rarr;A transfers request the same two locks in the
 * same order, so one simply waits instead of the pair deadlocking.
 *
 * <p><strong>3. Conditional debit.</strong> The balance predicate lives in the WHERE
 * clause, so an overdraft returns 0 rows. A 0-row UPDATE is <em>not</em> an error in
 * Postgres, which means the transaction is still alive and we can record the decline and
 * commit it. That is why a declined transfer is durable and idempotent on retry, rather
 * than vanishing in a rollback.
 *
 * <p><strong>4. Credit and ledger.</strong> The credit and both ledger rows are written in
 * this same transaction, which is what makes conservation hold: there is no instant at
 * which the debit is visible without the matching credit.
 *
 * <p>Kept as its own bean, not a method on {@link TransferService}, because the caller has
 * to catch the unique-constraint violation <em>outside</em> the transaction boundary — a
 * self-invocation would bypass the proxy and leave the caller holding an already-aborted
 * transaction.
 *
 * <p>See docs/CONCURRENCY.md.
 */
@Component
public class TransferExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransferExecutor.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransferExecutor(WalletRepository walletRepository,
                            TransferRepository transferRepository,
                            LedgerEntryRepository ledgerEntryRepository) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transfer execute(Long callerUserId,
                            Long fromWalletId,
                            Long toWalletId,
                            long amountPaise,
                            String idempotencyKey,
                            String requestHash) {

        // 1. Claim the idempotency key before doing anything else.
        Transfer transfer = transferRepository.saveAndFlush(new Transfer(
                UUID.randomUUID(), idempotencyKey, callerUserId,
                fromWalletId, toWalletId, amountPaise,
                TransferStatus.PENDING, requestHash));

        log.info("transfer.created transfer_id={} from_wallet={} to_wallet={} amount_paise={}",
                transfer.getPublicId(), fromWalletId, toWalletId, amountPaise);

        // 2. Deterministic lock order. Lower wallet id always first.
        Long firstLock = Math.min(fromWalletId, toWalletId);
        Long secondLock = Math.max(fromWalletId, toWalletId);
        lockOrThrow(firstLock);
        lockOrThrow(secondLock);

        // 3. Conditional debit. Zero rows means "cannot cover it", not "something broke".
        int debited = walletRepository.tryDebit(fromWalletId, amountPaise);
        if (debited == 0) {
            transfer.setStatus(TransferStatus.DECLINED_INSUFFICIENT_FUNDS);
            log.info("transfer.declined transfer_id={} from_wallet={} amount_paise={} reason=insufficient_funds",
                    transfer.getPublicId(), fromWalletId, amountPaise);
            return transfer;
        }
        log.info("transfer.debited transfer_id={} wallet={} amount_paise={}",
                transfer.getPublicId(), fromWalletId, amountPaise);

        // 4. Credit and audit trail, same transaction as the debit.
        walletRepository.credit(toWalletId, amountPaise);
        log.info("transfer.credited transfer_id={} wallet={} amount_paise={}",
                transfer.getPublicId(), toWalletId, amountPaise);

        ledgerEntryRepository.save(LedgerEntry.forTransfer(transfer.getId(), fromWalletId, -amountPaise));
        ledgerEntryRepository.save(LedgerEntry.forTransfer(transfer.getId(), toWalletId, amountPaise));

        transfer.setStatus(TransferStatus.COMPLETED);
        return transfer;
    }

    private void lockOrThrow(Long walletId) {
        walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new NotFoundException("Wallet " + walletId + " does not exist"));
    }
}
