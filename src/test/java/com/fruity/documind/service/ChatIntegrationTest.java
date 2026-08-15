package com.fruity.documind.service;

import com.fruity.documind.entity.Citation;
import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.Message;
import com.fruity.documind.entity.User;
import com.fruity.documind.enums.MessageRole;
import com.fruity.documind.enums.Role;
import com.fruity.documind.gateway.GatewayClient;
import com.fruity.documind.gateway.GatewayClient.RagResult;
import com.fruity.documind.repository.CitationRepository;
import com.fruity.documind.repository.DocumentChunkRepository;
import com.fruity.documind.repository.MessageRepository;
import com.fruity.documind.repository.UserRepository;
import com.fruity.documind.service.ChatService.ChatResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 read-path integration test against the real DB. Ingestion embeds through the Python
 * gateway (so real chunks + vectors are written); the {@code /rag/query} call is stubbed via a
 * programmable {@link GatewayClient} so the test controls exactly which chunk ids "Python cited".
 * That lets us prove Java's orchestrate-and-re-verify contract without a nondeterministic LLM:
 * <ul>
 *   <li>a cited chunk the user owns is re-verified and recorded as a citation;</li>
 *   <li>a forged chunk id in the stubbed response is rejected by re-verification (exit criteria).</li>
 * </ul>
 *
 * <p>{@code @Transactional} rolls the JPA writes back — safe because re-verification runs on the
 * same Java connection (it never reaches the gateway's separate connection, since ragQuery is
 * stubbed). Ingestion's vector rows are committed out-of-band by the gateway's {@code /index},
 * so {@code @AfterEach} deletes them (Phase 5.5).
 */
@SpringBootTest
@Transactional
class ChatIntegrationTest {

    private static final String STUB_ANSWER = "Based on the context, the policy is 20 days [1].";

    /** A GatewayClient whose {@code ragQuery} response the test sets at runtime; {@code index()}
     *  is inherited, so ingestion still hits the real gateway and produces real vectors. */
    static class ProgrammableGateway extends GatewayClient {
        volatile RagResult nextRagResult;

        ProgrammableGateway(String baseUrl, String apiKey) {
            super(baseUrl, apiKey);
        }

        @Override
        public RagResult ragQuery(String question, List<String> allowedDocumentIds, int topK) {
            return nextRagResult;
        }
    }

    @TestConfiguration
    static class StubGateway {
        // @Primary ProgrammableGateway overrides only ragQuery; ChunkIngestionService still calls
        // its inherited index() against the real gateway, so real vectors are written.
        @Bean
        @Primary
        ProgrammableGateway stubGatewayClient(
                @Value("${documind.gateway.base-url}") String baseUrl,
                @Value("${documind.gateway.api-key:}") String apiKey) {
            return new ProgrammableGateway(baseUrl, apiKey);
        }
    }

    @Autowired
    private DocumentService documentService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private ProgrammableGateway gateway;
    @Autowired
    private DocumentChunkRepository chunkRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private CitationRepository citationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID indexedDocId; // vector rows the gateway committed out-of-band; cleaned up below

    @AfterEach
    void deleteCommittedVectors() throws Exception {
        if (indexedDocId == null) {
            return;
        }
        // Python committed these vector rows on its own connection, so a delete inside this
        // test's (rolled-back) transaction wouldn't stick. Use a fresh auto-commit connection.
        try (var conn = jdbcTemplate.getDataSource().getConnection();
             var ps = conn.prepareStatement(
                     "delete from vector_store where metadata->>'documentId' = ?")) {
            ps.setString(1, indexedDocId.toString());
            ps.executeUpdate();
        }
    }

    @Test
    void ask_recordsCitations_forVerifiedCitedChunks() throws Exception {
        User user = persistUser();
        Document doc = documentService.upload(
                pdfFile("Company vacation policy grants 20 days per year."), "HR Policy", user);
        indexedDocId = doc.getId();
        assertEquals(com.fruity.documind.enums.DocumentStatus.INDEXED, doc.getStatus());

        // Python "cited" [1] -> the real chunk we just ingested (uploader is auto-granted OWNER).
        UUID chunkId = chunkRepository.findByDocumentId(doc.getId()).get(0).getId();
        gateway.nextRagResult = new RagResult(STUB_ANSWER, List.of(chunkId.toString()), false);

        ChatResult result = chatService.ask("How many vacation days do we get?", null, user);

        assertEquals(STUB_ANSWER, result.answer());
        assertFalse(result.citations().isEmpty(), "expected the re-verified cited chunk to be recorded");

        // Both turns persisted in order.
        List<Message> turns = messageRepository.findByConversationIdOrderByCreatedAtAsc(result.conversationId());
        assertEquals(2, turns.size());
        assertEquals(MessageRole.USER, turns.get(0).getRole());
        assertEquals(MessageRole.ASSISTANT, turns.get(1).getRole());
        assertEquals(STUB_ANSWER, turns.get(1).getContent());

        // Citation persisted against the assistant turn, with a content snapshot and doc ref.
        List<Citation> citations = citationRepository.findByMessageId(result.messageId());
        assertEquals(1, citations.size());
        assertNotNull(citations.get(0).getChunkContent());
        assertEquals("HR Policy", citations.get(0).getDocument().getTitle());
    }

    /**
     * Phase 5 exit criteria: a forged or malformed chunk id in the (untrusted) Python
     * /rag/query response must never become a citation. Java rejects both — a malformed
     * (non-UUID) id is dropped while parsing, a forged (valid UUID, no such chunk) id is
     * dropped by the fresh-permission re-verification — so only the real, authorized chunk
     * survives even though the stubbed gateway "cited" all three.
     */
    @Test
    void ask_reVerification_rejectsForgedAndMalformedChunkIds() throws Exception {
        User user = persistUser();
        Document doc = documentService.upload(
                pdfFile("Company vacation policy grants 20 days per year."), "HR Policy", user);
        indexedDocId = doc.getId();

        UUID realChunkId = chunkRepository.findByDocumentId(doc.getId()).get(0).getId();
        UUID forgedChunkId = UUID.randomUUID();          // valid UUID, but no such chunk exists
        String malformedChunkId = "not-a-real-uuid-42";  // not even a UUID

        // A compromised/buggy Python cites one real chunk plus a forged and a malformed id.
        gateway.nextRagResult = new RagResult(
                "The policy is 20 days [1][2][3].",
                List.of(realChunkId.toString(), forgedChunkId.toString(), malformedChunkId), false);

        ChatResult result = chatService.ask("How many vacation days do we get?", null, user);

        // Both the forged and the malformed id are rejected; only the real, authorized chunk cited.
        assertEquals(1, result.citations().size(),
                "forged and malformed chunk ids from the gateway must be rejected by Java's re-verification");
        assertEquals("HR Policy", result.citations().get(0).getDocument().getTitle());
    }

    private User persistUser() {
        User u = new User();
        u.setEmail("chat-" + UUID.randomUUID() + "@test.local");
        u.setPasswordHash("x");
        u.setName("Chat Tester");
        u.setRole(Role.VIEWER);
        return userRepository.save(u);
    }

    private MockMultipartFile pdfFile(String text) throws Exception {
        return new MockMultipartFile("file", "policy.pdf", "application/pdf", pdf(text));
    }

    private byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
