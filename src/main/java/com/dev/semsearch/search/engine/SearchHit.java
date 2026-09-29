package com.dev.semsearch.search.engine;

/**
 * An individual chunk hit returned from Elasticsearch by either BM25 or kNN search.
 *
 * @param id         the Elasticsearch document ID ({nodeId}_{chunkIndex})
 * @param nodeId     the Alfresco node UUID
 * @param chunkIndex the index of this chunk within the document
 * @param name       the document filename
 * @param path       the folder path in Alfresco
 * @param text       the extracted plain text snippet
 * @param score      the raw score from the search engine (BM25 score or cosine similarity)
 */
public record SearchHit(
        String id,
        String nodeId,
        int chunkIndex,
        String name,
        String path,
        String text,
        double score
) {}
