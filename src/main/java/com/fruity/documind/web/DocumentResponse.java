package com.fruity.documind.web;

import com.fruity.documind.entity.Document;
import com.fruity.documind.enums.DocumentStatus;
import com.fruity.documind.enums.FileType;

import java.time.Instant;
import java.util.UUID;

/** API view of a Document — deliberately excludes internals like the storage path. */
public record DocumentResponse(
        UUID id,
        String title,
        String originalFilename,
        FileType fileType,
        DocumentStatus status,
        Integer pageCount,
        Instant createdAt) {

    public static DocumentResponse from(Document d) {
        return new DocumentResponse(
                d.getId(),
                d.getTitle(),
                d.getOriginalFilename(),
                d.getFileType(),
                d.getStatus(),
                d.getPageCount(),
                d.getCreatedAt());
    }
}
