package com.fruity.documind.service;

import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.DocumentChunk;
import com.fruity.documind.entity.Message;
import com.fruity.documind.entity.User;
import com.fruity.documind.gateway.GatewayClient;
import com.fruity.documind.repository.ConversationRepository;
import com.fruity.documind.repository.MessageRepository;
import com.fruity.documind.service.ChatService.ChatResult;
import com.fruity.documind.service.ChunkRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies the chat orchestration (persist turns, ground-or-not, cite) with mocked collaborators. */
class ChatServiceTest {

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private ChunkRetrievalService chunkRetrievalService;
    private CitationService citationService;
    private GatewayClient gatewayClient;
    private ChatService service;
    private User user;

    @BeforeEach
    void setup() {
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        chunkRetrievalService = mock(ChunkRetrievalService.class);
        citationService = mock(CitationService.class);
        gatewayClient = mock(GatewayClient.class);
        service = new ChatService(conversationRepository, messageRepository,
                chunkRetrievalService, citationService, gatewayClient, 5);

        user = new User();
        when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private RetrievedChunk chunk(String title, int page, String content) {
        Document d = new Document();
        d.setTitle(title);
        DocumentChunk c = new DocumentChunk();
        c.setDocument(d);
        c.setPageNumber(page);
        c.setContent(content);
        return new RetrievedChunk(c, 0.9);
    }

    @Test
    void grounded_callsLlm_persistsBothTurns_andRecordsCitations() {
        when(chunkRetrievalService.retrieve(anyString(), any(), anyInt()))
                .thenReturn(List.of(chunk("Handbook", 3, "Vacation is 20 days.")));
        when(gatewayClient.generate(anyString(), anyString())).thenReturn("You get 20 days [1].");

        ChatResult result = service.ask("How much vacation?", null, user);

        assertEquals("You get 20 days [1].", result.answer());
        verify(gatewayClient, times(1)).generate(anyString(), anyString());
        verify(messageRepository, times(2)).save(any(Message.class)); // USER + ASSISTANT
        verify(citationService, times(1)).recordCitations(any(), anyList());
    }

    @Test
    void noAuthorizedChunks_skipsLlm_returnsCannedAnswer_noCitations() {
        when(chunkRetrievalService.retrieve(anyString(), any(), anyInt())).thenReturn(List.of());

        ChatResult result = service.ask("Anything secret?", null, user);

        assertTrue(result.answer().toLowerCase().contains("couldn't find"));
        verify(gatewayClient, never()).generate(anyString(), anyString());
        verify(citationService, never()).recordCitations(any(), anyList());
        verify(messageRepository, times(2)).save(any(Message.class)); // USER + canned ASSISTANT
    }

    @Test
    void blankQuestion_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.ask("   ", null, user));
        verifyNoInteractions(gatewayClient);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void unknownConversation_isRejected() {
        UUID missing = UUID.randomUUID();
        when(conversationRepository.findById(missing)).thenReturn(java.util.Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.ask("hi", missing, user));
    }
}
