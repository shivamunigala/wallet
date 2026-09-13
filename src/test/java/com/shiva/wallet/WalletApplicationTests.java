package com.shiva.wallet;

import com.shiva.wallet.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the context starts against a real Postgres, Flyway migrations apply, and the
 * health endpoint is reachable without a token so that container and platform health checks
 * work.
 */
@AutoConfigureMetrics // @SpringBootTest disables metrics export by default; this asserts on it.
class WalletApplicationTests extends AbstractPostgresTest {

    @Test
    void contextLoadsAndHealthIsPublic() {
        assertThat(rest.getForEntity(url("/actuator/health"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void metricsEndpointExposesDomainCounters() {
        String body = rest.getForEntity(url("/actuator/prometheus"), String.class).getBody();
        assertThat(body).contains("wallet_transfers_created_total");
    }
}
