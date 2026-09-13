package com.shiva.wallet.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Base for the invariant tests: a real PostgreSQL container, the app on a real port, and a
 * helper for firing N requests genuinely simultaneously.
 *
 * <p>Postgres rather than H2 on purpose. The correctness of this service rests on
 * {@code SELECT ... FOR UPDATE} row locks, {@code ON CONFLICT DO NOTHING}, and the fact
 * that a 0-row UPDATE does not abort a transaction. H2 does not reproduce those faithfully,
 * so tests against it would pass while proving nothing about production.
 *
 * <p>One container is shared across the whole suite (static, started by Testcontainers on
 * first use and reused) because migrations plus startup cost far more than the tests do.
 * Tests therefore must not assume an empty database — each computes its own before/after
 * deltas instead.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractPostgresTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected TestRestTemplate rest;

    @LocalServerPort
    protected int port;

    /**
     * The JDK's HttpURLConnection refuses to surface a 401 response when the request body
     * was streamed, throwing HttpRetryException instead. Buffering the body lets the
     * unauthorised-caller assertions see the real status code.
     */
    @BeforeEach
    void bufferRequestBodies() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        rest.getRestTemplate().setRequestFactory(factory);
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    protected ResponseEntity<Map> post(String path, String token, Object body) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), Map.class);
    }

    protected ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), Map.class);
    }

    /** Creates (or fetches) the caller's wallet and returns its id. */
    protected long walletIdFor(String token) {
        ResponseEntity<Map> response = post("/wallets", token, Collections.emptyMap());
        return ((Number) response.getBody().get("id")).longValue();
    }

    protected long balanceOf(long walletId, String token) {
        return ((Number) get("/wallets/" + walletId, token).getBody().get("balance_paise")).longValue();
    }

    protected void deposit(long walletId, String token, long amountPaise) {
        post("/wallets/" + walletId + "/deposit", token,
                Collections.singletonMap("amount_paise", amountPaise));
    }

    /**
     * Runs every task at the same instant.
     *
     * <p>A plain {@code invokeAll} would let early tasks finish before later ones are even
     * submitted, which is exactly the interleaving these tests need to rule out. The latch
     * holds every thread at the starting line until all of them are parked on it.
     */
    protected <T> List<T> fireSimultaneously(int count, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await(30, TimeUnit.SECONDS);
            start.countDown();

            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
