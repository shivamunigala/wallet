package com.shiva.wallet.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiva.wallet.domain.User;
import com.shiva.wallet.repository.UserRepository;
import com.shiva.wallet.web.CallerContext;
import com.shiva.wallet.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves {@code Authorization: Bearer <token>} to a {@link User} and publishes it on
 * {@link CallerContext}.
 *
 * <p>Requests without a usable token are not rejected here — they are simply left
 * unauthenticated, and the controllers raise 401 when they need a caller. That keeps
 * {@code /actuator/**} reachable without a token for health checks and Prometheus scrapes
 * without maintaining a path allowlist in two places.
 *
 * <h2>Why this filter caches</h2>
 *
 * <p>It runs on <b>every</b> request, and it used to reach the database on every request.
 * Outside any transaction, that meant one connection acquisition and one network round trip
 * before the request did any of its own work — a cost paid by reads and writes alike, and
 * invisible until the database moved off the local Docker network.
 *
 * <p>Tokens are safe to cache here because they are immutable: users are seeded by
 * migration and there is no endpoint that creates, retires, or rotates one. If that ever
 * changes, this cache has to go or gain invalidation — it is the assumption to check first.
 *
 * <p>Only <b>successful</b> lookups are cached. That is deliberate: the key is attacker-
 * supplied, so caching misses would let anyone grow the map without bound. Caching only
 * hits bounds it by the number of real users, at the cost of an unknown token still
 * reaching the database.
 *
 * <h2>Why it handles its own database failures</h2>
 *
 * <p>An exception thrown in a servlet filter never reaches {@code @RestControllerAdvice} —
 * that only sees exceptions raised during controller dispatch. So when the connection pool
 * was exhausted, this lookup produced a bare 500 with no correlation id and no JSON body,
 * while the identical failure inside a controller was correctly reported as a retryable
 * 503. Under a 300-request burst that was 24 misleading 500s. The pool failure is caught
 * here and answered in the same shape the rest of the API uses.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BearerTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthFilter.class);
    private static final String PREFIX = "Bearer ";

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, User> cache = new ConcurrentHashMap<>();

    public BearerTokenAuthFilter(UserRepository userRepository, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Optional<User> caller;
            try {
                caller = resolveCaller(request.getHeader("Authorization"));
            } catch (DataAccessException | TransactionException e) {
                writeUnavailable(response, e);
                return;
            }

            caller.ifPresent(user -> {
                CallerContext.set(user);
                MDC.put("user_id", String.valueOf(user.getId()));
            });
            filterChain.doFilter(request, response);
        } finally {
            // Tomcat threads are pooled; a leaked caller would authenticate the next request.
            CallerContext.clear();
            MDC.remove("user_id");
        }
    }

    private Optional<User> resolveCaller(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(PREFIX.length()).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }

        User cached = cache.get(token);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<User> found = userRepository.findByBearerToken(token);
        found.ifPresent(user -> cache.put(token, user));
        return found;
    }

    /**
     * Answers a database-resource failure the way the controllers would: a retryable 503
     * carrying the correlation id, rather than the container's bare 500 error page.
     */
    private void writeUnavailable(HttpServletResponse response, RuntimeException e) throws IOException {
        log.warn("request.auth_unavailable reason={}", e.getClass().getSimpleName());
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "1");
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(
                "transient_conflict",
                "Transient contention, nothing was applied. Retry with the same idempotency key.",
                MDC.get(CorrelationIdFilter.MDC_KEY),
                null));
    }
}
