package com.dev.semsearch.ingest.reconciliation;

import com.dev.semsearch.common.alfresco.AlfrescoProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Client for the Alfresco v1 Search API.
 * Uses AFTS (Alfresco Full Text Search) queries to discover content nodes.
 */
@Component
public class AlfrescoSearchClient {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoSearchClient.class);

    private final RestClient restClient;

    public AlfrescoSearchClient(AlfrescoProperties props) {
        String credentials = props.getUsername() + ":" + props.getPassword();
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

        String apiBase = props.getBaseUrl().replaceAll("/+$", "")
                + "/alfresco/api/-default-/public/search/versions/1";

        this.restClient = RestClient.builder()
                .baseUrl(apiBase)
                .defaultHeader("Authorization", basicAuth)
                .build();
    }

    /**
     * Finds all {@code cm:content} node IDs modified on or after {@code modifiedSince}.
     * Automatically pages through the results if more than 100 items match.
     *
     * <p>Throws if any page fails, so the caller never records a run that didn't complete.
     *
     * @param modifiedSince high-water mark timestamp
     * @return list of Alfresco node UUIDs
     */
    public List<String> findNodesModifiedSince(Instant modifiedSince) {
        List<String> nodeIds = new ArrayList<>();
        int skipCount = 0;
        int maxItems = 100;
        boolean hasMore = true;

        String isoDate = DateTimeFormatter.ISO_INSTANT.format(modifiedSince);
        String aftsQuery = "TYPE:\"cm:content\" AND cm:modified:['" + isoDate + "' TO MAX]";

        log.debug("Executing Alfresco AFTS reconciliation search: {}", aftsQuery);

        while (hasMore) {
            Map<String, Object> requestBody = Map.of(
                    "query", Map.of(
                            "language", "afts",
                            "query", aftsQuery
                    ),
                    "paging", Map.of(
                            "maxItems", maxItems,
                            "skipCount", skipCount
                    )
            );

            try {
                SearchResponse response = restClient.post()
                        .uri("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(SearchResponse.class);

                if (response == null || response.list == null || response.list.entries == null) {
                    break;
                }

                for (SearchEntryWrapper wrapper : response.list.entries) {
                    if (wrapper.entry != null && wrapper.entry.id != null) {
                        nodeIds.add(wrapper.entry.id);
                    }
                }

                if (response.list.pagination != null) {
                    hasMore = Boolean.TRUE.equals(response.list.pagination.hasMoreItems);
                    skipCount += maxItems;
                } else {
                    hasMore = false;
                }
            } catch (Exception e) {
                // Fail loudly: returning a partial or empty list here would let the caller
                // advance the high-water mark and silently skip every change in this window.
                throw new IllegalStateException(
                        "Alfresco reconciliation search failed at skipCount=" + skipCount, e);
            }
        }

        log.info("Alfresco search reconciliation returned {} modified node(s) since {}", nodeIds.size(), isoDate);
        return nodeIds;
    }

    // ── JSON mapping classes ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SearchResponse {
        @JsonProperty("list")
        SearchList list;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SearchList {
        @JsonProperty("entries")
        List<SearchEntryWrapper> entries;

        @JsonProperty("pagination")
        Pagination pagination;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SearchEntryWrapper {
        @JsonProperty("entry")
        SearchEntry entry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SearchEntry {
        @JsonProperty("id")
        String id;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Pagination {
        @JsonProperty("hasMoreItems")
        Boolean hasMoreItems;

        @JsonProperty("totalItems")
        Integer totalItems;

        @JsonProperty("skipCount")
        Integer skipCount;
    }
}
