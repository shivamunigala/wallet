package com.shiva.wallet;

import com.shiva.wallet.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant 3 — exactly-once transfer.
 *
 * <p>Three distinct cases, all of which the brief calls out:
 * <ul>
 *   <li>a retry storm of K simultaneous identical requests debits exactly once and returns
 *       identical responses;
 *   <li>a sequential retry after the original committed returns the original result;
 *   <li>the same key with a different body is a 409, not a second debit.
 * </ul>
 *
 * <p>The storm case is the interesting one: the losers block on the unique index while the
 * winner is still in flight, so this exercises the constraint-violation path rather than
 * the fast lookup path.
 */
class IdempotencyTest extends AbstractPostgresTest {

    private static final int STORM_SIZE = 20;
    private static final long AMOUNT = 5_000L;

    @Test
    void concurrentRetriesWithTheSameKeyDebitExactlyOnce() throws Exception {
        long alice = walletIdFor("token-alice");
        long bob = walletIdFor("token-bob");
        deposit(alice, "token-alice", 100_000L);

        long aliceBefore = balanceOf(alice, "token-alice");
        long bobBefore = balanceOf(bob, "token-bob");
        Map<String, Object> body = transferBody(alice, bob, AMOUNT, UUID.randomUUID().toString());

        List<ResponseEntity<Map>> responses =
                fireSimultaneously(STORM_SIZE, () -> post("/transfers", "token-alice", body));

        Set<String> transferIds = responses.stream()
                .map(r -> (String) r.getBody().get("id"))
                .collect(Collectors.toSet());
        assertThat(transferIds)
                .as("every response in the storm must describe the same transfer")
                .hasSize(1);

        Set<HttpStatus> statuses = new HashSet<>(responses.stream()
                .map(ResponseEntity::getStatusCode)
                .collect(Collectors.toSet()));
        assertThat(statuses)
                .as("a retry must not be distinguishable from the original by status code")
                .containsExactly(HttpStatus.CREATED);

        assertThat(balanceOf(alice, "token-alice"))
                .as("exactly one debit")
                .isEqualTo(aliceBefore - AMOUNT);
        assertThat(balanceOf(bob, "token-bob"))
                .as("exactly one credit")
                .isEqualTo(bobBefore + AMOUNT);
    }

    @Test
    void sequentialRetryReturnsTheOriginalResult() {
        long alice = walletIdFor("token-alice");
        long bob = walletIdFor("token-bob");
        deposit(alice, "token-alice", 50_000L);

        Map<String, Object> body = transferBody(alice, bob, AMOUNT, UUID.randomUUID().toString());
        ResponseEntity<Map> first = post("/transfers", "token-alice", body);
        long aliceAfterFirst = balanceOf(alice, "token-alice");

        ResponseEntity<Map> replay = post("/transfers", "token-alice", body);

        assertThat(replay.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(replay.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(balanceOf(alice, "token-alice"))
                .as("a replay must not move money a second time")
                .isEqualTo(aliceAfterFirst);
    }

    @Test
    void sameKeyWithDifferentBodyIsAConflict() {
        long alice = walletIdFor("token-alice");
        long bob = walletIdFor("token-bob");
        deposit(alice, "token-alice", 50_000L);

        String key = UUID.randomUUID().toString();
        post("/transfers", "token-alice", transferBody(alice, bob, AMOUNT, key));
        long balanceAfterOriginal = balanceOf(alice, "token-alice");

        ResponseEntity<Map> conflicting =
                post("/transfers", "token-alice", transferBody(alice, bob, AMOUNT * 2, key));

        assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflicting.getBody().get("code")).isEqualTo("idempotency_conflict");
        assertThat(balanceOf(alice, "token-alice"))
                .as("a conflicting replay must not debit anything")
                .isEqualTo(balanceAfterOriginal);
    }

    private static Map<String, Object> transferBody(long from, long to, long amountPaise, String key) {
        Map<String, Object> body = new HashMap<>();
        body.put("from", from);
        body.put("to", to);
        body.put("amount_paise", amountPaise);
        body.put("idempotency_key", key);
        return body;
    }
}
