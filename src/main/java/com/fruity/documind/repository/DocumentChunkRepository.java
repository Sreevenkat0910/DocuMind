package com.fruity.documind.repository;

import com.fruity.documind.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    /**
     * Re-fetch chunks by the ids extracted from vector-store metadata (read path, step 4).
     * The parent Document is fetched eagerly here so the RBAC re-check can read
     * chunk.getDocument().getId() without triggering a lazy-load per chunk.
     */
    @org.springframework.data.jpa.repository.Query(
            "select c from DocumentChunk c join fetch c.document where c.id in :ids")
    List<DocumentChunk> findAllByIdInWithDocument(
            @org.springframework.data.repository.query.Param("ids") List<UUID> ids);

    /** Used by the delete/cleanup path to find vector-store rows to invalidate. */
    List<DocumentChunk> findByDocumentId(UUID documentId);
}
