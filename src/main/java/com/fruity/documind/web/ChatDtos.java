package com.fruity.documind.web;

import com.fruity.documind.entity.Citation;
import com.fruity.documind.entity.Message;
import com.fruity.documind.enums.MessageRole;

import java.time.Instant;
import java.util.UUID;

/** Request/response payloads for the chat endpoints. */
public final class ChatDtos {

    private ChatDtos() {}

    /** POST body: a question, optionally continuing an existing conversation. */
    public record ChatRequest(String question, UUID conversationId) {}

    /** The assistant's answer plus the citations it was grounded in. */
    public record ChatResponse(UUID conversationId, UUID messageId, String answer, java.util.List<CitationView> citations) {}

    /** A single citation, safe for API exposure (snippet only, not the full chunk). */
    public record CitationView(UUID documentId, String documentTitle, Integer pageNumber, Double relevanceScore, String snippet) {
        public static CitationView from(Citation c) {
            String content = c.getChunkContent() == null ? "" : c.getChunkContent();
            String snippet = content.length() <= 240 ? content : content.substring(0, 240) + "...";
            return new CitationView(
                    c.getDocument().getId(),
                    c.getDocument().getTitle(),
                    c.getPageNumber(),
                    c.getRelevanceScore(),
                    snippet);
        }
    }

    /** A conversation turn, for replaying history. */
    public record MessageView(UUID id, MessageRole role, String content, Instant createdAt) {
        public static MessageView from(Message m) {
            return new MessageView(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt());
        }
    }
}
