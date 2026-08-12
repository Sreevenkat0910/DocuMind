package com.fruity.documind.repository;

import com.fruity.documind.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    /** Turns of a conversation in chronological order (replaying chat history). */
    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
