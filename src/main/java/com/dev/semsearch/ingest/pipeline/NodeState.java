package com.dev.semsearch.ingest.pipeline;

import java.time.Instant;

/**
 * Represents a row from the {@code ingest.node_state} table.
 * Tracks what was last indexed for a given Alfresco node, enabling
 * content-hash-based skip logic and orphan-chunk cleanup.
 */
public record NodeState(
        String nodeId,
        String versionLabel,
        String contentSha256,
        int chunkCount,
        Instant indexedAt
) {}
