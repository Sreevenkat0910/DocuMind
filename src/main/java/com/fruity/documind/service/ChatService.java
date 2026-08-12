package com.fruity.documind.service;

import com.fruity.documind.entity.Citation;
import com.fruity.documind.entity.Conversation;
import com.fruity.documind.entity.Message;
import com.fruity.documind.entity.User;
import com.fruity.documind.enums.MessageRole;
import com.fruity.documind.enums.Role;
import com.fruity.documind.repository.ConversationRepository;
import com.fruity.documind.repository.MessageRepository;
import com.fruity.documind.repository.UserRepository;
import com.fruity.documind.service.ChunkRetrievalService.RetrievedChunk;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 1 chat/query flow (README §7, step 7). Answers a question strictly from the user's
 * authorized documents:
 * <ol>
 *   <li>persist the USER turn,</li>
 *   <li>retrieve authorized chunks via {@link ChunkRetrievalService} (RBAC-enforced),</li>
 *   <li>build a grounded prompt and call the LLM,</li>
 *   <li>persist the ASSISTANT turn, then record {@link Citation}s for the chunks used.</li>
 * </ol>
 *
 * <p>Not {@code @Transactional}: the LLM call is a network round-trip and must not hold a DB
 * transaction open. Each persistence step commits on its own (acceptable Phase-1 trade-off).
 */
@Service
public class ChatService {

    private static final String DEV_USER_EMAIL = "dev@documind.local"; // temporary; see DocumentService

    private static final String SYSTEM_INSTRUCTION = """
            You are Docent, an enterprise knowledge assistant. Answer the user's question using \
            ONLY the information in the provided context. If the answer is not contained in the \
            context, say you don't have enough information to answer — do not use outside knowledge \
            and do not guess. Be concise, and refer to the bracketed source numbers (e.g. [1]) you used.""";

    private static final String NO_CONTEXT_ANSWER =
            "I couldn't find anything in your documents to answer that.";

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ChunkRetrievalService chunkRetrievalService;
    private final CitationService citationService;
    private final ChatModel chatModel;
    private final int topK;

    public ChatService(ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       UserRepository userRepository,
                       ChunkRetrievalService chunkRetrievalService,
                       CitationService citationService,
                       ChatModel chatModel,
                       @Value("${documind.retrieval.top-k:5}") int topK) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.chunkRetrievalService = chunkRetrievalService;
        this.citationService = citationService;
        this.chatModel = chatModel;
        this.topK = topK;
    }

    /** Result of one question: the (possibly new) conversation, the assistant turn, and citations. */
    public record ChatResult(UUID conversationId, UUID messageId, String answer, List<Citation> citations) {}

    /**
     * Ask a question. {@code conversationId} may be null to start a new conversation.
     */
    public ChatResult ask(String question, UUID conversationId) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be empty");
        }
        String q = question.strip();
        User user = resolveUser();
        Conversation conversation = resolveConversation(conversationId, user, q);

        // 1. Persist the user's turn.
        saveMessage(conversation, MessageRole.USER, q);

        // 2. Retrieve authorized chunks (RBAC enforced inside the retrieval service).
        List<RetrievedChunk> chunks = chunkRetrievalService.retrieve(q, user, topK);

        // 3. Build a grounded prompt and call the LLM — unless there's nothing to ground on.
        String answer;
        if (chunks.isEmpty()) {
            answer = NO_CONTEXT_ANSWER; // don't invoke the model with no context; avoids hallucination.
        } else {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(SYSTEM_INSTRUCTION),
                    new UserMessage(buildContextBlock(chunks) + "\n\nQuestion: " + q)));
            answer = chatModel.call(prompt).getResult().getOutput().getText();
        }

        // 4. Persist the assistant's turn and record citations for the grounding chunks.
        Message assistantMessage = saveMessage(conversation, MessageRole.ASSISTANT, answer);
        List<Citation> citations = chunks.isEmpty()
                ? List.of()
                : citationService.recordCitations(assistantMessage, chunks);

        // Touch the conversation so it sorts to the top of the sidebar.
        conversationRepository.save(conversation);

        return new ChatResult(conversation.getId(), assistantMessage.getId(), answer, citations);
    }

    // --- helpers ---

    private String buildContextBlock(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder("Context:\n");
        int n = 1;
        for (RetrievedChunk rc : chunks) {
            String title = rc.chunk().getDocument().getTitle();
            Integer page = rc.chunk().getPageNumber();
            sb.append('[').append(n++).append("] (").append(title);
            if (page != null) {
                sb.append(", p.").append(page);
            }
            sb.append(")\n").append(rc.chunk().getContent()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private Conversation resolveConversation(UUID conversationId, User user, String firstQuestion) {
        if (conversationId == null) {
            Conversation conversation = new Conversation();
            conversation.setUser(user);
            conversation.setTitle(deriveTitle(firstQuestion));
            return conversationRepository.save(conversation);
        }
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Conversation does not belong to this user");
        }
        return conversation;
    }

    private Message saveMessage(Conversation conversation, MessageRole role, String content) {
        Message message = new Message();
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content);
        return messageRepository.save(message);
    }

    private static String deriveTitle(String question) {
        String t = question.strip();
        return t.length() <= 60 ? t : t.substring(0, 57) + "...";
    }

    private User resolveUser() {
        return userRepository.findByEmail(DEV_USER_EMAIL).orElseGet(() -> {
            User dev = new User();
            dev.setEmail(DEV_USER_EMAIL);
            dev.setName("Dev User");
            dev.setPasswordHash("N/A-phase1");
            dev.setRole(Role.ADMIN);
            return userRepository.save(dev);
        });
    }
}
