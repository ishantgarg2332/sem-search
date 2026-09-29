package com.dev.semsearch.ingest.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generates vector embeddings for text chunks using Spring AI's {@link EmbeddingModel}
 * (auto-configured for Ollama with the {@code nomic-embed-text} model).
 *
 * <p>Each chunk is prefixed with {@code "search_document: "} as required by
 * {@code nomic-embed-text} for indexing. At query time, the prefix is
 * {@code "search_query: "} instead.
 */
@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final String INDEX_PREFIX = "search_document: ";

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Embeds a list of text chunks, returning one float array per chunk.
     * Each chunk is prefixed with {@code "search_document: "} before embedding.
     *
     * @param chunks the text chunks to embed
     * @return list of embedding vectors (each 768-dimensional for nomic-embed-text)
     */
    public List<float[]> embed(List<String> chunks) {
        List<String> prefixed = chunks.stream()
                .map(c -> INDEX_PREFIX + c)
                .toList();

        EmbeddingResponse response = embeddingModel.call(
                new org.springframework.ai.embedding.EmbeddingRequest(prefixed, null));

        List<float[]> embeddings = response.getResults().stream()
                .map(r -> r.getOutput())
                .toList();

        log.debug("Embedded {} chunk(s), vector dim={}",
                embeddings.size(),
                embeddings.isEmpty() ? 0 : embeddings.get(0).length);
        return embeddings;
    }
}
