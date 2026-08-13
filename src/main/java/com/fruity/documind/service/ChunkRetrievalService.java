package com.fruity.documind.service;

import com.fruity.documind.entity.DocumentChunk;
import com.fruity.documind.entity.User;
import com.fruity.documind.gateway.GatewayClient;
import com.fruity.documind.repository.DocumentChunkRepository;
import com.fruity.documind.repository.DocumentPermissionRepository;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read path (README §5.3, step 4) — the RBAC-critical retrieval.
 *
 * <p>The vector store's {@code documentId} metadata is used ONLY as a coarse
 * pre-filter to speed up similarity search. It is <b>never</b> the authorization
 * decision. After the search returns candidates we re-fetch the real
 * {@link DocumentChunk} rows and re-verify each one's parent document against a
 * <em>fresh</em> {@code DocumentPermission} query. That second check is the actual
 * gate: a permission revoked after the pre-filter was computed still blocks the
 * chunk here.
 *
 * <p><b>Phase 4:</b> the similarity search itself moves to the Python gateway
 * ({@code POST /retrieve}) when {@code documind.gateway.enabled=true}; otherwise the
 * local Spring AI {@link VectorStore} does it in-process. Either way the re-verification
 * gate below is unchanged — the gateway is trusted to <em>filter</em>, never to <em>authorize</em>.
 */
@Service
public class ChunkRetrievalService {

    private final VectorStore vectorStore;
    private final GatewayClient gatewayClient;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentPermissionRepository permissionRepository;
    private final boolean gatewayEnabled;

    public ChunkRetrievalService(VectorStore vectorStore,
                                 GatewayClient gatewayClient,
                                 DocumentChunkRepository chunkRepository,
                                 DocumentPermissionRepository permissionRepository,
                                 @Value("${documind.gateway.enabled:false}") boolean gatewayEnabled) {
        this.vectorStore = vectorStore;
        this.gatewayClient = gatewayClient;
        this.chunkRepository = chunkRepository;
        this.permissionRepository = permissionRepository;
        this.gatewayEnabled = gatewayEnabled;
    }

    /** A chunk that passed the authorization gate, with its similarity score. */
    public record RetrievedChunk(DocumentChunk chunk, Double score) {}

    /**
     * Retrieve up to {@code topK} chunks relevant to {@code query} that {@code user}
     * is authorized to see, in descending similarity order.
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieve(String query, User user, int topK) {
        // --- Step 2: compute the allowed document ids (pre-filter). ---
        List<UUID> allowedDocIds =
                permissionRepository.findAccessibleDocumentIds(user.getId(), user.getRole());
        if (allowedDocIds.isEmpty()) {
            return List.of(); // user can see nothing; skip the vector search entirely.
        }

        // --- Step 3: similarity search (Python gateway or local VectorStore), pre-filtered
        //     by the allowed document ids. Returns chunkId -> score in similarity order. ---
        Map<UUID, Double> scoreByChunkId = gatewayEnabled
                ? candidatesViaGateway(query, allowedDocIds, topK)
                : candidatesViaVectorStore(query, allowedDocIds, topK);
        if (scoreByChunkId.isEmpty()) {
            return List.of();
        }

        // --- Step 4b: re-fetch the authoritative chunk rows (with parent document). ---
        List<DocumentChunk> chunks =
                chunkRepository.findAllByIdInWithDocument(new ArrayList<>(scoreByChunkId.keySet()));
        Map<UUID, DocumentChunk> chunkById = chunks.stream()
                .collect(Collectors.toMap(DocumentChunk::getId, c -> c));

        // --- Step 4c: RE-VERIFY against a fresh permission query (authoritative gate). ---
        Set<UUID> allowedNow = new HashSet<>(
                permissionRepository.findAccessibleDocumentIds(user.getId(), user.getRole()));

        // --- Step 5: assemble in original similarity order, dropping anything unsafe. ---
        List<RetrievedChunk> result = new ArrayList<>(scoreByChunkId.size());
        for (Map.Entry<UUID, Double> entry : scoreByChunkId.entrySet()) {
            DocumentChunk chunk = chunkById.get(entry.getKey());
            if (chunk == null) {
                continue; // vector row references a chunk that no longer exists (orphan).
            }
            if (!allowedNow.contains(chunk.getDocument().getId())) {
                continue; // permission changed since the pre-filter: block it.
            }
            result.add(new RetrievedChunk(chunk, entry.getValue()));
        }
        return result;
    }

    /** Phase 4: vector search delegated to the Python gateway's {@code /retrieve}. */
    private Map<UUID, Double> candidatesViaGateway(String query, List<UUID> allowedDocIds, int topK) {
        List<String> allowed = allowedDocIds.stream().map(UUID::toString).toList();
        Map<UUID, Double> scoreByChunkId = new LinkedHashMap<>();
        for (GatewayClient.RetrievedRef ref : gatewayClient.retrieve(query, allowed, topK)) {
            if (ref.chunkId() == null) {
                continue;
            }
            try {
                scoreByChunkId.put(UUID.fromString(ref.chunkId()), ref.score());
            } catch (IllegalArgumentException ignored) {
                // Malformed chunk id from the gateway: ignore rather than trust it.
            }
        }
        return scoreByChunkId;
    }

    /** Pre-Phase-4 local path: Spring AI {@link VectorStore} does the search in-process. */
    private Map<UUID, Double> candidatesViaVectorStore(String query, List<UUID> allowedDocIds, int topK) {
        String inList = allowedDocIds.stream()
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(", ", "documentId in [", "]"));

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(inList)
                .build();

        List<org.springframework.ai.document.Document> hits = vectorStore.similaritySearch(request);
        Map<UUID, Double> scoreByChunkId = new LinkedHashMap<>();
        if (hits == null) {
            return scoreByChunkId;
        }
        for (org.springframework.ai.document.Document hit : hits) {
            Object raw = hit.getMetadata().get(ChunkIngestionService.META_CHUNK_ID);
            if (raw == null) {
                continue; // vector rows written outside our ingestion path have no chunkId.
            }
            try {
                scoreByChunkId.put(UUID.fromString(raw.toString()), hit.getScore());
            } catch (IllegalArgumentException ignored) {
                // Malformed metadata: ignore rather than trust it.
            }
        }
        return scoreByChunkId;
    }
}
