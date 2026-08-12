package com.fruity.documind.repository;

import com.fruity.documind.entity.Citation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CitationRepository extends JpaRepository<Citation, UUID> {

    List<Citation> findByMessageId(UUID messageId);
}
