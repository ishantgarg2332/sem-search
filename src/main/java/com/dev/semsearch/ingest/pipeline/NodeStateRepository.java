package com.dev.semsearch.ingest.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * Data access for the {@code ingest.node_state} table.
 * Tracks the last-indexed content hash and chunk count for each node,
 * enabling content-hash-based skip logic and orphan-chunk cleanup.
 */
@Repository
public class NodeStateRepository {

    private static final Logger log = LoggerFactory.getLogger(NodeStateRepository.class);

    private final JdbcClient jdbc;

    public NodeStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Looks up the last-indexed state for a node.
     *
     * @param nodeId the Alfresco node UUID
     * @return the state, or empty if this node has never been indexed
     */
    public Optional<NodeState> findByNodeId(String nodeId) {
        return jdbc.sql("""
                SELECT node_id, version_label, content_sha256, chunk_count, indexed_at
                FROM ingest.node_state
                WHERE node_id = :nodeId
                """)
                .param("nodeId", nodeId)
                .query(NodeStateRepository::mapRow)
                .optional();
    }

    /**
     * Records the state after indexing a node. If the node already has a state row,
     * it is updated with the new values.
     *
     * @param nodeId        the Alfresco node UUID
     * @param versionLabel  the version label (nullable for unversioned files)
     * @param contentSha256 SHA-256 hash of the file content
     * @param chunkCount    number of chunks created
     */
    @Transactional
    public void upsert(String nodeId, String versionLabel, String contentSha256, int chunkCount) {
        jdbc.sql("""
                INSERT INTO ingest.node_state (node_id, version_label, content_sha256, chunk_count, indexed_at)
                VALUES (:nodeId, :versionLabel, :contentSha256, :chunkCount, now())
                ON CONFLICT (node_id) DO UPDATE SET
                    version_label = EXCLUDED.version_label,
                    content_sha256 = EXCLUDED.content_sha256,
                    chunk_count = EXCLUDED.chunk_count,
                    indexed_at = now()
                """)
                .param("nodeId", nodeId)
                .param("versionLabel", versionLabel)
                .param("contentSha256", contentSha256)
                .param("chunkCount", chunkCount)
                .update();
        log.debug("Upserted node_state for node={} hash={} chunks={}", nodeId, contentSha256, chunkCount);
    }

    /**
     * Deletes the state row for a node (called when the node is deleted from Alfresco).
     *
     * @param nodeId the Alfresco node UUID
     */
    @Transactional
    public void delete(String nodeId) {
        jdbc.sql("DELETE FROM ingest.node_state WHERE node_id = :nodeId")
                .param("nodeId", nodeId)
                .update();
        log.debug("Deleted node_state for node={}", nodeId);
    }

    /**
     * Retrieves all indexed node UUIDs from node_state.
     * Used by orphan reconciliation and permission drift checks.
     *
     * @return list of node IDs
     */
    public java.util.List<String> findAllNodeIds() {
        return jdbc.sql("SELECT node_id FROM ingest.node_state ORDER BY node_id")
                .query(String.class)
                .list();
    }

    private static NodeState mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new NodeState(
                rs.getString("node_id"),
                rs.getString("version_label"),
                rs.getString("content_sha256"),
                rs.getInt("chunk_count"),
                rs.getTimestamp("indexed_at").toInstant()
        );
    }
}
