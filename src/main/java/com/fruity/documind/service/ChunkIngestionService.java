package com.fruity.documind.service;

import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.DocumentChunk;
import com.fruity.documind.repository.DocumentChunkRepository;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Write path (README §5.3, step 3).
 *
 * For each produced chunk we persist a {@link DocumentChunk} row (the source of
 * truth) and then hand the text to Spring AI's {@link VectorStore} for embedding
 * + similarity-search storage. The vector-store row carries only {chunkId,
 * documentId} metadata, used later purely as a retrieval pre-filter — never as an
 * authorization decision.
 *
 * <p><b>Atomicity caveat:</b> both writes run inside one {@code @Transactional}
 * method, but Spring AI's pgvector store issues its own JDBC writes that may not
 * enlist in the same Spring-managed transaction. If a crash lands between the JPA
 * commit and the vector-store write, we can get an orphaned chunk (row exists, no
 * embedding). Accepted as an eventual-consistency risk for now; the delete/cleanup
 * path is responsible for reconciling orphans. See README §5.3.
 */
@Service
public class ChunkIngestionService {

    static final String META_CHUNK_ID = "chunkId";
    static final String META_DOCUMENT_ID = "documentId";

    private final DocumentChunkRepository chunkRepository;
    private final VectorStore vectorStore;

    public ChunkIngestionService(DocumentChunkRepository chunkRepository, VectorStore vectorStore) {
        this.chunkRepository = chunkRepository;
        this.vectorStore = vectorStore;
    }

    /** One text unit produced by the parsing/chunking step, prior to persistence. */
    public record ChunkInput(int chunkIndex, String content, Integer pageNumber, Integer tokenCount) {}

    @Transactional
    public List<DocumentChunk> ingestChunks(Document document, List<ChunkInput> inputs) {
        // 1. Persist the relational chunks first so each has a generated id.
        List<DocumentChunk> chunks = new ArrayList<>(inputs.size());
        for (ChunkInput in : inputs) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocument(document);
            chunk.setChunkIndex(in.chunkIndex());
            chunk.setContent(in.content());
            chunk.setPageNumber(in.pageNumber());
            chunk.setTokenCount(in.tokenCount());
            chunks.add(chunk);
        }
        chunks = chunkRepository.saveAll(chunks);

        // 2. Store embeddings in the vector store, tagging each with its chunkId so
        //    the read path can map a similarity hit back to the authoritative row.
        List<org.springframework.ai.document.Document> aiDocs = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            Map<String, Object> metadata = Map.of(
                    META_CHUNK_ID, chunk.getId().toString(),
                    META_DOCUMENT_ID, document.getId().toString());
            aiDocs.add(new org.springframework.ai.document.Document(chunk.getContent(), metadata));
        }
        vectorStore.add(aiDocs);

        return chunks;
    }
}
