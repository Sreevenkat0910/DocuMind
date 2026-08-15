package com.fruity.documind.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Phase 7: gives every incoming request a correlation id, so one request's log lines are
 * greppable end to end — including across the boundary into the Python gateway, which
 * {@link com.fruity.documind.gateway.GatewayClient} propagates via the same header.
 *
 * <p>Reuses an id the caller already set (useful if a request is itself part of a larger
 * chain), otherwise generates one. Stored in SLF4J {@link MDC} for the lifetime of the request
 * so the logging pattern (see {@code application.properties}) picks it up automatically —
 * cleared in a {@code finally} so it never leaks onto a pooled worker thread's next request.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
