package com.fruity.documind.repository;

import com.fruity.documind.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** A user's chat threads, most recently active first (conversation sidebar). */
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(UUID userId);
}
