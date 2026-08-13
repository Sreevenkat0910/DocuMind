package com.fruity.documind.web;

import com.fruity.documind.entity.Document;
import com.fruity.documind.security.CurrentUser;
import com.fruity.documind.service.DocumentProcessingException;
import com.fruity.documind.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Phase 1 document endpoints. No auth/RBAC yet (that's Phase 2) — these are open via the
 * temporary security stub so the ingest pipeline can be exercised end-to-end.
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final CurrentUser currentUser;

    public DocumentController(DocumentService documentService, CurrentUser currentUser) {
        this.documentService = documentService;
        this.currentUser = currentUser;
    }

    /** Upload a PDF. Restricted to EDITOR/ADMIN — VIEWERs can read but not add documents. */
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<DocumentResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {
        Document document = documentService.upload(file, title, currentUser.require());
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentResponse.from(document));
    }

    /** List documents the caller is authorized to see. */
    @GetMapping
    public List<DocumentResponse> list() {
        return documentService.listAccessible(currentUser.require()).stream().map(DocumentResponse::from).toList();
    }

    // --- local error handling ---

    /** Bad input (empty file) or unsupported type → 4xx with a message. */
    @ExceptionHandler({IllegalArgumentException.class, UnsupportedOperationException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        HttpStatus status = (e instanceof UnsupportedOperationException)
                ? HttpStatus.UNSUPPORTED_MEDIA_TYPE
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }

    /** Pipeline failure (parse/embed) → 500 with a message. */
    @ExceptionHandler(DocumentProcessingException.class)
    public ResponseEntity<Map<String, String>> handleProcessingFailure(DocumentProcessingException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }
}
