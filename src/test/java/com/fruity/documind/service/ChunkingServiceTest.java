package com.fruity.documind.service;

import com.fruity.documind.service.ChunkIngestionService.ChunkInput;
import com.fruity.documind.service.PdfParsingService.ParsedDocument;
import com.fruity.documind.service.PdfParsingService.ParsedPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit test (no Spring): validates the sliding-window behaviour deterministically. */
class ChunkingServiceTest {

    private static ParsedDocument doc(ParsedPage... pages) {
        return new ParsedDocument(pages.length, List.of(pages));
    }

    private static String words(int from, int toInclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= toInclusive; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("w").append(i);
        }
        return sb.toString();
    }

    @Test
    void slidingWindowProducesOverlappingChunks() {
        // 12 words, size 5, overlap 2 -> step 3 -> windows start at 0,3,6,9
        ChunkingService service = new ChunkingService(5, 2);
        List<ChunkInput> chunks = service.chunk(doc(new ParsedPage(1, words(0, 11))));

        assertEquals(4, chunks.size());
        assertEquals("w0 w1 w2 w3 w4", chunks.get(0).content());
        assertEquals("w3 w4 w5 w6 w7", chunks.get(1).content());
        assertEquals("w6 w7 w8 w9 w10", chunks.get(2).content());
        assertEquals("w9 w10 w11", chunks.get(3).content()); // final short window, no repeat past end

        // Overlap: last 2 words of a chunk equal first 2 words of the next.
        assertTrue(chunks.get(1).content().startsWith("w3 w4"));
    }

    @Test
    void chunkIndexIsGlobalAndPageNumberIsPerPage() {
        ChunkingService service = new ChunkingService(3, 0); // no overlap, step 3
        // Page 1: 3 words -> 1 chunk. Page 2: 6 words -> 2 chunks.
        List<ChunkInput> chunks = service.chunk(doc(
                new ParsedPage(1, words(0, 2)),
                new ParsedPage(2, words(0, 5))));

        assertEquals(3, chunks.size());
        // chunkIndex is continuous across pages:
        assertEquals(0, chunks.get(0).chunkIndex());
        assertEquals(1, chunks.get(1).chunkIndex());
        assertEquals(2, chunks.get(2).chunkIndex());
        // pageNumber reflects the source page:
        assertEquals(1, chunks.get(0).pageNumber());
        assertEquals(2, chunks.get(1).pageNumber());
        assertEquals(2, chunks.get(2).pageNumber());
    }

    @Test
    void blankPagesProduceNoChunks() {
        ChunkingService service = new ChunkingService(200, 40);
        List<ChunkInput> chunks = service.chunk(doc(
                new ParsedPage(1, "   "),   // whitespace-only (e.g. un-OCR'd scan)
                new ParsedPage(2, ""),      // empty
                new ParsedPage(3, "real content here")));

        assertEquals(1, chunks.size());
        assertEquals(3, chunks.get(0).pageNumber());
        assertEquals(0, chunks.get(0).chunkIndex());
        assertTrue(chunks.get(0).tokenCount() >= 1);
    }

    @Test
    void rejectsInvalidOverlap() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkingService(5, 5)); // overlap == size
        assertThrows(IllegalArgumentException.class, () -> new ChunkingService(5, 6)); // overlap > size
        assertThrows(IllegalArgumentException.class, () -> new ChunkingService(0, 0)); // size < 1
    }
}
