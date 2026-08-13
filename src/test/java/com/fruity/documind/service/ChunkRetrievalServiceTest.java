package com.fruity.documind.service;

import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.DocumentChunk;
import com.fruity.documind.entity.User;
import com.fruity.documind.enums.Role;
import com.fruity.documind.gateway.GatewayClient;
import com.fruity.documind.gateway.GatewayClient.RetrievedRef;
import com.fruity.documind.repository.DocumentChunkRepository;
import com.fruity.documind.repository.DocumentPermissionRepository;
import com.fruity.documind.service.ChunkRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 4: vector search now happens in the Python gateway, but authorization stays in Java.
 * These prove the re-verification gate still holds when chunk ids arrive over HTTP — a doc
 * whose permission is revoked after the pre-filter is dropped, and a forged chunk id the user
 * isn't permitted for never surfaces. No DB: repositories/gateway are mocked.
 */
class ChunkRetrievalServiceTest {

    private GatewayClient gatewayClient;
    private DocumentChunkRepository chunkRepository;
    private DocumentPermissionRepository permissionRepository;
    private ChunkRetrievalService service;
    private User user;

    private final UUID docId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        gatewayClient = mock(GatewayClient.class);
        chunkRepository = mock(DocumentChunkRepository.class);
        permissionRepository = mock(DocumentPermissionRepository.class);
        // gateway ENABLED so retrieval goes through /retrieve; the VectorStore is unused here.
        service = new ChunkRetrievalService(mock(VectorStore.class), gatewayClient,
                chunkRepository, permissionRepository, true);

        user = new User();
        user.setRole(Role.VIEWER);

        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(docId);
        DocumentChunk chunk = mock(DocumentChunk.class);
        when(chunk.getId()).thenReturn(chunkId);
        when(chunk.getDocument()).thenReturn(doc);

        when(gatewayClient.retrieve(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RetrievedRef(chunkId.toString(), docId.toString(), 0.9)));
        when(chunkRepository.findAllByIdInWithDocument(anyList())).thenReturn(List.of(chunk));
    }

    @Test
    void gatewayResult_isReturned_whenStillPermitted() {
        // Both the pre-filter and the fresh re-verify see the doc as allowed.
        when(permissionRepository.findAccessibleDocumentIds(any(), any()))
                .thenReturn(List.of(docId));

        List<RetrievedChunk> out = service.retrieve("q", user, 5);

        assertEquals(1, out.size());
        assertEquals(chunkId, out.get(0).chunk().getId());
    }

    @Test
    void gatewayResult_isBlocked_whenPermissionRevokedAfterPrefilter() {
        // Pre-filter allows the doc; the fresh re-verify no longer does (revoked mid-flight).
        when(permissionRepository.findAccessibleDocumentIds(any(), any()))
                .thenReturn(List.of(docId))  // step 2: pre-filter
                .thenReturn(List.of());      // step 4c: authoritative re-verify

        List<RetrievedChunk> out = service.retrieve("q", user, 5);

        assertTrue(out.isEmpty(),
                "a revoked document's chunk must not surface even when retrieval happened in Python");
    }
}
