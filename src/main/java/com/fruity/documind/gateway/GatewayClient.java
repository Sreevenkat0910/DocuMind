package com.fruity.documind.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Thin HTTP client for the Python "Embedding & LLM Gateway" (Plan.md Phase 3).
 *
 * <p>What used to be in-process calls (local ONNX embeddings, Spring AI {@code ChatModel})
 * are now network calls to a separate FastAPI process. Every request carries the shared
 * {@code X-Internal-Api-Key}. The gateway never sees permissions — Java computes
 * {@code allowedDocumentIds} and re-verifies every result, exactly as before.
 *
 * <p><b>Phase 7:</b> every call also carries {@code X-Correlation-Id} (from
 * {@link CorrelationIdFilter} via MDC), so a single request's log lines are greppable across
 * both processes, and logs how long each gateway call took — Java's half of "where did the
 * latency go" (Python's half is its own per-stage logging around retrieve/rerank/LLM).
 *
 * <p><b>Phase 10:</b> every call is wrapped in a shared {@code "gateway"} retry + circuit-breaker
 * pair (config in application.properties), composed programmatically in {@link #call} rather
 * than via {@code @Retry}/{@code @CircuitBreaker} annotations — annotation-driven resilience
 * needs a Spring AOP proxy, and this class is deliberately subclassed by test doubles
 * ({@code ChatIntegrationTest.ProgrammableGateway}) that override one method; proxying would
 * split the proxy and the real target into two objects with two copies of instance state; a
 * plain method call has no such split. Once retries are exhausted or the breaker is open,
 * {@link #call} throws {@link GatewayUnavailableException} instead of letting the raw network
 * exception surface — callers (ingestion, chat) catch that one type for a clean, expected
 * failure rather than an arbitrary {@code RestClientException}. There is deliberately no
 * in-process fallback answer (no cached/local response) — Phase 5.5 already moved all AI/vector
 * logic out of Java, so there is nothing left here to fall back to.
 */
@Component
public class GatewayClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayClient.class);
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final RestClient rest;
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    // Spring can't infer which of this class's several public constructors to use for
    // dependency injection once there's more than one; @Autowired pins it to this one. The
    // others below exist only for test doubles to call directly (never through Spring DI).
    @Autowired
    public GatewayClient(@Value("${documind.gateway.base-url:http://localhost:8000}") String baseUrl,
                         @Value("${documind.gateway.api-key:}") String apiKey,
                         RetryRegistry retryRegistry,
                         CircuitBreakerRegistry circuitBreakerRegistry) {
        // Pin HTTP/1.1: the JDK HttpClient otherwise tries an h2c (HTTP/2 cleartext) upgrade that
        // the gateway's uvicorn/httptools can't handle, which intermittently drops the request body.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(http))
                .build();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpClient = http;
        // Plain, unconfigured ObjectMapper: the streaming call's JSON shape is simple (strings/
        // lists/booleans, no dates/polymorphism), so no need to depend on Spring Boot's
        // autoconfigured bean here.
        this.objectMapper = new ObjectMapper();
        this.retry = retryRegistry.retry("gateway");
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("gateway");
    }

    /** Convenience constructor for test doubles (a different package, e.g.
     *  {@code ChatIntegrationTest.ProgrammableGateway}) that don't care about the specific
     *  resilience config — default Retry/CircuitBreaker are permissive enough not to interfere.
     *  Keeps existing {@code super(baseUrl, apiKey)} test subclasses compiling. */
    public GatewayClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, Retry.ofDefaults("gateway"), CircuitBreaker.ofDefaults("gateway"));
    }

    /** Constructor test doubles use when they need a specific resilience config; production
     *  code goes through the registry-based constructor above so config stays centralized in
     *  application.properties. */
    public GatewayClient(String baseUrl, String apiKey, Retry retry, CircuitBreaker circuitBreaker) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(http))
                .build();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpClient = http;
        this.objectMapper = new ObjectMapper();
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
    }

    /** POST {@code uri} with {@code body}, carrying the internal key + correlation id, retried
     *  and circuit-broken as one unit, and logging the call's duration under that same
     *  correlation id (via MDC). */
    private <T> T post(String uri, Object body, Class<T> responseType) {
        return call(uri, () -> {
            long start = System.currentTimeMillis();
            try {
                return rest.post()
                        .uri(uri)
                        .header("X-Internal-Api-Key", apiKey)
                        .header(CORRELATION_HEADER, MDC.get("correlationId"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(responseType);
            } finally {
                log.info("gateway_call endpoint={} durationMs={}", uri, System.currentTimeMillis() - start);
            }
        });
    }

    /** Runs {@code action} through the shared retry + circuit-breaker pair; once both give up,
     *  wraps whatever they threw as {@link GatewayUnavailableException} so every caller has one
     *  exception type to handle instead of {@code RestClientException} vs. raw I/O errors. */
    private <T> T call(String endpoint, Supplier<T> action) {
        return callThrough(endpoint, CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, action)));
    }

    /** Same as {@link #call}, but circuit-breaker only — no retry. Used by the streaming call,
     *  where a mid-stream failure may follow tokens already relayed to the caller, so blindly
     *  retrying risks emitting the answer twice; the breaker still fails fast when known-down. */
    private <T> T callBreakerOnly(String endpoint, Supplier<T> action) {
        return callThrough(endpoint, CircuitBreaker.decorateSupplier(circuitBreaker, action));
    }

    private <T> T callThrough(String endpoint, Supplier<T> decorated) {
        try {
            return decorated.get();
        } catch (Exception ex) {
            throw new GatewayUnavailableException(endpoint, ex);
        }
    }

    /** One chunk to embed + store in the vector index. */
    public record IndexChunk(@JsonProperty("chunk_id") String chunkId,
                             @JsonProperty("document_id") String documentId,
                             String content) {}

    private record IndexRequest(List<IndexChunk> chunks) {}

    private record IndexResponse(int indexed) {}

    /**
     * Phase 5.5: hand the chunks to Python to embed and write into the vector store. Java has
     * already persisted the authoritative {@code DocumentChunk} rows (its system of record); this
     * only populates the vector index. Returns how many rows the gateway wrote.
     */
    public int index(List<IndexChunk> chunks) {
        IndexResponse resp = post("/index", new IndexRequest(chunks), IndexResponse.class);
        return resp == null ? 0 : resp.indexed();
    }

    /** One page of extracted text to split into chunks. */
    public record PageInput(@JsonProperty("page_number") int pageNumber, String text) {}

    /** One structure-aware chunk Python produced from a page. Java assigns the chunk id (JPA). */
    public record ChunkOutput(String content, @JsonProperty("page_number") int pageNumber) {}

    private record ChunkRequest(List<PageInput> pages) {}

    private record ChunkResponse(List<ChunkOutput> chunks) {}

    /**
     * Phase 6: structure-aware splitting lives in Python now (LangChain's
     * {@code RecursiveCharacterTextSplitter}, per page so {@code pageNumber} stays exact for
     * citations). Java still assigns chunk ids by persisting the returned chunks via JPA.
     */
    public List<ChunkOutput> chunk(List<PageInput> pages) {
        ChunkResponse resp = post("/chunk", new ChunkRequest(pages), ChunkResponse.class);
        return resp == null ? List.of() : resp.chunks();
    }

    /** One page of text the gateway extracted (OCR or DOCX/XLSX/PPTX parsing). */
    public record ParsedPage(@JsonProperty("page_number") int pageNumber, String text) {}

    private record ParseResponse(@JsonProperty("page_count") int pageCount, List<ParsedPage> pages) {}

    /**
     * Phase 9: OCR (scanned/image-only PDFs) and DOCX/XLSX/PPTX parsing — formats PDFBox
     * can't handle. {@code filename}'s extension tells the gateway which parser to use.
     */
    public List<ParsedPage> parse(byte[] bytes, String filename) {
        ParseResponse resp = call("/parse", () -> {
            long start = System.currentTimeMillis();
            try {
                LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("file", new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
                return rest.post()
                        .uri("/parse")
                        .header("X-Internal-Api-Key", apiKey)
                        .header(CORRELATION_HEADER, MDC.get("correlationId"))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .retrieve()
                        .body(ParseResponse.class);
            } finally {
                log.info("gateway_call endpoint=/parse durationMs={}", System.currentTimeMillis() - start);
            }
        });
        return resp == null ? List.of() : resp.pages();
    }

    private record RetrieveRequest(String query,
                                   @JsonProperty("allowed_document_ids") List<String> allowedDocumentIds,
                                   @JsonProperty("top_k") int topK) {}

    /** One similarity hit from the gateway. Java re-verifies the chunk id before trusting it. */
    public record RetrievedRef(@JsonProperty("chunk_id") String chunkId,
                               @JsonProperty("document_id") String documentId,
                               double score) {}

    private record RetrieveResponse(List<RetrievedRef> results) {}

    /**
     * Phase 4: filtered vector search in Python. {@code allowedDocumentIds} is the pre-filter
     * Java already computed; the returned chunk ids are still re-verified by the caller.
     */
    public List<RetrievedRef> retrieve(String query, List<String> allowedDocumentIds, int topK) {
        RetrieveResponse resp = post("/retrieve",
                new RetrieveRequest(query, allowedDocumentIds, topK), RetrieveResponse.class);
        return resp == null ? List.of() : resp.results();
    }

    private record RagQueryRequest(String question,
                                   @JsonProperty("allowed_document_ids") List<String> allowedDocumentIds,
                                   @JsonProperty("top_k") int topK) {}

    private record RagQueryResponse(String answer,
                                    @JsonProperty("used_chunk_ids") List<String> usedChunkIds,
                                    @JsonProperty("no_context_found") boolean noContextFound) {}

    /**
     * The finished answer plus which chunk ids the model cited. Java RE-VERIFIES every id in
     * {@code usedChunkIds} against a fresh permission query before trusting any of them.
     */
    public record RagResult(String answer, List<String> usedChunkIds, boolean noContextFound) {}

    /**
     * Phase 5: full RAG assembly in Python. Java sends the question + its already-computed
     * {@code allowedDocumentIds}; Python retrieves, builds the grounded prompt, calls the LLM
     * and returns the answer with the chunk ids it cited. Never an authorization decision.
     */
    public RagResult ragQuery(String question, List<String> allowedDocumentIds, int topK) {
        RagQueryResponse resp = post("/rag/query",
                new RagQueryRequest(question, allowedDocumentIds, topK), RagQueryResponse.class);
        if (resp == null) {
            return new RagResult("", List.of(), true);
        }
        List<String> used = resp.usedChunkIds() == null ? List.of() : resp.usedChunkIds();
        return new RagResult(resp.answer(), used, resp.noContextFound());
    }

    /** What a stream ends with: which chunk ids the model cited (still re-verified by the
     *  caller, same as {@link RagResult}) — the answer text itself is accumulated by the
     *  caller from the {@code onToken} callback, not repeated here. */
    public record StreamOutcome(List<String> usedChunkIds, boolean noContextFound) {}

    /**
     * Phase 8: streamed RAG assembly. Bypasses {@link RestClient} for this one call — its
     * message-converter pipeline buffers the whole response before returning it, defeating
     * streaming. The JDK {@link HttpClient} this class already holds (for the HTTP/1.1 pin)
     * gives true line-by-line reads via {@code BodyHandlers.ofLines()}, so no new dependency
     * (e.g. WebClient/Reactor) is needed for this single streaming call.
     *
     * <p>Parses the gateway's SSE {@code data: {...}} lines: {@code type:"token"} invokes
     * {@code onToken} with the chunk text as it arrives; {@code type:"metadata"} (always the
     * final event) supplies the returned {@link StreamOutcome}.
     *
     * <p><b>Phase 10:</b> circuit-breaker only, no retry (see {@link #callBreakerOnly}) — tokens
     * may already have been relayed to {@code onToken} by the time a mid-stream failure happens,
     * so blindly retrying would risk emitting the answer twice.
     */
    public StreamOutcome streamRagQuery(String question, List<String> allowedDocumentIds, int topK,
                                        Consumer<String> onToken) {
        return callBreakerOnly("/rag/query/stream", () -> doStreamRagQuery(question, allowedDocumentIds, topK, onToken));
    }

    private StreamOutcome doStreamRagQuery(String question, List<String> allowedDocumentIds, int topK,
                                           Consumer<String> onToken) {
        long start = System.currentTimeMillis();
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "question", question, "allowed_document_ids", allowedDocumentIds, "top_k", topK));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rag/query/stream"))
                    .header("X-Internal-Api-Key", apiKey)
                    .header("X-Correlation-Id", String.valueOf(MDC.get("correlationId")))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            class Outcome { List<String> usedChunkIds = List.of(); boolean noContextFound = true; }
            Outcome outcome = new Outcome();
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> {
                    if (!line.startsWith("data: ")) {
                        return;
                    }
                    try {
                        JsonNode node = objectMapper.readTree(line.substring(6));
                        String type = node.path("type").asText();
                        if ("token".equals(type)) {
                            onToken.accept(node.path("content").asText());
                        } else if ("metadata".equals(type)) {
                            outcome.noContextFound = node.path("no_context_found").asBoolean(true);
                            List<String> ids = new ArrayList<>();
                            node.path("used_chunk_ids").forEach(n -> ids.add(n.asText()));
                            outcome.usedChunkIds = ids;
                        }
                    } catch (IOException e) {
                        throw new UncheckedGatewayException("Malformed SSE line from gateway: " + line, e);
                    }
                });
            }
            return new StreamOutcome(outcome.usedChunkIds, outcome.noContextFound);
        } catch (IOException e) {
            throw new UncheckedGatewayException("Gateway streaming call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedGatewayException("Gateway streaming call interrupted", e);
        } finally {
            log.info("gateway_call endpoint=/rag/query/stream durationMs={}", System.currentTimeMillis() - start);
        }
    }

    /** Retries exhausted or the circuit is open: the gateway is down, not just this one call.
     *  Callers (ingestion, chat) catch this specific type — never a raw {@code RestClientException}
     *  — to render the "AI service unavailable" behavior described in Plan.md Phase 10. */
    public static class GatewayUnavailableException extends UncheckedGatewayException {
        public GatewayUnavailableException(String endpoint, Throwable cause) {
            super("Gateway unavailable calling " + endpoint, cause);
        }
    }

    /** Wraps checked I/O failures from the raw streaming call as unchecked, matching how
     *  {@link RestClient}'s calls elsewhere in this class already surface failures unchecked. */
    public static class UncheckedGatewayException extends RuntimeException {
        public UncheckedGatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
