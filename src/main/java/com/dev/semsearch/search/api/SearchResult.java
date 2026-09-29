package com.dev.semsearch.search.api;

/**
 * Public response DTO for a search result item.
 *
 * @param id           the Alfresco node ID
 * @param name         the document name
 * @param path         the repository folder path
 * @param snippet      a relevant text excerpt from the best-matching chunk
 * @param score        the final Reciprocal Rank Fusion (RRF) score
 * @param alfrescoLink direct link to view/download the document in Alfresco
 */
public record SearchResult(
        String id,
        String name,
        String path,
        String snippet,
        double score,
        String alfrescoLink
) {}
