package com.fruity.documind.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
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
        // Pin HTTP/1.1: the JDK HttpClient otherwise tries an h2c (HTTP/2 cleartext) upgrade that
        // the gateway's uvicorn/httptools can't handle, which intermittently drops the request body.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(http))
                .build();
        this.apiKey = apiKey;
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
        IndexResponse resp = rest.post()
                .uri("/index")
                .header("X-Internal-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new IndexRequest(chunks))
                .retrieve()
                .body(IndexResponse.class);
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
        ChunkResponse resp = rest.post()
                .uri("/chunk")
                .header("X-Internal-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChunkRequest(pages))
                .retrieve()
                .body(ChunkResponse.class);
        return resp == null ? List.of() : resp.chunks();
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
        RagQueryResponse resp = rest.post()
                .uri("/rag/query")
                .header("X-Internal-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RagQueryRequest(question, allowedDocumentIds, topK))
                .retrieve()
                .body(RagQueryResponse.class);
        if (resp == null) {
            return new RagResult("", List.of(), true);
        }
        List<String> used = resp.usedChunkIds() == null ? List.of() : resp.usedChunkIds();
        return new RagResult(resp.answer(), used, resp.noContextFound());
    }
}
