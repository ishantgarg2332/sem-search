package com.dev.semsearch.ingest.pipeline;

import com.dev.semsearch.ingest.IngestProperties;
import com.dev.semsearch.ingest.alfresco.AlfrescoClient;
import com.dev.semsearch.ingest.alfresco.NodeInfo;
import com.dev.semsearch.ingest.alfresco.PermissionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the full ingestion pipeline for a single node.
 *
 * <p>UPSERT flow:
 * <ol>
 *   <li>Fetch node metadata + permissions from Alfresco (404 → treat as delete)</li>
 *   <li>Check MIME type and file size (unsupported → skip)</li>
 *   <li>Download content to a temp file</li>
 *   <li>SHA-256 hash the content, compare with {@code node_state}</li>
 *   <li>If hash matches → metadata-only update (no re-embedding)</li>
 *   <li>Extract text (Tika), chunk (TokenTextSplitter), embed (Ollama)</li>
 *   <li>Bulk index chunks + delete orphans</li>
 *   <li>Upsert {@code node_state}</li>
 * </ol>
 *
 * <p>DELETE flow: remove all chunks from Elasticsearch and the {@code node_state} row.
 */
@Component
public class ContentProcessor {

    private static final Logger log = LoggerFactory.getLogger(ContentProcessor.class);

    private final AlfrescoClient alfrescoClient;
    private final PermissionMapper permissionMapper;
    private final TextExtractor textExtractor;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final ChunkIndexer chunkIndexer;
    private final NodeStateRepository nodeStateRepository;
    private final IngestProperties properties;

    public ContentProcessor(AlfrescoClient alfrescoClient,
                            PermissionMapper permissionMapper,
                            TextExtractor textExtractor,
                            ChunkService chunkService,
                            EmbeddingService embeddingService,
                            ChunkIndexer chunkIndexer,
                            NodeStateRepository nodeStateRepository,
                            IngestProperties properties) {
        this.alfrescoClient = alfrescoClient;
        this.permissionMapper = permissionMapper;
        this.textExtractor = textExtractor;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.chunkIndexer = chunkIndexer;
        this.nodeStateRepository = nodeStateRepository;
        this.properties = properties;
    }

    /**
     * Processes an UPSERT job: fetches, extracts, chunks, embeds, and indexes.
     */
    public void processUpsert(String nodeId) throws IOException {
        // 1. Fetch node metadata + permissions
        Optional<NodeInfo> optNode = alfrescoClient.getNode(nodeId);
        if (optNode.isEmpty()) {
            log.info("Node {} not found in Alfresco, treating UPSERT as DELETE", nodeId);
            processDelete(nodeId);
            return;
        }

        NodeInfo node = optNode.get();

        // 2. Check MIME type
        if (node.mimeType() == null || !isSupportedMimeType(node.mimeType())) {
            log.info("Skipping node {} — unsupported MIME type: {}", nodeId, node.mimeType());
            removeStaleIndexIfPresent(nodeId, "content type changed to " + node.mimeType());
            return;
        }

        // 3. Check file size
        long maxBytes = properties.getPipeline().getMaxFileSizeMb() * 1024L * 1024L;
        if (node.sizeInBytes() > maxBytes) {
            log.info("Skipping node {} — file size {} bytes exceeds limit of {} MB",
                    nodeId, node.sizeInBytes(), properties.getPipeline().getMaxFileSizeMb());
            removeStaleIndexIfPresent(nodeId, "file grew past the size limit");
            return;
        }

        // 4. Map permissions to readers list
        List<String> readers = permissionMapper.toReaders(node);

        // 5. Download content
        Path tempFile = null;
        try {
            tempFile = alfrescoClient.downloadContent(nodeId);

            // 6. Hash content
            String contentHash = sha256(tempFile);

            // 7. Check node_state for content-hash match
            Optional<NodeState> existingState = nodeStateRepository.findByNodeId(nodeId);
            if (existingState.isPresent() && existingState.get().contentSha256().equals(contentHash)) {
                // Content unchanged — metadata-only update (name, path, permissions)
                log.info("Content unchanged for node {} (hash match). Updating metadata only.", nodeId);
                chunkIndexer.updateMetadataOnly(nodeId, node.name(), node.path(),
                        node.mimeType(), node.modifiedAt(), readers);
                // Update node_state with new version label if it changed
                nodeStateRepository.upsert(nodeId, node.versionLabel(), contentHash,
                        existingState.get().chunkCount());
                return;
            }

            // 8. Extract text
            String text = textExtractor.extractText(tempFile);
            if (text == null || text.isBlank()) {
                log.info("Skipping node {} — no text extracted (empty or image-only PDF)", nodeId);
                removeStaleIndexIfPresent(nodeId, "new content has no extractable text");
                return;
            }

            // 9. Chunk
            List<String> chunks = chunkService.chunk(text);
            if (chunks.isEmpty()) {
                log.info("Skipping node {} — chunking produced zero chunks", nodeId);
                removeStaleIndexIfPresent(nodeId, "new content produced no chunks");
                return;
            }

            // 10. Embed
            List<float[]> embeddings = embeddingService.embed(chunks);

            // 11. Index chunks + delete orphans
            chunkIndexer.indexChunks(nodeId, node.versionLabel(), node.name(), node.path(),
                    node.mimeType(), node.modifiedAt(), readers, chunks, embeddings);

            // 12. Record state
            nodeStateRepository.upsert(nodeId, node.versionLabel(), contentHash, chunks.size());

            log.info("Successfully processed UPSERT for node={} chunks={}", nodeId, chunks.size());

        } finally {
            // Always clean up the temp file
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file {}: {}", tempFile, e.getMessage());
                }
            }
        }
    }

    /**
     * Processes a DELETE job: removes all chunks and the node_state row.
     */
    public void processDelete(String nodeId) throws IOException {
        chunkIndexer.deleteAllChunks(nodeId);
        nodeStateRepository.delete(nodeId);
        log.info("Processed DELETE for node={}", nodeId);
    }

    /**
     * A node that was indexed before can change into something we skip (a PDF replaced by
     * a .docx, a file that grew past 50 MB, a scan with no text layer). Its old chunks
     * would otherwise stay searchable, showing text the document no longer contains.
     * If we have state for the node, remove its chunks and state, exactly like a delete.
     */
    private void removeStaleIndexIfPresent(String nodeId, String reason) throws IOException {
        if (nodeStateRepository.findByNodeId(nodeId).isPresent()) {
            log.info("Removing previously indexed chunks for node {}: {}", nodeId, reason);
            processDelete(nodeId);
        }
    }

    private boolean isSupportedMimeType(String mimeType) {
        return properties.getPipeline().getSupportedMimeTypes().contains(mimeType);
    }

    /**
     * Computes the SHA-256 hash of a file's contents.
     */
    private static String sha256(Path filePath) throws IOException {
        // Stream the file through the digest in 64 KB blocks instead of reading all of it
        // (up to 50 MB) into memory at once.
        try (InputStream in = Files.newInputStream(filePath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
