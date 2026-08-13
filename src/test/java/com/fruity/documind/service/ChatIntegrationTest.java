package com.fruity.documind.service;

import com.fruity.documind.entity.Citation;
import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.Message;
import com.fruity.documind.entity.User;
import com.fruity.documind.enums.MessageRole;
import com.fruity.documind.enums.Role;
import com.fruity.documind.gateway.GatewayClient;
import com.fruity.documind.repository.CitationRepository;
import com.fruity.documind.repository.MessageRepository;
import com.fruity.documind.repository.UserRepository;
import com.fruity.documind.service.ChatService.ChatResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end chat pipeline against the real DB, using REAL local transformers embeddings
 * (no key) and a stubbed chat model (so no Gemini call/key is needed). Proves: upload →
 * auto-granted OWNER permission → real embedding + retrieval of authorized chunks → LLM
 * answer → USER/ASSISTANT turns and citations persisted. {@code @Transactional} rolls back.
 *
 * <p>Gateway is disabled ({@code documind.gateway.enabled=false}) so embeddings use the real
 * local ONNX model (retrieval must return real chunks); only the LLM {@code /generate} call is
 * stubbed via a fake {@link GatewayClient}, so no Groq key or running gateway is needed.
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=sk-test-not-used",
        "documind.gateway.enabled=false"})
@Transactional
class ChatIntegrationTest {

    private static final String STUB_ANSWER = "Based on the context, the policy is 20 days [1].";

    @TestConfiguration
    static class StubGateway {
        @Bean
        @Primary
        GatewayClient stubGatewayClient() {
            return new GatewayClient("http://localhost:0", "") {
                @Override
                public String generate(String system, String userMessage) {
                    return STUB_ANSWER;
                }
            };
        }
    }

    @Autowired
    private DocumentService documentService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private CitationRepository citationRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void ask_retrievesAuthorizedChunks_answersAndCites() throws Exception {
        User user = persistUser();

        // Ingest a document (uploader is auto-granted OWNER access).
        MockMultipartFile file = new MockMultipartFile(
                "file", "policy.pdf", "application/pdf", pdf("Company vacation policy grants 20 days per year."));
        Document doc = documentService.upload(file, "HR Policy", user);
        assertEquals(com.fruity.documind.enums.DocumentStatus.INDEXED, doc.getStatus());

        // Ask a question (same authenticated user, so retrieval is authorized).
        ChatResult result = chatService.ask("How many vacation days do we get?", null, user);

        // Answer came from the (stub) LLM, grounded in retrieved chunks.
        assertEquals(STUB_ANSWER, result.answer());
        assertFalse(result.citations().isEmpty(), "expected at least one citation");

        // Both turns persisted in order.
        List<Message> turns = messageRepository.findByConversationIdOrderByCreatedAtAsc(result.conversationId());
        assertEquals(2, turns.size());
        assertEquals(MessageRole.USER, turns.get(0).getRole());
        assertEquals(MessageRole.ASSISTANT, turns.get(1).getRole());
        assertEquals(STUB_ANSWER, turns.get(1).getContent());

        // Citations persisted against the assistant turn, with a content snapshot.
        List<Citation> citations = citationRepository.findByMessageId(result.messageId());
        assertFalse(citations.isEmpty());
        assertNotNull(citations.get(0).getChunkContent());
        assertEquals("HR Policy", citations.get(0).getDocument().getTitle());
    }

    private User persistUser() {
        User u = new User();
        u.setEmail("chat-" + java.util.UUID.randomUUID() + "@test.local");
        u.setPasswordHash("x");
        u.setName("Chat Tester");
        u.setRole(Role.VIEWER);
        return userRepository.save(u);
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
