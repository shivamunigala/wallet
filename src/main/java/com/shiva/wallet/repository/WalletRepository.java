package com.shiva.wallet.repository;

import com.shiva.wallet.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.util.Optional;

/**
 * Every balance change in the system goes through this interface.
 *
 * <p>The SQL here is written by hand rather than left to Hibernate dirty checking, because
 * the exact shape of these statements <em>is</em> the correctness argument:
 *
 * <ul>
 *   <li>{@link #insertIfAbsent} makes get-or-create race-free without a lock — concurrent
 *       callers collide on the UNIQUE constraint and the loser simply reads the winner's row.
 *   <li>{@link #findByIdForUpdate} takes an explicit row lock. Callers must acquire locks in
 *       <strong>ascending wallet id order</strong>; that ordering is the entire deadlock
 *       story for simultaneous A&rarr;B and B&rarr;A transfers.
 *   <li>{@link #tryDebit} carries the balance predicate in the WHERE clause, so an
 *       overdraft returns 0 rows rather than raising. A 0-row UPDATE is not an error in
 *       Postgres, which is what lets the caller record a decline and still commit.
 * </ul>
 *
 * <p>See docs/CONCURRENCY.md.
 */
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    /**
     * Creates the wallet if this user has none, doing nothing if one already exists.
     * Returns the number of rows inserted (1 on create, 0 when it already existed), which
     * lets the caller tell "created" from "found" without a second query.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO wallets (user_id, balance_paise) VALUES (:userId, 0) "
            + "ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId);

    /**
     * Reads a wallet under a row lock (SELECT ... FOR UPDATE). Must be called inside a
     * transaction, and callers moving money between two wallets must call it for the lower
     * wallet id first.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);

    /**
     * Conditional debit. Returns 1 when the wallet could cover the amount, 0 when it could
     * not — the caller treats 0 as a clean decline, not a failure.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE wallets SET balance_paise = balance_paise - :amountPaise, updated_at = now() "
            + "WHERE id = :walletId AND balance_paise >= :amountPaise", nativeQuery = true)
    int tryDebit(@Param("walletId") Long walletId, @Param("amountPaise") long amountPaise);

    /** Unconditional credit. A credit can never fail an invariant, so it carries no predicate. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE wallets SET balance_paise = balance_paise + :amountPaise, updated_at = now() "
            + "WHERE id = :walletId", nativeQuery = true)
    int credit(@Param("walletId") Long walletId, @Param("amountPaise") long amountPaise);

    /** Current balance, read fresh from the database rather than from the persistence context. */
    @Query(value = "SELECT balance_paise FROM wallets WHERE id = :walletId", nativeQuery = true)
    Optional<Long> currentBalance(@Param("walletId") Long walletId);

    /** System-wide total, used by the conservation assertions. */
    @Query(value = "SELECT COALESCE(SUM(balance_paise), 0) FROM wallets", nativeQuery = true)
    long totalBalance();
}
