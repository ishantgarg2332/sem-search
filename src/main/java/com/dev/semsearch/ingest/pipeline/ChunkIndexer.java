package com.dev.semsearch.ingest.pipeline;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.dev.semsearch.common.elasticsearch.ElasticsearchIndexInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexes document chunks into Elasticsearch and handles cleanup of orphan chunks.
 *
 * <p>All operations go through the {@code doc-chunks} alias, never the concrete
 * index name, enabling zero-downtime reindexing in the future.
 *
 * <p>Chunk document IDs follow the pattern {@code {nodeId}_{chunkIndex}} (zero-padded
 * to 4 digits), making upserts idempotent: re-indexing the same document overwrites
 * rather than duplicates.
 */
@Component
public class ChunkIndexer {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexer.class);
    private static final String ALIAS = ElasticsearchIndexInitializer.ALIAS_NAME;

    private final ElasticsearchClient client;

    public ChunkIndexer(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * Indexes chunks for a node and deletes any orphan chunks from a previous
     * (longer) version of the document.
     *
     * @param nodeId       Alfresco node UUID
     * @param versionLabel version label (nullable)
     * @param name         document name
     * @param path         folder path
     * @param mimeType     MIME type
     * @param modifiedAt   last-modified timestamp
     * @param readers      list of authority IDs with read access
     * @param chunks       text content of each chunk
     * @param embeddings   vector embeddings (one per chunk, same order)
     */
    public void indexChunks(String nodeId, String versionLabel, String name, String path,
                            String mimeType, Instant modifiedAt, List<String> readers,
                            List<String> chunks, List<float[]> embeddings) throws IOException {

        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "chunks.size() = " + chunks.size() + " but embeddings.size() = " + embeddings.size());
        }

        // Build bulk request
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder().index(ALIAS);

        for (int i = 0; i < chunks.size(); i++) {
            String docId = formatDocId(nodeId, i);
            Map<String, Object> doc = new HashMap<>();
            doc.put("node_id", nodeId);
            doc.put("version_label", versionLabel);
            doc.put("chunk_index", i);
            doc.put("name", name);
            doc.put("path", path);
            doc.put("mime_type", mimeType);
            doc.put("modified_at", modifiedAt != null ? modifiedAt.toString() : null);
            doc.put("readers", readers);
            doc.put("text", chunks.get(i));
            doc.put("embedding", floatArrayToList(embeddings.get(i)));

            bulkBuilder.operations(op -> op
                    .index(idx -> idx.id(docId).document(doc))
            );
        }

        BulkResponse bulkResponse = client.bulk(bulkBuilder.build());

        if (bulkResponse.errors()) {
            for (BulkResponseItem item : bulkResponse.items()) {
                if (item.error() != null) {
                    log.error("Bulk index error for doc {}: {}", item.id(), item.error().reason());
                }
            }
            throw new IOException("Bulk indexing had errors for node " + nodeId);
        }

        log.info("Indexed {} chunk(s) for node={}", chunks.size(), nodeId);

        // Delete orphan chunks from previous longer version
        deleteOrphanChunks(nodeId, chunks.size());
    }

    /**
     * Updates only the metadata fields on existing chunks for a node,
     * without touching the text or embedding. Used when the content hash
     * hasn't changed (metadata-only update: rename, move, permission change).
     */
    public void updateMetadataOnly(String nodeId, String name, String path,
                                    String mimeType, Instant modifiedAt,
                                    List<String> readers) throws IOException {
        client.updateByQuery(u -> u
                .index(ALIAS)
                .query(q -> q.term(t -> t.field("node_id").value(nodeId)))
                .script(s -> s
                        .source("""
                            ctx._source.name = params.name;
                            ctx._source.path = params.path;
                            ctx._source.mime_type = params.mimeType;
                            ctx._source.modified_at = params.modifiedAt;
                            ctx._source.readers = params.readers;
                            """)
                        .params("name", co.elastic.clients.json.JsonData.of(name))
                        .params("path", co.elastic.clients.json.JsonData.of(path))
                        .params("mimeType", co.elastic.clients.json.JsonData.of(mimeType))
                        .params("modifiedAt", co.elastic.clients.json.JsonData.of(
                                modifiedAt != null ? modifiedAt.toString() : null))
                        .params("readers", co.elastic.clients.json.JsonData.of(readers))
                )
        );
        log.info("Updated metadata only for node={}", nodeId);
    }

    /**
     * Deletes all chunks for a node from the index.
     */
    public void deleteAllChunks(String nodeId) throws IOException {
        DeleteByQueryResponse response = client.deleteByQuery(d -> d
                .index(ALIAS)
                .query(q -> q.term(t -> t.field("node_id").value(nodeId)))
        );
        log.info("Deleted {} chunk(s) for node={}", response.deleted(), nodeId);
    }

    /**
     * Deletes chunks with chunk_index >= newCount for a node.
     * Handles the case where a document gets shorter after re-indexing.
     */
    private void deleteOrphanChunks(String nodeId, int newCount) throws IOException {
        DeleteByQueryResponse response = client.deleteByQuery(d -> d
                .index(ALIAS)
                .query(q -> q.bool(b -> b
                        .must(m -> m.term(t -> t.field("node_id").value(nodeId)))
                        .must(m -> m.range(r -> r.number(n -> n.field("chunk_index").gte((double) newCount))))
                ))
        );
        if (response.deleted() != null && response.deleted() > 0) {
            log.info("Deleted {} orphan chunk(s) for node={} (chunk_index >= {})",
                    response.deleted(), nodeId, newCount);
        }
    }

    /**
     * Formats a chunk document ID: {nodeId}_{chunkIndex zero-padded to 4 digits}.
     */
    private static String formatDocId(String nodeId, int chunkIndex) {
        return nodeId + "_" + String.format("%04d", chunkIndex);
    }

    /**
     * Converts a float[] to a List&lt;Double&gt; for Elasticsearch JSON serialization.
     */
    private static List<Double> floatArrayToList(float[] arr) {
        Double[] result = new Double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            result[i] = (double) arr[i];
        }
        return List.of(result);
    }
}
