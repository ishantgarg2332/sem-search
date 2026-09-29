package com.dev.semsearch.search.engine;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.dev.semsearch.common.elasticsearch.ElasticsearchIndexInitializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes keyword (BM25) and dense vector (kNN) queries against the {@code doc-chunks}
 * Elasticsearch alias, enforcing early-binding security by placing the {@code readers}
 * filter directly in both queries.
 */
@Service
public class ElasticsearchSearchService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchSearchService.class);
    private static final String ALIAS = ElasticsearchIndexInitializer.ALIAS_NAME;

    private final ElasticsearchClient client;

    public ElasticsearchSearchService(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * Executes BM25 keyword search matching {@code name^2} and {@code text^1},
     * filtered by user authorities against the {@code readers} field.
     *
     * @param queryText   the search text
     * @param authorities the reader authorities of the user
     * @param size        maximum number of candidate chunks to retrieve
     * @return ranked list of chunk hits
     */
    public List<SearchHit> searchKeyword(String queryText, List<String> authorities, int size) throws IOException {
        if (authorities == null || authorities.isEmpty() || queryText == null || queryText.isBlank()) {
            return List.of();
        }

        List<FieldValue> authorityValues = authorities.stream().map(FieldValue::of).toList();

        SearchResponse<ChunkDocument> response = client.search(s -> s
                .index(ALIAS)
                .query(q -> q.bool(b -> b
                        .must(m -> m.multiMatch(mm -> mm
                                .query(queryText)
                                .fields("name^2", "text^1")
                        ))
                        .filter(f -> f.terms(t -> t
                                .field("readers")
                                .terms(tq -> tq.value(authorityValues))
                        ))
                ))
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .size(size),
                ChunkDocument.class
        );

        return mapHits(response);
    }

    /**
     * Executes kNN dense vector search on the {@code embedding} field,
     * with the {@code readers} filter applied INSIDE the kNN request.
     *
     * @param queryVector the 768-dimensional query vector
     * @param authorities the reader authorities of the user
     * @param size        maximum number of candidate chunks to retrieve
     * @return ranked list of chunk hits
     */
    public List<SearchHit> searchVector(float[] queryVector, List<String> authorities, int size) throws IOException {
        if (authorities == null || authorities.isEmpty() || queryVector == null || queryVector.length == 0) {
            return List.of();
        }

        List<FieldValue> authorityValues = authorities.stream().map(FieldValue::of).toList();
        List<Float> vectorList = new ArrayList<>(queryVector.length);
        for (float v : queryVector) {
            vectorList.add(v);
        }

        int numCandidates = Math.max(size * 2, 50);

        SearchResponse<ChunkDocument> response = client.search(s -> s
                .index(ALIAS)
                .knn(k -> k
                        .field("embedding")
                        .queryVector(vectorList)
                        .k(size)
                        .numCandidates(numCandidates)
                        .filter(f -> f.terms(t -> t
                                .field("readers")
                                .terms(tq -> tq.value(authorityValues))
                        ))
                )
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .size(size),
                ChunkDocument.class
        );

        return mapHits(response);
    }

    private List<SearchHit> mapHits(SearchResponse<ChunkDocument> response) {
        List<SearchHit> results = new ArrayList<>();
        if (response.hits() == null || response.hits().hits() == null) {
            return results;
        }

        for (Hit<ChunkDocument> hit : response.hits().hits()) {
            ChunkDocument doc = hit.source();
            if (doc != null) {
                double score = hit.score() != null ? hit.score() : 0.0;
                results.add(new SearchHit(
                        hit.id(),
                        doc.nodeId,
                        doc.chunkIndex,
                        doc.name,
                        doc.path,
                        doc.text,
                        score
                ));
            }
        }
        return results;
    }

    // ── Internal JSON DTO for Elasticsearch response parsing ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChunkDocument {
        @JsonProperty("node_id")
        String nodeId;

        @JsonProperty("chunk_index")
        int chunkIndex;

        @JsonProperty("name")
        String name;

        @JsonProperty("path")
        String path;

        @JsonProperty("mime_type")
        String mimeType;

        @JsonProperty("text")
        String text;
    }
}
