package com.dev.semsearch.search.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Generates vector embeddings for user search queries via Spring AI and Ollama.
 *
 * <p><strong>Critical requirement:</strong> The query text MUST be prefixed with
 * {@code "search_query: "} to match the asymmetric training of the {@code nomic-embed-text} model.
 * (Document chunks were embedded with {@code "search_document: "}).
 */
@Service
public class QueryEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(QueryEmbeddingService.class);
    public static final String TASK_PREFIX = "search_query: ";

    private final EmbeddingModel embeddingModel;

    public QueryEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Embeds a search query with the required {@code "search_query: "} task prefix.
     *
     * @param query the raw user query
     * @return 768-dimensional float embedding vector
     */
    public float[] embedQuery(String query) {
        String prefixedQuery = TASK_PREFIX + (query != null ? query.trim() : "");
        log.debug("Embedding search query with prefix: '{}'", prefixedQuery);

        EmbeddingResponse response = embeddingModel.call(
                new EmbeddingRequest(List.of(prefixedQuery), null)
        );

        return response.getResults().getFirst().getOutput();
    }
}
