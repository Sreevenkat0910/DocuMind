package com.fruity.documind.service;

import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.DocumentPermission;
import com.fruity.documind.entity.User;
import com.fruity.documind.enums.AccessLevel;
import com.fruity.documind.enums.DocumentStatus;
import com.fruity.documind.enums.FileType;
import com.fruity.documind.gateway.GatewayClient;
import com.fruity.documind.repository.DocumentPermissionRepository;
import com.fruity.documind.repository.DocumentRepository;
import com.fruity.documind.service.ChunkIngestionService.ChunkInput;
import com.fruity.documind.service.PdfParsingService.ParsedDocument;
import com.fruity.documind.service.PdfParsingService.ParsedPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 1 upload flow (README §7, step 6). Orchestrates: store file → create Document row
 * → parse (PDFBox, or the Python gateway's {@code /parse} for what PDFBox can't handle —
 * Plan.md Phase 9) → chunk (Python gateway, Plan.md Phase 6) → embed+persist
 * (ChunkIngestionService), driving the status lifecycle UPLOADED → PROCESSING → INDEXED (or
 * FAILED on any error).
 *
 * <p>The top-level method is intentionally NOT {@code @Transactional}: each status write is
 * its own commit, so a FAILED status survives even when the processing step rolls back.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final DocumentPermissionRepository documentPermissionRepository;
    private final FileStorageService fileStorageService;
    private final PdfParsingService pdfParsingService;
    private final GatewayClient gatewayClient;
    private final ChunkIngestionService chunkIngestionService;

    public DocumentService(DocumentRepository documentRepository,
                           DocumentPermissionRepository documentPermissionRepository,
                           FileStorageService fileStorageService,
                           PdfParsingService pdfParsingService,
                           GatewayClient gatewayClient,
                           ChunkIngestionService chunkIngestionService) {
        this.documentRepository = documentRepository;
        this.documentPermissionRepository = documentPermissionRepository;
        this.fileStorageService = fileStorageService;
        this.pdfParsingService = pdfParsingService;
        this.gatewayClient = gatewayClient;
        this.chunkIngestionService = chunkIngestionService;
    }

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "docx", "xlsx", "pptx");

    /** Store, register, and fully process an uploaded document for {@code uploader}.
     *  Returns the persisted Document. */
    public Document upload(MultipartFile file, String title, User uploader) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.pdf";
        String ext = extension(originalFilename);
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            throw new UnsupportedOperationException("Unsupported file type (got: " + originalFilename + ")");
        }

        byte[] bytes = readBytes(file);

        // Store first; the path is a required (non-null) Document field.
        String storagePath = fileStorageService.store(bytes, "." + ext);

        Document document = new Document();
        document.setTitle((title != null && !title.isBlank()) ? title.strip() : stripExtension(originalFilename));
        document.setOriginalFilename(originalFilename);
        document.setFileType(FileType.valueOf(ext.toUpperCase()));
        document.setStoragePath(storagePath);
        document.setUploadedBy(uploader);
        document.setStatus(DocumentStatus.UPLOADED);
        document = documentRepository.save(document);

        // Grant the uploader OWNER access so retrieval authorizes them. (README step 11's
        // intent, pulled early because chat retrieval needs at least one DocumentPermission
        // to return anything. Becomes the authenticated principal's grant in Phase 2.)
        grantOwnerPermission(document, uploader);

        return process(document, bytes, ext);
    }

    /** The parse → chunk → embed pipeline, with the PROCESSING/INDEXED/FAILED transitions. */
    private Document process(Document document, byte[] bytes, String ext) {
        try {
            document.setStatus(DocumentStatus.PROCESSING);
            document = documentRepository.save(document);

            List<GatewayClient.PageInput> pages = extractPages(bytes, document.getOriginalFilename(), ext);
            document.setPageCount(pages.size());

            List<ChunkInput> chunks = toChunkInputs(pages);
            if (chunks.isEmpty()) {
                // No extractable text. Still a valid document, just not searchable. Nothing to embed.
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

    /**
     * Plan.md Phase 9: PDFBox first for PDFs (fast, no network hop). Falls back to the
     * gateway's {@code /parse} (Tesseract OCR) when PDFBox finds no extractable text — i.e.
     * a scanned/image-only PDF. Non-PDF formats always go via the gateway, since PDFBox can't
     * read them at all.
     */
    private List<GatewayClient.PageInput> extractPages(byte[] bytes, String filename, String ext) throws IOException {
        if ("pdf".equals(ext)) {
            ParsedDocument parsed = pdfParsingService.parse(bytes);
            if (parsed.pages().stream().anyMatch(p -> !p.text().isBlank())) {
                return pdfPagesToInputs(parsed.pages());
            }
            log.info("Document {} has no extractable text via PDFBox; falling back to OCR via gateway", filename);
        }
        return gatewayPagesToInputs(gatewayClient.parse(bytes, filename));
    }

    private static List<GatewayClient.PageInput> pdfPagesToInputs(List<ParsedPage> pages) {
        List<GatewayClient.PageInput> inputs = new ArrayList<>(pages.size());
        for (ParsedPage page : pages) {
            inputs.add(new GatewayClient.PageInput(page.pageNumber(), page.text()));
        }
        return inputs;
    }

    private static List<GatewayClient.PageInput> gatewayPagesToInputs(List<GatewayClient.ParsedPage> pages) {
        List<GatewayClient.PageInput> inputs = new ArrayList<>(pages.size());
        for (GatewayClient.ParsedPage page : pages) {
            inputs.add(new GatewayClient.PageInput(page.pageNumber(), page.text()));
        }
        return inputs;
    }

    /** Documents {@code user} is authorized to see (own grants + role-based grants). */
    public List<Document> listAccessible(User user) {
        List<UUID> ids = documentPermissionRepository.findAccessibleDocumentIds(user.getId(), user.getRole());
        return ids.isEmpty() ? List.of() : documentRepository.findAllById(ids);
    }

    // --- helpers ---

    /** Ask the gateway to split the parsed pages (Plan.md Phase 6), then wrap each chunk it
     *  returns as a {@link ChunkInput}, assigning the document-global {@code chunkIndex} here. */
    private List<ChunkInput> toChunkInputs(List<GatewayClient.PageInput> pages) {
        List<GatewayClient.ChunkOutput> chunked = gatewayClient.chunk(pages);

        List<ChunkInput> inputs = new ArrayList<>(chunked.size());
        int chunkIndex = 0;
        for (GatewayClient.ChunkOutput c : chunked) {
            inputs.add(new ChunkInput(chunkIndex++, c.content(), c.pageNumber(), estimateTokens(c.content())));
        }
        return inputs;
    }

    /** Naive token estimate (~4 characters per token); real tokenization is a later concern. */
    private static int estimateTokens(String text) {
        return Math.max(1, Math.round(text.length() / 4.0f));
    }

    private void grantOwnerPermission(Document document, User uploader) {
        DocumentPermission permission = new DocumentPermission();
        permission.setDocument(document);
        permission.setUser(uploader);
        permission.setAccessLevel(AccessLevel.OWNER);
        permission.setGrantedBy(uploader);
        documentPermissionRepository.save(permission);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
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
