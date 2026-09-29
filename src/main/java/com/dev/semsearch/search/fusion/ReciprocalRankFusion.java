package com.dev.semsearch.search.fusion;

import com.dev.semsearch.search.engine.SearchHit;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom Reciprocal Rank Fusion (RRF) implementation with chunk collapsing per document.
 *
 * <p><strong>Algorithm:</strong>
 * <ol>
 *   <li>Computes RRF score for each unique chunk:
 *       {@code RRF(chunk) = sum( 1 / (k + rank_m) )} where {@code k = 60} and {@code rank} is 1-based.</li>
 *   <li>Collapses chunks by {@code nodeId}: for each document, only the single best-scoring
 *       chunk is kept.</li>
 *   <li>Ranks documents by their best chunk's RRF score descending.</li>
 * </ol>
 */
@Component
public class ReciprocalRankFusion {

    public static final int DEFAULT_K = 60;

    /**
     * An intermediate scored chunk holding accumulated RRF score and document metadata.
     */
    public record ScoredChunk(
            SearchHit hit,
            double rrfScore
    ) {}

    /**
     * Fused document result after chunk collapsing.
     */
    public record FusedDocument(
            String nodeId,
            String name,
            String path,
            String snippet,
            int bestChunkIndex,
            double score
    ) {}

    /**
     * Merges keyword and vector hits using RRF (k = 60) and collapses to one result per document.
     *
     * @param keywordHits ranked list of hits from BM25 search
     * @param vectorHits  ranked list of hits from kNN vector search
     * @param limit       maximum number of documents to return
     * @return list of collapsed, ranked documents
     */
    public List<FusedDocument> fuseAndCollapse(List<SearchHit> keywordHits, List<SearchHit> vectorHits, int limit) {
        return fuseAndCollapse(keywordHits, vectorHits, DEFAULT_K, limit);
    }

    /**
     * Merges keyword and vector hits using RRF with a custom k and collapses to one result per document.
     *
     * @param keywordHits ranked list of hits from BM25 search
     * @param vectorHits  ranked list of hits from kNN vector search
     * @param k           the RRF constant (default 60)
     * @param limit       maximum number of documents to return
     * @return list of collapsed, ranked documents
     */
    public List<FusedDocument> fuseAndCollapse(List<SearchHit> keywordHits, List<SearchHit> vectorHits, int k, int limit) {
        Map<String, Double> chunkScores = new HashMap<>();
        Map<String, SearchHit> chunkHits = new HashMap<>();

        // Accumulate RRF scores from keyword ranking
        if (keywordHits != null) {
            for (int rank = 0; rank < keywordHits.size(); rank++) {
                SearchHit hit = keywordHits.get(rank);
                double contribution = 1.0 / (k + (rank + 1));
                chunkScores.merge(hit.id(), contribution, Double::sum);
                chunkHits.putIfAbsent(hit.id(), hit);
            }
        }

        // Accumulate RRF scores from vector ranking
        if (vectorHits != null) {
            for (int rank = 0; rank < vectorHits.size(); rank++) {
                SearchHit hit = vectorHits.get(rank);
                double contribution = 1.0 / (k + (rank + 1));
                chunkScores.merge(hit.id(), contribution, Double::sum);
                chunkHits.putIfAbsent(hit.id(), hit);
            }
        }

        // Group chunks by nodeId and collapse: pick highest scoring chunk per document
        Map<String, ScoredChunk> bestChunkPerDoc = new HashMap<>();
        for (Map.Entry<String, Double> entry : chunkScores.entrySet()) {
            String chunkId = entry.getKey();
            double score = entry.getValue();
            SearchHit hit = chunkHits.get(chunkId);

            bestChunkPerDoc.compute(hit.nodeId(), (nodeId, existing) -> {
                if (existing == null || score > existing.rrfScore()) {
                    return new ScoredChunk(hit, score);
                }
                return existing;
            });
        }

        // Sort collapsed documents descending by RRF score and apply limit
        return bestChunkPerDoc.values().stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::rrfScore).reversed())
                .limit(limit > 0 ? limit : 10)
                .map(sc -> new FusedDocument(
                        sc.hit().nodeId(),
                        sc.hit().name(),
                        sc.hit().path(),
                        extractSnippet(sc.hit().text()),
                        sc.hit().chunkIndex(),
                        sc.rrfScore()
                ))
                .toList();
    }

    /**
     * Extracts a clean, concise snippet from the chunk text (up to 300 chars).
     */
    private static String extractSnippet(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= 300) {
            return cleaned;
        }
        // Try to cut at word boundary
        int lastSpace = cleaned.lastIndexOf(' ', 300);
        if (lastSpace > 200) {
            return cleaned.substring(0, lastSpace) + "...";
        }
        return cleaned.substring(0, 300) + "...";
    }
}
