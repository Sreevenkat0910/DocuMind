package com.fruity.documind.service;

import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.DocumentChunk;
import com.fruity.documind.gateway.GatewayClient;
import com.fruity.documind.repository.DocumentChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Write path (README §5.3, step 3).
 *
 * For each produced chunk we persist a {@link DocumentChunk} row (the source of truth) and then
 * hand {@code {chunk_id, document_id, content}} to the Python gateway's {@code POST /index}
 * (Plan.md Phase 5.5), which embeds the text and writes the vector-store row. The vector row
 * carries only {chunkId, documentId} metadata, used later purely as a retrieval pre-filter —
 * never as an authorization decision. Java owns no embedding/vector code any more.
 *
 * <p><b>Atomicity caveat:</b> the JPA chunk writes run inside this {@code @Transactional} method,
 * but the gateway's vector write commits out-of-band on its own connection. A crash between the
 * JPA commit and the {@code /index} call can leave a chunk with no embedding; the delete/cleanup
 * path is responsible for reconciling orphans. See README §5.3.
 */
@Service
public class ChunkIngestionService {

    private final DocumentChunkRepository chunkRepository;
    private final GatewayClient gatewayClient;

    public ChunkIngestionService(DocumentChunkRepository chunkRepository, GatewayClient gatewayClient) {
        this.chunkRepository = chunkRepository;
        this.gatewayClient = gatewayClient;
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

        // 2. Hand the chunks to the gateway to embed + write the vector rows, tagging each with
        //    its chunkId so the read path can map a similarity hit back to the authoritative row.
        List<GatewayClient.IndexChunk> toIndex = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            toIndex.add(new GatewayClient.IndexChunk(
                    chunk.getId().toString(), document.getId().toString(), chunk.getContent()));
        }
        gatewayClient.index(toIndex);

        return chunks;
    }
}
