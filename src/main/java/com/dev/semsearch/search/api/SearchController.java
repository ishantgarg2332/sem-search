package com.dev.semsearch.search.api;

import com.dev.semsearch.common.alfresco.AlfrescoProperties;
import com.dev.semsearch.search.authority.AuthorityService;
import com.dev.semsearch.search.embedding.QueryEmbeddingService;
import com.dev.semsearch.search.engine.ElasticsearchSearchService;
import com.dev.semsearch.search.engine.SearchHit;
import com.dev.semsearch.search.fusion.ReciprocalRankFusion;
import com.dev.semsearch.search.observability.SearchMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * REST controller exposing the hybrid semantic search API.
 * Combines BM25 and kNN search over Alfresco document chunks using Reciprocal Rank Fusion,
 * enforcing early-binding security.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final AuthorityService authorityService;
    private final QueryEmbeddingService embeddingService;
    private final ElasticsearchSearchService searchService;
    private final ReciprocalRankFusion rankFusion;
    private final String alfrescoBaseUrl;

    private final SearchMetrics metrics;

    public SearchController(
            AuthorityService authorityService,
            QueryEmbeddingService embeddingService,
            ElasticsearchSearchService searchService,
            ReciprocalRankFusion rankFusion,
            AlfrescoProperties alfrescoProperties,
            @org.springframework.beans.factory.annotation.Autowired(required = false) SearchMetrics metrics
    ) {
        this.authorityService = authorityService;
        this.embeddingService = embeddingService;
        this.searchService = searchService;
        this.rankFusion = rankFusion;
        this.metrics = metrics;
        this.alfrescoBaseUrl = (alfrescoProperties != null && alfrescoProperties.getBaseUrl() != null)
                ? alfrescoProperties.getBaseUrl().replaceAll("/+$", "")
                : "http://localhost:8080/alfresco";
    }

    /**
     * Executes an authenticated hybrid search query.
     *
     * @param query          the search query string
     * @param limit          maximum number of distinct documents to return (default: 10)
     * @param authentication the authenticated user principal from Spring Security
     * @return list of relevant search results the user is authorized to read
     * @throws IOException on Elasticsearch communication failure
     */
    @GetMapping
    public List<SearchResult> search(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            Authentication authentication
    ) throws IOException {
        // Fail closed: never fall back to a privileged identity.
        if (authentication == null || authentication.getName() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        String username = authentication.getName();

        log.info("Search request from user='{}': query='{}' limit={}", username, query, limit);

        if (query == null || query.isBlank()) {
            return List.of();
        }

        // 1. Resolve security authorities for the current user
        List<String> authorities = authorityService.getAuthoritiesForUser(username);

        // 2. Generate 768-dim query embedding with "search_query: " prefix
        float[] queryVector = (metrics != null)
                ? metrics.recordEmbedding(() -> embeddingService.embedQuery(query))
                : embeddingService.embedQuery(query);

        // 3. Retrieve candidate pools for BM25 and kNN (fetch 3x limit to feed RRF)
        int candidatePoolSize = Math.max(limit * 3, 30);
        List<SearchHit> keywordHits;
        List<SearchHit> vectorHits;
        if (metrics != null) {
            try {
                keywordHits = metrics.recordKeyword(() -> searchService.searchKeyword(query, authorities, candidatePoolSize));
                vectorHits = metrics.recordVector(() -> searchService.searchVector(queryVector, authorities, candidatePoolSize));
            } catch (Exception e) {
                if (e instanceof IOException ioe) throw ioe;
                throw new RuntimeException(e);
            }
        } else {
            keywordHits = searchService.searchKeyword(query, authorities, candidatePoolSize);
            vectorHits = searchService.searchVector(queryVector, authorities, candidatePoolSize);
        }

        log.debug("Found {} keyword hits and {} vector hits for user='{}'",
                keywordHits.size(), vectorHits.size(), username);

        // 4. Merge results using custom Reciprocal Rank Fusion and collapse by document (nodeId)
        List<ReciprocalRankFusion.FusedDocument> fusedDocs = rankFusion.fuseAndCollapse(keywordHits, vectorHits, limit);

        // 5. Build response DTOs with direct Alfresco content links
        return fusedDocs.stream()
                .map(doc -> new SearchResult(
                        doc.nodeId(),
                        doc.name(),
                        doc.path(),
                        doc.snippet(),
                        doc.score(),
                        buildAlfrescoLink(doc.nodeId())
                ))
                .toList();
    }

    private String buildAlfrescoLink(String nodeId) {
        return "/api/documents/" + nodeId + "/content";
    }
}
