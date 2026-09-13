package com.shiva.wallet.service;

import com.shiva.wallet.config.WalletMetrics;
import com.shiva.wallet.domain.Transfer;
import com.shiva.wallet.domain.TransferStatus;
import com.shiva.wallet.domain.exception.IdempotencyConflictException;
import com.shiva.wallet.domain.exception.InvalidRequestException;
import com.shiva.wallet.domain.exception.NotFoundException;
import com.shiva.wallet.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates a transfer: validate, execute, and resolve idempotent replays.
 *
 * <p>Deliberately <strong>not</strong> transactional. The unique-constraint violation that
 * signals a replay aborts the database transaction, so it has to be caught after that
 * transaction has rolled back — which means outside it. {@link TransferExecutor} owns the
 * transaction; this class owns everything around it.
 *
 * <p>Replays are handled on two paths, and both are needed:
 * <ul>
 *   <li><em>Fast path</em> — a retry arriving after the original committed is found by a
 *       plain indexed read, with no exception thrown.
 *   <li><em>Race path</em> — a retry arriving while the original is still in flight blocks
 *       on the unique index, then surfaces as a constraint violation once the original
 *       commits. Catching it and re-reading yields the same answer.
 * </ul>
 *
 * <p>Either way the caller gets the original result, and the money moved exactly once.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferExecutor transferExecutor;
    private final TransferPreflight preflight;
    private final TransferRepository transferRepository;
    private final RequestHasher requestHasher;
    private final WalletMetrics metrics;

    public TransferService(TransferExecutor transferExecutor,
                           TransferRepository transferRepository,
                           RequestHasher requestHasher,
                           TransferPreflight preflight,
                           WalletMetrics metrics) {
        this.transferExecutor = transferExecutor;
        this.preflight = preflight;
        this.transferRepository = transferRepository;
        this.requestHasher = requestHasher;
        this.metrics = metrics;
    }

    public Transfer transfer(Long callerUserId,
                             Long fromWalletId,
                             Long toWalletId,
                             long amountPaise,
                             String idempotencyKey) {

        if (amountPaise <= 0) {
            throw new InvalidRequestException("amount_paise must be a positive number of paise");
        }
        if (fromWalletId.equals(toWalletId)) {
            throw new InvalidRequestException("from and to must be different wallets");
        }

        String requestHash = requestHasher.hash(fromWalletId, toWalletId, amountPaise);

        // Wallet checks and the idempotency lookup share one read-only transaction, so this
        // is a single connection acquisition rather than three. See TransferPreflight.
        Optional<Transfer> alreadyApplied =
                preflight.inspect(callerUserId, fromWalletId, toWalletId, idempotencyKey);
        if (alreadyApplied.isPresent()) {
            return replayOf(alreadyApplied.get(), idempotencyKey, requestHash);
        }

        try {
            Transfer transfer = transferExecutor.execute(
                    callerUserId, fromWalletId, toWalletId, amountPaise, idempotencyKey, requestHash);
            if (transfer.getStatus() == TransferStatus.COMPLETED) {
                metrics.transferCreated();
            } else {
                metrics.transferDeclined();
            }
            return transfer;
        } catch (DataIntegrityViolationException e) {
            // Either a same-key race, or a genuine data problem. Only the former resolves
            // to an existing transfer; anything else is rethrown untouched.
            Transfer existing = transferRepository
                    .findByCreatedByUserAndIdempotencyKey(callerUserId, idempotencyKey)
                    .orElseThrow(() -> e);
            return replayOf(existing, idempotencyKey, requestHash);
        }
    }

    public Transfer findByPublicId(UUID publicId) {
        return transferRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Transfer " + publicId + " does not exist"));
    }

    /**
     * Resolves a repeat of an already-recorded idempotency key. Same body returns the
     * original outcome; different body is a conflict rather than a second debit.
     */
    private Transfer replayOf(Transfer existing, String idempotencyKey, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            metrics.idempotencyConflict();
            log.warn("transfer.idempotency_conflict idempotency_key={} existing_transfer_id={}",
                    idempotencyKey, existing.getPublicId());
            throw new IdempotencyConflictException(idempotencyKey);
        }
        metrics.idempotentReplay();
        log.info("transfer.idempotent_replay transfer_id={} idempotency_key={} status={}",
                existing.getPublicId(), idempotencyKey, existing.getStatus());
        return existing;
    }
}
