package com.fruity.documind.web;

import com.fruity.documind.repository.MessageRepository;
import com.fruity.documind.service.ChatService;
import com.fruity.documind.service.ChatService.ChatResult;
import com.fruity.documind.web.ChatDtos.ChatRequest;
import com.fruity.documind.web.ChatDtos.ChatResponse;
import com.fruity.documind.web.ChatDtos.CitationView;
import com.fruity.documind.web.ChatDtos.MessageView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 1 chat endpoints. No auth yet (Phase 2) — open via the temporary security stub.
 */
@RestController
public class ChatController {

    private final ChatService chatService;
    private final MessageRepository messageRepository;

    public ChatController(ChatService chatService, MessageRepository messageRepository) {
        this.chatService = chatService;
        this.messageRepository = messageRepository;
    }

    /** Ask a question (optionally within an existing conversation). */
    @PostMapping("/chat")
    public ChatResponse ask(@RequestBody ChatRequest request) {
        ChatResult result = chatService.ask(request.question(), request.conversationId());
        List<CitationView> citations = result.citations().stream().map(CitationView::from).toList();
        return new ChatResponse(result.conversationId(), result.messageId(), result.answer(), citations);
    }

    /** Replay a conversation's turns in order. */
    @GetMapping("/conversations/{id}/messages")
    public List<MessageView> history(@PathVariable UUID id) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(id).stream()
                .map(MessageView::from).toList();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    /**
     * Upstream LLM provider failures (rate limits/quota, bad requests, timeouts) surface as
     * runtime exceptions from the model call. Return a concise 502 instead of a raw 500 stack
     * trace so the client shows something readable.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleUpstream(RuntimeException e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (msg.length() > 400) {
            msg = msg.substring(0, 400) + "…";
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "AI provider error: " + msg));
    }
}
