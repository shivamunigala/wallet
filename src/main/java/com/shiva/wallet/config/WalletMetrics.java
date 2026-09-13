package com.shiva.wallet.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters required by the brief, alongside the request rate / latency / error
 * metrics Actuator provides for free.
 *
 * <p>Counters are resolved once at construction rather than looked up per call, so the hot
 * path costs an increment and nothing else. All are exposed at /actuator/prometheus.
 */
@Component
public class WalletMetrics {

    private final Counter transfersCreated;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter idempotencyConflicts;
    private final Counter walletsCreated;

    public WalletMetrics(MeterRegistry registry) {
        this.transfersCreated = Counter.builder("wallet.transfers.created")
                .description("Transfers that moved money")
                .register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("wallet.transfers.declined")
                .description("Transfers declined without moving money")
                .tag("reason", "insufficient_funds")
                .register(registry);
        this.idempotentReplays = Counter.builder("wallet.idempotent.replays")
                .description("Repeat requests served from the original transfer record")
                .register(registry);
        this.idempotencyConflicts = Counter.builder("wallet.idempotency.conflicts")
                .description("Reused idempotency keys carrying a different request body")
                .register(registry);
        this.walletsCreated = Counter.builder("wallet.wallets.created")
                .description("Wallets actually created, excluding get-or-create hits")
                .register(registry);
    }

    public void transferCreated() {
        transfersCreated.increment();
    }

    public void transferDeclined() {
        transfersDeclinedInsufficientFunds.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void idempotencyConflict() {
        idempotencyConflicts.increment();
    }

    public void walletCreated() {
        walletsCreated.increment();
    }
}
