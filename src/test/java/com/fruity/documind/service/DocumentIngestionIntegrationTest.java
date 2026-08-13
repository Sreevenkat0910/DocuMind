package com.fruity.documind.service;

import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.DocumentChunk;
import com.fruity.documind.entity.User;
import com.fruity.documind.enums.DocumentStatus;
import com.fruity.documind.enums.Role;
import com.fruity.documind.repository.DocumentChunkRepository;
import com.fruity.documind.repository.UserRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-pipeline integration test against the real pgvector database, using the REAL local
 * transformers embedding model (no API key, no network after the model is cached). Proves
 * storage → parse → chunk → JPA persistence → real embedding → vector-store insert.
 *
 * <p>{@code @Transactional} rolls all writes back, leaving the dev DB clean. A dummy OpenAI
 * key is set only so the (unused-here) Gemini chat bean can construct at context load.
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=sk-test-not-used",
        "documind.gateway.enabled=false"})
@Transactional
class DocumentIngestionIntegrationTest {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentChunkRepository chunkRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;

    @Test
    void upload_runsFullPipeline_persistingChunksAndVectors() throws Exception {
        User uploader = persistUser();
        MockMultipartFile file = new MockMultipartFile(
                "file", "integration.pdf", "application/pdf", singlePagePdf());

        Document doc = documentService.upload(file, "Integration Test Doc", uploader);

        // 1. Status lifecycle reached INDEXED.
        assertEquals(DocumentStatus.INDEXED, doc.getStatus());
        assertEquals(1, doc.getPageCount());

        // 2. Relational chunks were persisted (source of truth).
        List<DocumentChunk> chunks = chunkRepository.findByDocumentId(doc.getId());
        assertFalse(chunks.isEmpty(), "expected at least one persisted DocumentChunk");
        assertEquals("Integration Test Doc", chunks.get(0).getDocument().getTitle());

        // 3. Vector-store rows were written and tagged with this documentId in metadata.
        Integer vectorRows = jdbcTemplate.queryForObject(
                "select count(*) from vector_store where metadata->>'documentId' = ?",
                Integer.class, doc.getId().toString());
        assertNotNull(vectorRows);
        assertEquals(chunks.size(), vectorRows,
                "one vector row should exist per persisted chunk");
    }

    private User persistUser() {
        User u = new User();
        u.setEmail("ingest-" + java.util.UUID.randomUUID() + "@test.local");
        u.setPasswordHash("x");
        u.setName("Ingest Tester");
        u.setRole(Role.VIEWER);
        return userRepository.save(u);
    }

    private byte[] singlePagePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Docent integration test content for the retrieval pipeline.");
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
