package com.fruity.documind.gateway;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plan.md Phase 10: verifies the resilience wiring itself against a real (local, always-failing)
 * HTTP server — that retry actually retries and the circuit breaker actually short-circuits —
 * rather than just trusting the composition in {@link GatewayClient#call} is correct.
 */
class GatewayClientTest {

    private HttpServer server;
    private AtomicInteger requestCount;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A GatewayClient pointed at a local server that always returns 500. */
    private GatewayClient clientAlwaysFailing(int maxRetryAttempts, int slidingWindowSize) throws Exception {
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chunk", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        Retry retry = Retry.of("test", RetryConfig.custom()
                .maxAttempts(maxRetryAttempts)
                .waitDuration(Duration.ofMillis(10))
                .build());
        CircuitBreaker breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(slidingWindowSize)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        return new GatewayClient(baseUrl, "test-key", retry, breaker);
    }

    @Test
    void retriesTransientFailures_thenGivesUpAsGatewayUnavailable() throws Exception {
        GatewayClient client = clientAlwaysFailing(3, 10);

        assertThrows(GatewayClient.GatewayUnavailableException.class,
                () -> client.chunk(List.of(new GatewayClient.PageInput(1, "text"))));

        assertEquals(3, requestCount.get(), "should have attempted exactly maxAttempts times");
    }

    @Test
    void circuitOpensAfterFailures_thenShortCircuitsWithoutHittingNetwork() throws Exception {
        // 1 attempt per call (isolate circuit-breaker behavior from retry behavior); a 2-call
        // sliding window so the breaker opens once both calls in it have failed.
        GatewayClient client = clientAlwaysFailing(1, 2);
        List<GatewayClient.PageInput> pages = List.of(new GatewayClient.PageInput(1, "text"));

        assertThrows(GatewayClient.GatewayUnavailableException.class, () -> client.chunk(pages));
        assertThrows(GatewayClient.GatewayUnavailableException.class, () -> client.chunk(pages));
        assertEquals(2, requestCount.get(), "breaker should still be closed for these first two calls");

        assertThrows(GatewayClient.GatewayUnavailableException.class, () -> client.chunk(pages));
        assertEquals(2, requestCount.get(), "circuit should be open now: the 3rd call must not reach the network");
    }
}
