package com.fruity.documind.repository;

import com.fruity.documind.entity.Document;
import com.fruity.documind.enums.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /** A user's own uploaded documents (document-management / list views). */
    List<Document> findByUploadedById(UUID userId);

    /** Drive the ingestion pipeline / admin views by processing state. */
    List<Document> findByStatus(DocumentStatus status);
}
