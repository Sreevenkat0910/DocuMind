package com.fruity.documind.service;

import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.DocumentPermission;
import com.fruity.documind.entity.User;
import com.fruity.documind.enums.AccessLevel;
import com.fruity.documind.enums.DocumentStatus;
import com.fruity.documind.enums.FileType;
import com.fruity.documind.enums.Role;
import com.fruity.documind.repository.DocumentPermissionRepository;
import com.fruity.documind.repository.DocumentRepository;
import com.fruity.documind.repository.UserRepository;
import com.fruity.documind.service.ChunkIngestionService.ChunkInput;
import com.fruity.documind.service.PdfParsingService.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Phase 1 upload flow (README §7, step 6). Orchestrates: store file → create Document row
 * → parse (PDFBox) → chunk → embed+persist (ChunkIngestionService), driving the status
 * lifecycle UPLOADED → PROCESSING → INDEXED (or FAILED on any error).
 *
 * <p>The top-level method is intentionally NOT {@code @Transactional}: each status write is
 * its own commit, so a FAILED status survives even when the processing step rolls back.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /**
     * TEMPORARY Phase-1 scaffolding: with no auth yet, every upload is attributed to a
     * single dev user (created on first use). Real uploader identity arrives with JWT auth
     * in Phase 2, at which point this is replaced by the authenticated principal.
     */
    private static final String DEV_USER_EMAIL = "dev@documind.local";

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final DocumentPermissionRepository documentPermissionRepository;
    private final FileStorageService fileStorageService;
    private final PdfParsingService pdfParsingService;
    private final ChunkingService chunkingService;
    private final ChunkIngestionService chunkIngestionService;

    public DocumentService(DocumentRepository documentRepository,
                           UserRepository userRepository,
                           DocumentPermissionRepository documentPermissionRepository,
                           FileStorageService fileStorageService,
                           PdfParsingService pdfParsingService,
                           ChunkingService chunkingService,
                           ChunkIngestionService chunkIngestionService) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.documentPermissionRepository = documentPermissionRepository;
        this.fileStorageService = fileStorageService;
        this.pdfParsingService = pdfParsingService;
        this.chunkingService = chunkingService;
        this.chunkIngestionService = chunkIngestionService;
    }

    /** Store, register, and fully process an uploaded PDF. Returns the persisted Document. */
    public Document upload(MultipartFile file, String title) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.pdf";
        if (!isPdf(originalFilename, file.getContentType())) {
            throw new UnsupportedOperationException("Only PDF uploads are supported in Phase 1 (got: " + originalFilename + ")");
        }

        byte[] bytes = readBytes(file);
        User uploader = resolveUploader();

        // Store first; the path is a required (non-null) Document field.
        String storagePath = fileStorageService.store(bytes, ".pdf");

        Document document = new Document();
        document.setTitle((title != null && !title.isBlank()) ? title.strip() : stripExtension(originalFilename));
        document.setOriginalFilename(originalFilename);
        document.setFileType(FileType.PDF);
        document.setStoragePath(storagePath);
        document.setUploadedBy(uploader);
        document.setStatus(DocumentStatus.UPLOADED);
        document = documentRepository.save(document);

        // Grant the uploader OWNER access so retrieval authorizes them. (README step 11's
        // intent, pulled early because chat retrieval needs at least one DocumentPermission
        // to return anything. Becomes the authenticated principal's grant in Phase 2.)
        grantOwnerPermission(document, uploader);

        return process(document, bytes);
    }

    /** The parse → chunk → embed pipeline, with the PROCESSING/INDEXED/FAILED transitions. */
    private Document process(Document document, byte[] bytes) {
        try {
            document.setStatus(DocumentStatus.PROCESSING);
            document = documentRepository.save(document);

            ParsedDocument parsed = pdfParsingService.parse(bytes);
            document.setPageCount(parsed.pageCount());

            List<ChunkInput> chunks = chunkingService.chunk(parsed);
            if (chunks.isEmpty()) {
                // No extractable text (e.g. a scanned/image-only PDF). Still a valid document,
                // just not searchable until OCR (Phase 7). Nothing to embed.
                log.warn("Document {} produced 0 chunks (no extractable text); marking INDEXED with no vectors.",
                        document.getId());
            } else {
                chunkIngestionService.ingestChunks(document, chunks);
            }

            document.setStatus(DocumentStatus.INDEXED);
            return documentRepository.save(document);
        } catch (Exception e) {
            log.error("Processing failed for document {}", document.getId(), e);
            document.setStatus(DocumentStatus.FAILED);
            documentRepository.save(document);
            throw new DocumentProcessingException("Failed to process document " + document.getId(), e);
        }
    }

    public List<Document> listAll() {
        return documentRepository.findAll();
    }

    // --- helpers ---

    private void grantOwnerPermission(Document document, User uploader) {
        DocumentPermission permission = new DocumentPermission();
        permission.setDocument(document);
        permission.setUser(uploader);
        permission.setAccessLevel(AccessLevel.OWNER);
        permission.setGrantedBy(uploader);
        documentPermissionRepository.save(permission);
    }

    private User resolveUploader() {
        return userRepository.findByEmail(DEV_USER_EMAIL).orElseGet(() -> {
            User dev = new User();
            dev.setEmail(DEV_USER_EMAIL);
            dev.setName("Dev User");
            dev.setPasswordHash("N/A-phase1"); // placeholder; real credentials in Phase 2
            dev.setRole(Role.ADMIN);
            return userRepository.save(dev);
        });
    }

    private static boolean isPdf(String filename, String contentType) {
        boolean byName = filename.toLowerCase().endsWith(".pdf");
        boolean byType = contentType != null && contentType.equalsIgnoreCase("application/pdf");
        return byName || byType;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not read uploaded file", e);
        }
    }
}
