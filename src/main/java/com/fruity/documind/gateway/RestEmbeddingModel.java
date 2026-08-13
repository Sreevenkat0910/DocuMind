package com.fruity.documind.gateway;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI {@link EmbeddingModel} backed by the Python gateway's {@code /embed/batch}
 * (Plan.md Phase 3). Wiring embeddings through this bean rather than editing every call
 * site means Spring AI's {@code VectorStore} keeps working unchanged for BOTH ingestion
 * ({@code add}) and query embedding (retrieval) — one bean covers the whole embedding path.
 *
 * <p>Enabled only when {@code documind.gateway.enabled=true}, and marked {@link Primary} so
 * it wins over the local ONNX embedding bean. With the property absent (e.g. JPA integration
 * tests without a running gateway) the local ONNX model is used instead.
 */
@Component
@Primary
@ConditionalOnProperty(name = "documind.gateway.enabled", havingValue = "true")
public class RestEmbeddingModel implements EmbeddingModel {

    private final GatewayClient gateway;

    public RestEmbeddingModel(GatewayClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<float[]> vectors = gateway.embedBatch(request.getInstructions());
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }
}
