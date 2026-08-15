package com.fruity.documind.web;

import com.fruity.documind.security.CurrentUser;
import com.fruity.documind.service.ChatService;
import com.fruity.documind.service.ChatService.ChatResult;
import com.fruity.documind.web.ChatDtos.ChatRequest;
import com.fruity.documind.web.ChatDtos.ChatResponse;
import com.fruity.documind.web.ChatDtos.CitationView;
import com.fruity.documind.web.ChatDtos.MessageView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 1 chat endpoints. No auth yet (Phase 2) — open via the temporary security stub.
 */
@RestController
public class ChatController {

    private final ChatService chatService;
    private final CurrentUser currentUser;

    public ChatController(ChatService chatService, CurrentUser currentUser) {
        this.chatService = chatService;
        this.currentUser = currentUser;
    }

    /** Ask a question (optionally within an existing conversation). */
    @PostMapping("/chat")
    public ChatResponse ask(@RequestBody ChatRequest request) {
        ChatResult result = chatService.ask(request.question(), request.conversationId(), currentUser.require());
        List<CitationView> citations = result.citations().stream().map(CitationView::from).toList();
        return new ChatResponse(result.conversationId(), result.messageId(), result.answer(), citations);
    }

    /**
     * Phase 8: same question, streamed. Runs on a virtual thread (Java 21, stdlib — no thread
     * pool to configure) so this method returns the emitter immediately while tokens relay in
     * the background. Two SSE event types: {@code token} per chunk, and one final
     * {@code metadata} event once generation finishes and citations are re-verified — the
     * pattern production streaming chat APIs use, since citations don't exist until the answer
     * is complete.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStreaming(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; the gateway call bounds duration
        Thread.ofVirtual().start(() -> {
            try {
                ChatResult result = chatService.askStreaming(
                        request.question(), request.conversationId(), currentUser.require(),
                        token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                List<CitationView> citations = result.citations().stream().map(CitationView::from).toList();
                ChatResponse metadata = new ChatResponse(
                        result.conversationId(), result.messageId(), result.answer(), citations);
                emitter.send(SseEmitter.event().name("metadata").data(metadata));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** Replay a conversation's turns in order (only the caller's own conversations). */
    @GetMapping("/conversations/{id}/messages")
    public List<MessageView> history(@PathVariable UUID id) {
        return chatService.history(id, currentUser.require()).stream()
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
