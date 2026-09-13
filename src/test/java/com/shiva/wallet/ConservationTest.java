package com.shiva.wallet;

import com.shiva.wallet.repository.LedgerEntryRepository;
import com.shiva.wallet.repository.WalletRepository;
import com.shiva.wallet.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants 1 and 2 — conservation and no overdraft, under contention.
 *
 * <p>Mirrors the live probe: many concurrent transfers among a small set of wallets,
 * deliberately including A&rarr;B and B&rarr;A firing at the same instant, which is the
 * arrangement that deadlocks an implementation that locks in request order rather than a
 * fixed order.
 *
 * <p>Amounts are large relative to the funded balances so that a meaningful share of the
 * transfers are declined. A test where everything succeeds would not exercise the decline
 * path at all, and the decline path is where money is most easily lost.
 *
 * <p>Three assertions, and the ledger one matters most: it proves conservation without
 * reading the balance column, so it would still catch a bug that corrupted balances
 * consistently.
 */
class ConservationTest extends AbstractPostgresTest {

    private static final int TRANSFERS = 60;
    private static final long STARTING_BALANCE = 20_000L;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void concurrentTransfersConserveMoneyAndNeverOverdraw() throws Exception {
        String[] tokens = {"token-alice", "token-bob", "token-carol"};
        long[] wallets = new long[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            wallets[i] = walletIdFor(tokens[i]);
            deposit(wallets[i], tokens[i], STARTING_BALANCE);
        }

        long totalBefore = walletRepository.totalBalance();

        List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            // Alternate direction so that opposing transfers between the same pair of
            // wallets are in flight at the same moment.
            int fromIndex = i % tokens.length;
            int toIndex = (i % 2 == 0)
                    ? (fromIndex + 1) % tokens.length
                    : (fromIndex + tokens.length - 1) % tokens.length;
            long amount = ThreadLocalRandom.current().nextLong(1_000L, 9_000L);
            Map<String, Object> body = new HashMap<>();
            body.put("from", wallets[fromIndex]);
            body.put("to", wallets[toIndex]);
            body.put("amount_paise", amount);
            body.put("idempotency_key", UUID.randomUUID().toString());
            String token = tokens[fromIndex];
            tasks.add(() -> post("/transfers", token, body));
        }

        List<ResponseEntity<Map>> responses = fireAllSimultaneously(tasks);

        // Asserting on status codes matters as much as asserting on balances. An earlier
        // version of this test checked only the money and stayed green while most requests
        // were in fact failing with deadlocks — conservation trivially holds when the
        // transactions roll back. Only 201 (applied) and 422 (declined) are acceptable.
        Set<HttpStatus> statuses = responses.stream()
                .map(ResponseEntity::getStatusCode)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(statuses)
                .as("every transfer must either apply or decline, never error")
                .isSubsetOf(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(walletRepository.totalBalance())
                .as("transfers must not create or destroy money")
                .isEqualTo(totalBefore);

        for (long walletId : wallets) {
            assertThat(walletRepository.currentBalance(walletId).orElseThrow(AssertionError::new))
                    .as("wallet %s must never go negative", walletId)
                    .isGreaterThanOrEqualTo(0L);
        }

        assertThat(ledgerEntryRepository.transferEntriesNetSum())
                .as("every transfer's ledger entries must cancel out")
                .isZero();
    }

    /**
     * Same starting-line trick as the base class, but for heterogeneous tasks rather than
     * N copies of one task.
     */
    private List<ResponseEntity<Map>> fireAllSimultaneously(List<Callable<ResponseEntity<Map>>> tasks)
            throws Exception {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(tasks.size());
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(tasks.size());
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<Map>> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await(30, java.util.concurrent.TimeUnit.SECONDS);
            start.countDown();
            List<ResponseEntity<Map>> responses = new ArrayList<>();
            for (java.util.concurrent.Future<ResponseEntity<Map>> future : futures) {
                responses.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }
}
