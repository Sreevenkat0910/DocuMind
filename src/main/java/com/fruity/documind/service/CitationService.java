package com.fruity.documind.service;

import com.fruity.documind.entity.Citation;
import com.fruity.documind.entity.DocumentChunk;
import com.fruity.documind.entity.Message;
import com.fruity.documind.repository.CitationRepository;
import com.fruity.documind.service.ChunkRetrievalService.RetrievedChunk;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Step 5 (README §5.3): turn the chunks that grounded an answer into {@link Citation}
 * rows for an assistant {@link Message}.
 *
 * <p>Each citation stores a <b>snapshot</b> of the chunk text (not just an FK), so a
 * historical citation stays accurate even if the document is later re-chunked or
 * re-indexed. We still record the real document reference and page number for
 * navigation back to the source.
 */
@Service
public class CitationService {

    private final CitationRepository citationRepository;

    public CitationService(CitationRepository citationRepository) {
        this.citationRepository = citationRepository;
    }

    @Transactional
    public List<Citation> recordCitations(Message assistantMessage, List<RetrievedChunk> groundingChunks) {
        List<Citation> citations = groundingChunks.stream()
                .map(rc -> toCitation(assistantMessage, rc))
                .toList();
        return citationRepository.saveAll(citations);
    }

    private Citation toCitation(Message message, RetrievedChunk rc) {
        DocumentChunk chunk = rc.chunk();
        Citation citation = new Citation();
        citation.setMessage(message);
        citation.setDocument(chunk.getDocument());
        citation.setChunkContent(chunk.getContent()); // snapshot at citation time
        citation.setPageNumber(chunk.getPageNumber());
        citation.setRelevanceScore(rc.score());
        return citation;
    }
}
