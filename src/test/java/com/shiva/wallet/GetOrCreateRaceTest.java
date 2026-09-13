package com.shiva.wallet;

import com.shiva.wallet.repository.WalletRepository;
import com.shiva.wallet.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant 4 — race-free get-or-create.
 *
 * <p>Mirrors the live probe: fire N simultaneous POST /wallets for a user who has none and
 * expect exactly one wallet. The mechanism under test is the UNIQUE constraint on
 * {@code wallets.user_id} combined with ON CONFLICT DO NOTHING — no application lock is
 * involved, so this is really a test that the database is doing the arbitration.
 */
class GetOrCreateRaceTest extends AbstractPostgresTest {

    private static final int CONCURRENT_CALLERS = 24;

    @Autowired
    private WalletRepository walletRepository;

    @Test
    void concurrentCreatesYieldExactlyOneWallet() throws Exception {
        long walletsBefore = walletRepository.count();

        List<Long> walletIds = fireSimultaneously(CONCURRENT_CALLERS,
                () -> ((Number) post("/wallets", "token-dave", Collections.emptyMap())
                        .getBody().get("id")).longValue());

        Set<Long> distinct = new HashSet<>(walletIds);
        assertThat(distinct)
                .as("every concurrent caller must see the same wallet id")
                .hasSize(1);
        assertThat(walletRepository.count())
                .as("exactly one wallet row may have been created")
                .isEqualTo(walletsBefore + 1);
    }
}
