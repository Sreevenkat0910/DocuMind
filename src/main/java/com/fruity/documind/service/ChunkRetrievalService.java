package com.fruity.documind.service;

import com.fruity.documind.entity.DocumentChunk;
import com.fruity.documind.entity.User;
import com.fruity.documind.gateway.GatewayClient;
import com.fruity.documind.repository.DocumentChunkRepository;
import com.fruity.documind.repository.DocumentPermissionRepository;
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
 * <p>Similarity search runs entirely in the Python gateway ({@code POST /retrieve} and
 * {@code /rag/query}); Java holds no vector-store code. The re-verification gate below is
 * unchanged — the gateway is trusted to <em>filter</em>, never to <em>authorize</em>.
 */
@Service
public class ChunkRetrievalService {

    private final GatewayClient gatewayClient;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentPermissionRepository permissionRepository;

    public ChunkRetrievalService(GatewayClient gatewayClient,
                                 DocumentChunkRepository chunkRepository,
                                 DocumentPermissionRepository permissionRepository) {
        this.gatewayClient = gatewayClient;
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

        // --- Step 3: similarity search in the Python gateway, pre-filtered by the allowed
        //     document ids. Returns chunkId -> score in similarity order. ---
        Map<UUID, Double> scoreByChunkId = candidatesViaGateway(query, allowedDocIds, topK);
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

    /**
     * The document ids {@code user} is currently authorized to see. Phase 5 sends this to the
     * Python gateway as the retrieval pre-filter; it is NOT the final authorization decision.
     */
    @Transactional(readOnly = true)
    public List<UUID> accessibleDocumentIds(User user) {
        return permissionRepository.findAccessibleDocumentIds(user.getId(), user.getRole());
    }

    /**
     * Phase 5 re-verification gate: given chunk ids from an untrusted source (the Python
     * {@code /rag/query} response), return only the chunks whose parent document {@code user}
     * is authorized to see <em>right now</em>, in the given order. A forged/unknown id (no such
     * chunk) or an unauthorized one (permission revoked, or never granted) is silently dropped.
     * This is the same guarantee as {@link #retrieve}, applied to ids that arrived over HTTP.
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunk> verify(List<UUID> chunkIds, User user) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, DocumentChunk> chunkById = chunkRepository.findAllByIdInWithDocument(chunkIds).stream()
                .collect(Collectors.toMap(DocumentChunk::getId, c -> c));
        Set<UUID> allowedNow = new HashSet<>(
                permissionRepository.findAccessibleDocumentIds(user.getId(), user.getRole()));

        List<RetrievedChunk> verified = new ArrayList<>(chunkIds.size());
        for (UUID id : chunkIds) {
            DocumentChunk chunk = chunkById.get(id);
            if (chunk == null) {
                continue; // forged or deleted chunk id: never trust it.
            }
            if (!allowedNow.contains(chunk.getDocument().getId())) {
                continue; // not authorized (or revoked since retrieval): block it.
            }
            verified.add(new RetrievedChunk(chunk, null)); // score not carried by used_chunk_ids
        }
        return verified;
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
}
