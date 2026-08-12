package com.fruity.documind.service;

import com.fruity.documind.service.ChunkIngestionService.ChunkInput;
import com.fruity.documind.service.PdfParsingService.ParsedDocument;
import com.fruity.documind.service.PdfParsingService.ParsedPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 chunking (README §7). A deliberately <b>naive</b> fixed-size sliding-window
 * splitter: it slices text into windows of N words that overlap by K words, so context
 * straddling a window boundary isn't lost between neighbours.
 *
 * <p>Two intentional simplifications for Phase 1:
 * <ul>
 *   <li><b>Chunks never cross a page boundary.</b> Each page is split independently, which
 *       keeps {@code pageNumber} exact for every chunk (needed for citations). A sentence
 *       split across a page break is the accepted cost.</li>
 *   <li><b>Word-based sizing, estimated tokens.</b> No real tokenizer — {@code tokenCount}
 *       is a rough estimate (~4 chars/token).</li>
 * </ul>
 * Structure-aware chunking (headings, sentences, tables) is explicitly deferred to Phase 3.
 */
@Service
public class ChunkingService {

    private final int chunkSizeWords;
    private final int overlapWords;

    public ChunkingService(
            @Value("${documind.chunking.size-words:200}") int chunkSizeWords,
            @Value("${documind.chunking.overlap-words:40}") int overlapWords) {
        if (chunkSizeWords < 1) {
            throw new IllegalArgumentException("chunkSizeWords must be >= 1");
        }
        if (overlapWords < 0 || overlapWords >= chunkSizeWords) {
            throw new IllegalArgumentException("overlapWords must be in [0, chunkSizeWords)");
        }
        this.chunkSizeWords = chunkSizeWords;
        this.overlapWords = overlapWords;
    }

    /**
     * Split a parsed document into ordered chunks. {@code chunkIndex} is document-global
     * (0,1,2,… across all pages); {@code pageNumber} is the source page of each chunk.
     * Blank pages (e.g. un-OCR'd scans) contribute no chunks.
     */
    public List<ChunkInput> chunk(ParsedDocument document) {
        List<ChunkInput> chunks = new ArrayList<>();
        int step = chunkSizeWords - overlapWords; // guaranteed >= 1 by constructor checks
        int chunkIndex = 0;

        for (ParsedPage page : document.pages()) {
            String text = page.text() == null ? "" : page.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            String[] words = text.split("\\s+");

            for (int start = 0; start < words.length; start += step) {
                int end = Math.min(start + chunkSizeWords, words.length);
                String content = String.join(" ", java.util.Arrays.copyOfRange(words, start, end));
                chunks.add(new ChunkInput(chunkIndex++, content, page.pageNumber(), estimateTokens(content)));
                if (end == words.length) {
                    break; // last window reached the end; stepping again would repeat tail words
                }
            }
        }
        return chunks;
    }

    /** Naive token estimate (~4 characters per token). Real tokenization is a later concern. */
    private static int estimateTokens(String text) {
        return Math.max(1, Math.round(text.length() / 4.0f));
    }
}
