package com.shiva.wallet.web.filter;

import com.shiva.wallet.domain.User;
import com.shiva.wallet.repository.UserRepository;
import com.shiva.wallet.web.CallerContext;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

/**
 * Resolves {@code Authorization: Bearer <token>} to a {@link User} and publishes it on
 * {@link CallerContext}.
 *
 * <p>Requests without a usable token are not rejected here — they are simply left
 * unauthenticated, and the controllers raise 401 when they need a caller. That keeps
 * {@code /actuator/**} reachable without a token for health checks and Prometheus scrapes
 * without maintaining a path allowlist in two places.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BearerTokenAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final UserRepository userRepository;

    public BearerTokenAuthFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            resolveCaller(request.getHeader("Authorization")).ifPresent(user -> {
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
        return userRepository.findByBearerToken(token);
    }
}
