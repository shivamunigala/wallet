package com.shiva.wallet.repository;

import com.shiva.wallet.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Transfers double as the idempotency store — there is no separate table.
 *
 * <p>Keeping the key on the transfer row is what allows it to be written in the same
 * transaction as the money movement. A separate idempotency table would either need a
 * second transaction (leaving a window where the key exists but the money has not moved)
 * or a distributed transaction to avoid one.
 */
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByPublicId(UUID publicId);

    /** Used to resolve a replay after a unique-constraint collision. */
    Optional<Transfer> findByCreatedByUserAndIdempotencyKey(Long createdByUser, String idempotencyKey);
}
