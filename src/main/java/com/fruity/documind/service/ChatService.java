package com.fruity.documind.service;

import com.fruity.documind.entity.Citation;
import com.fruity.documind.entity.Conversation;
import com.fruity.documind.entity.Message;
import com.fruity.documind.entity.User;
import com.fruity.documind.enums.MessageRole;
import com.fruity.documind.repository.ConversationRepository;
import com.fruity.documind.repository.MessageRepository;
import com.fruity.documind.gateway.GatewayClient;
import com.fruity.documind.service.ChunkRetrievalService.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Chat/query flow (README §7, step 7). Answers a question strictly from the user's authorized
 * documents. <b>Phase 5:</b> the RAG assembly (retrieve → grounded prompt → LLM → cited sources)
 * now lives in the Python gateway ({@code POST /rag/query}); this service is a thin orchestrator:
 * <ol>
 *   <li>persist the USER turn,</li>
 *   <li>compute {@code allowedDocumentIds} and call {@code /rag/query},</li>
 *   <li><b>re-verify</b> every returned {@code used_chunk_id} against a fresh permission query —
 *       the gateway is trusted to filter, never to authorize,</li>
 *   <li>persist the ASSISTANT turn, then record {@link Citation}s for the re-verified chunks.</li>
 * </ol>
 *
 * <p>Not {@code @Transactional}: the gateway call is a network round-trip and must not hold a DB
 * transaction open. Each persistence step commits on its own (acceptable trade-off).
 */
@Service
public class ChatService {

    private static final String NO_CONTEXT_ANSWER =
            "I couldn't find anything in your documents to answer that.";

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ChunkRetrievalService chunkRetrievalService;
    private final CitationService citationService;
    private final GatewayClient gatewayClient;
    private final int topK;

    public ChatService(ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       ChunkRetrievalService chunkRetrievalService,
                       CitationService citationService,
                       GatewayClient gatewayClient,
                       @Value("${documind.retrieval.top-k:5}") int topK) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.chunkRetrievalService = chunkRetrievalService;
        this.citationService = citationService;
        this.gatewayClient = gatewayClient;
        this.topK = topK;
    }

    /** Result of one question: the (possibly new) conversation, the assistant turn, and citations. */
    public record ChatResult(UUID conversationId, UUID messageId, String answer, List<Citation> citations) {}

    /**
     * Ask a question as {@code user}. {@code conversationId} may be null to start a new
     * conversation. Retrieval is scoped to what {@code user} is authorized to see.
     */
    public ChatResult ask(String question, UUID conversationId, User user) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be empty");
        }
        String q = question.strip();
        Conversation conversation = resolveConversation(conversationId, user, q);

        // 1. Persist the user's turn.
        saveMessage(conversation, MessageRole.USER, q);

        // 2. Compute the allowed-document pre-filter, then let Python assemble the answer.
        List<UUID> allowedDocIds = chunkRetrievalService.accessibleDocumentIds(user);
        String answer;
        List<RetrievedChunk> verifiedChunks;
        if (allowedDocIds.isEmpty()) {
            answer = NO_CONTEXT_ANSWER; // user can see nothing; skip the gateway round-trip.
            verifiedChunks = List.of();
        } else {
            GatewayClient.RagResult rag = gatewayClient.ragQuery(
                    q, allowedDocIds.stream().map(UUID::toString).toList(), topK);
            if (rag.noContextFound()) {
                answer = NO_CONTEXT_ANSWER; // no chunks grounded the answer; Python skipped the LLM.
                verifiedChunks = List.of();
            } else {
                answer = rag.answer();
                // 3. RE-VERIFY every cited chunk id against a fresh permission query. Forged,
                //    unknown or newly-unauthorized ids are dropped here — Python is never trusted
                //    to authorize, only to filter (Plan.md Phase 5 exit criteria).
                verifiedChunks = chunkRetrievalService.verify(parseChunkIds(rag.usedChunkIds()), user);
            }
        }

        // 4. Persist the assistant's turn and record citations for the re-verified chunks.
        Message assistantMessage = saveMessage(conversation, MessageRole.ASSISTANT, answer);
        List<Citation> citations = verifiedChunks.isEmpty()
                ? List.of()
                : citationService.recordCitations(assistantMessage, verifiedChunks);

        // Touch the conversation so it sorts to the top of the sidebar.
        conversationRepository.save(conversation);

        return new ChatResult(conversation.getId(), assistantMessage.getId(), answer, citations);
    }

    /** Replay a conversation's turns in order — only if it belongs to {@code user}. */
    @Transactional(readOnly = true)
    public List<com.fruity.documind.entity.Message> history(UUID conversationId, User user) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Conversation does not belong to this user");
        }
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    // --- helpers ---

    /** Parse the gateway's used-chunk-id strings into UUIDs, silently dropping any malformed one. */
    private static List<UUID> parseChunkIds(List<String> ids) {
        List<UUID> parsed = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                parsed.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // Malformed id from the gateway: ignore rather than trust it.
            }
        }
        return parsed;
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
}
