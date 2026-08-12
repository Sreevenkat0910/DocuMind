package com.fruity.documind.service;

import com.fruity.documind.entity.Conversation;
import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.DocumentChunk;
import com.fruity.documind.entity.Message;
import com.fruity.documind.entity.User;
import com.fruity.documind.repository.ConversationRepository;
import com.fruity.documind.repository.MessageRepository;
import com.fruity.documind.repository.UserRepository;
import com.fruity.documind.service.ChatService.ChatResult;
import com.fruity.documind.service.ChunkRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies the chat orchestration (persist turns, ground-or-not, cite) with mocked collaborators. */
class ChatServiceTest {

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private UserRepository userRepository;
    private ChunkRetrievalService chunkRetrievalService;
    private CitationService citationService;
    private ChatModel chatModel;
    private ChatService service;

    @BeforeEach
    void setup() {
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        userRepository = mock(UserRepository.class);
        chunkRetrievalService = mock(ChunkRetrievalService.class);
        citationService = mock(CitationService.class);
        chatModel = mock(ChatModel.class);
        service = new ChatService(conversationRepository, messageRepository, userRepository,
                chunkRetrievalService, citationService, chatModel, 5);

        User user = new User();
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user));
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
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("You get 20 days [1].")))));

        ChatResult result = service.ask("How much vacation?", null);

        assertEquals("You get 20 days [1].", result.answer());
        verify(chatModel, times(1)).call(any(Prompt.class));
        verify(messageRepository, times(2)).save(any(Message.class)); // USER + ASSISTANT
        verify(citationService, times(1)).recordCitations(any(), anyList());
    }

    @Test
    void noAuthorizedChunks_skipsLlm_returnsCannedAnswer_noCitations() {
        when(chunkRetrievalService.retrieve(anyString(), any(), anyInt())).thenReturn(List.of());

        ChatResult result = service.ask("Anything secret?", null);

        assertTrue(result.answer().toLowerCase().contains("couldn't find"));
        verify(chatModel, never()).call(any(Prompt.class));
        verify(citationService, never()).recordCitations(any(), anyList());
        verify(messageRepository, times(2)).save(any(Message.class)); // USER + canned ASSISTANT
    }

    @Test
    void blankQuestion_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.ask("   ", null));
        verifyNoInteractions(chatModel);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void unknownConversation_isRejected() {
        UUID missing = UUID.randomUUID();
        when(conversationRepository.findById(missing)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.ask("hi", missing));
    }
}
