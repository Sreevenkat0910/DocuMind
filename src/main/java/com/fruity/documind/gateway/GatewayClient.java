package com.fruity.documind.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Thin HTTP client for the Python "Embedding & LLM Gateway" (Plan.md Phase 3).
 *
 * <p>What used to be in-process calls (local ONNX embeddings, Spring AI {@code ChatModel})
 * are now network calls to a separate FastAPI process. Every request carries the shared
 * {@code X-Internal-Api-Key}. The gateway never sees permissions — Java computes
 * {@code allowedDocumentIds} and re-verifies every result, exactly as before.
 */
@Component
public class GatewayClient {

    private final RestClient rest;
    private final String apiKey;

    public GatewayClient(@Value("${documind.gateway.base-url:http://localhost:8000}") String baseUrl,
                         @Value("${documind.gateway.api-key:}") String apiKey) {
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public record Message(String role, String content) {}

    private record GenerateRequest(String system, List<Message> messages) {}

    private record GenerateResponse(String content, String model, Object usage) {}

    /** Single grounded completion: system instruction + one user turn -> answer text. */
    public String generate(String system, String userMessage) {
        GenerateResponse resp = rest.post()
                .uri("/generate")
                .header("X-Internal-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new GenerateRequest(system, List.of(new Message("user", userMessage))))
                .retrieve()
                .body(GenerateResponse.class);
        return resp == null ? null : resp.content();
    }

    private record EmbedBatchRequest(List<String> texts) {}

    private record EmbedBatchResponse(List<float[]> embeddings, String model, int dim) {}

    /** Batch-embed texts (used by chunk ingestion and query embedding). Order matches input. */
    public List<float[]> embedBatch(List<String> texts) {
        EmbedBatchResponse resp = rest.post()
                .uri("/embed/batch")
                .header("X-Internal-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbedBatchRequest(texts))
                .retrieve()
                .body(EmbedBatchResponse.class);
        return resp == null ? List.of() : resp.embeddings();
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
        RetrieveResponse resp = rest.post()
                .uri("/retrieve")
                .header("X-Internal-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RetrieveRequest(query, allowedDocumentIds, topK))
                .retrieve()
                .body(RetrieveResponse.class);
        return resp == null ? List.of() : resp.results();
    }
}
