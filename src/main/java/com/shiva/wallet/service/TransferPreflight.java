package com.shiva.wallet.service;

import com.shiva.wallet.domain.Transfer;
import com.shiva.wallet.domain.Wallet;
import com.shiva.wallet.domain.exception.ForbiddenException;
import com.shiva.wallet.domain.exception.NotFoundException;
import com.shiva.wallet.repository.TransferRepository;
import com.shiva.wallet.repository.WalletRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The read-only checks that run before a transfer is attempted: both wallets exist, the
 * caller owns the source, and the idempotency key has or has not been seen before.
 *
 * <p>Responsible for doing all of that in <b>one</b> database round trip. It exists as its
 * own bean for that reason alone — {@link TransferService} cannot annotate a method it
 * calls on itself, because self-invocation bypasses the transactional proxy.
 *
 * <p>Why it is worth a class: round trips are the scarce resource in this service, because
 * a transfer holds row locks while it makes them. These three checks used to be three
 * separate repository calls on a non-transactional path, so each one opened its own
 * implicit transaction, borrowed a pooled connection, and gave it back — three
 * acquisitions and three round trips before the transfer had even started. Against
 * Postgres on the same Docker network that is invisible; against managed Postgres one
 * network hop away it is most of the request. Sharing a single read-only transaction makes
 * it one acquisition, and fetching both wallets with one {@code findAllById} makes it two
 * queries rather than three.
 *
 * <p>Read-only and non-locking on purpose. Nothing here is a guarantee — a wallet can be
 * drained between this check and the transfer. It exists only to turn the common failures
 * into clean 404s and 403s. Every invariant that actually matters is enforced inside
 * {@link TransferExecutor}'s transaction, under row locks, by the conditional {@code UPDATE}
 * and the unique constraint on the idempotency key.
 *
 * <p>It must stay <b>outside</b> the transfer transaction. A replay is resolved from what
 * this returns, and a unique-constraint violation aborts a Postgres transaction — which is
 * exactly why {@link TransferService} and {@link TransferExecutor} are separate beans.
 */
@Component
public class TransferPreflight {

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;

    public TransferPreflight(WalletRepository walletRepository,
                             TransferRepository transferRepository) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
    }

    /**
     * Validates the wallets and looks up the idempotency key in a single transaction.
     *
     * @return the transfer already recorded under this key, if there is one
     * @throws NotFoundException  if either wallet does not exist
     * @throws ForbiddenException if the caller does not own the source wallet
     */
    @Transactional(readOnly = true)
    public Optional<Transfer> inspect(Long callerUserId,
                                      Long fromWalletId,
                                      Long toWalletId,
                                      String idempotencyKey) {

        List<Wallet> found = walletRepository.findAllById(Arrays.asList(fromWalletId, toWalletId));

        Wallet from = pick(found, fromWalletId);
        if (from == null) {
            throw new NotFoundException("Wallet " + fromWalletId + " does not exist");
        }
        if (pick(found, toWalletId) == null) {
            throw new NotFoundException("Wallet " + toWalletId + " does not exist");
        }
        if (!from.getUserId().equals(callerUserId)) {
            throw new ForbiddenException("Caller does not own wallet " + fromWalletId);
        }

        return transferRepository.findByCreatedByUserAndIdempotencyKey(callerUserId, idempotencyKey);
    }

    private static Wallet pick(List<Wallet> wallets, Long id) {
        for (Wallet wallet : wallets) {
            if (id.equals(wallet.getId())) {
                return wallet;
            }
        }
        return null;
    }
}
