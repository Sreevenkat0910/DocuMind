package com.fruity.documind.service;

import com.fruity.documind.entity.DocumentChunk;
import com.fruity.documind.entity.User;
import com.fruity.documind.repository.DocumentChunkRepository;
import com.fruity.documind.repository.DocumentPermissionRepository;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
@Service
public class ChunkRetrievalService {

    private final VectorStore vectorStore;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentPermissionRepository permissionRepository;

    public ChunkRetrievalService(VectorStore vectorStore,
                                 DocumentChunkRepository chunkRepository,
                                 DocumentPermissionRepository permissionRepository) {
        this.vectorStore = vectorStore;
        this.chunkRepository = chunkRepository;
        this.permissionRepository = permissionRepository;
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

        // --- Step 3: similarity search, pre-filtered by allowed document ids. ---
        String inList = allowedDocIds.stream()
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(", ", "documentId in [", "]"));

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(inList)
                .build();

        List<org.springframework.ai.document.Document> hits = vectorStore.similaritySearch(request);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        // --- Step 4a: extract chunkIds in similarity order, preserving scores. ---
        Map<UUID, Double> scoreByChunkId = new LinkedHashMap<>();
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
        if (scoreByChunkId.isEmpty()) {
            return List.of();
        }

        // --- Step 4b: re-fetch the authoritative chunk rows (with parent document). ---
        List<DocumentChunk> chunks =
                chunkRepository.findAllByIdInWithDocument(new ArrayList<>(scoreByChunkId.keySet()));
        Map<UUID, DocumentChunk> chunkById = chunks.stream()
                .collect(Collectors.toMap(DocumentChunk::getId, c -> c));

        // --- Step 4c: RE-VERIFY against a fresh permission query (authoritative gate). ---
        var allowedNow = new java.util.HashSet<>(
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
}
