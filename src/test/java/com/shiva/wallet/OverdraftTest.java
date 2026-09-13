package com.shiva.wallet;

import com.shiva.wallet.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant 2 — no overdraft, and the shape of a decline.
 *
 * <p>A decline has to be more than "the request failed": the transfer must exist, be
 * addressable by id, and replay identically. That is what makes the conditional-UPDATE
 * approach worth its complexity — a 0-row UPDATE leaves the transaction alive, so the
 * decline can be committed rather than rolled back into nothing.
 */
class OverdraftTest extends AbstractPostgresTest {

    @Test
    void debitBeyondTheBalanceIsDeclinedCleanly() {
        long alice = walletIdFor("token-alice");
        long bob = walletIdFor("token-bob");
        deposit(alice, "token-alice", 1_000L);

        long aliceBefore = balanceOf(alice, "token-alice");
        long bobBefore = balanceOf(bob, "token-bob");

        Map<String, Object> body = new HashMap<>();
        body.put("from", alice);
        body.put("to", bob);
        body.put("amount_paise", aliceBefore + 1);
        body.put("idempotency_key", UUID.randomUUID().toString());

        ResponseEntity<Map> declined = post("/transfers", "token-alice", body);

        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(declined.getBody().get("status")).isEqualTo("DECLINED_INSUFFICIENT_FUNDS");
        assertThat(balanceOf(alice, "token-alice"))
                .as("a declined debit must not partially apply")
                .isEqualTo(aliceBefore);
        assertThat(balanceOf(bob, "token-bob")).isEqualTo(bobBefore);

        // The decline is durable and addressable, not a transient error.
        String transferId = (String) declined.getBody().get("id");
        ResponseEntity<Map> fetched = get("/transfers/" + transferId, "token-alice");
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("status")).isEqualTo("DECLINED_INSUFFICIENT_FUNDS");

        // And replaying the same key returns that same decline rather than retrying it.
        ResponseEntity<Map> replay = post("/transfers", "token-alice", body);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(replay.getBody().get("id")).isEqualTo(transferId);
    }

    @Test
    void callerCannotDebitAWalletTheyDoNotOwn() {
        long alice = walletIdFor("token-alice");
        long bob = walletIdFor("token-bob");
        deposit(bob, "token-bob", 10_000L);

        Map<String, Object> body = new HashMap<>();
        body.put("from", bob);
        body.put("to", alice);
        body.put("amount_paise", 1_000L);
        body.put("idempotency_key", UUID.randomUUID().toString());

        ResponseEntity<Map> response = post("/transfers", "token-alice", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void missingTokenIsRejected() {
        ResponseEntity<Map> response = post("/wallets", "not-a-real-token", new HashMap<>());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
