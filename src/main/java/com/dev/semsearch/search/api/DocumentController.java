package com.dev.semsearch.search.api;

import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.ContentStream;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.DocumentNode;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.ListResponse;
import com.dev.semsearch.search.authority.DocumentAccessPolicy;
import com.dev.semsearch.search.authority.DocumentAccessPolicy.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for document management operations against Alfresco.
 *
 * <p>Acts as a secure proxy — the React frontend talks only to this API,
 * never directly to Alfresco. All endpoints require authentication, and every
 * operation is checked against the node's Alfresco permissions for the signed-in
 * user via {@link DocumentAccessPolicy} <em>before</em> the service account acts.
 *
 * <p>Status codes: 404 when the user can't read the node (so its existence isn't
 * revealed), 403 when they can read it but the operation needs a stronger role.
 *
 * <p>Document mutations (upload, update, delete) automatically trigger
 * Alfresco ActiveMQ events, which the ingest pipeline picks up. No manual
 * wiring is needed between this controller and the ingestion system.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final AlfrescoDocumentClient documentClient;
    private final DocumentAccessPolicy accessPolicy;

    public DocumentController(AlfrescoDocumentClient documentClient, DocumentAccessPolicy accessPolicy) {
        this.documentClient = documentClient;
        this.accessPolicy = accessPolicy;
    }

    /**
     * Lists the children of an Alfresco folder.
     * Use {@code folderId=-root-} or omit to browse the repository root.
     *
     * @param folderId  the Alfresco folder node UUID (default: repository root)
     * @param skipCount pagination offset (default: 0)
     * @param maxItems  maximum items per page (default: 25)
     * @return paginated list of child nodes (files and folders)
     */
    @GetMapping
    public ListResponse listDocuments(
            @RequestParam(value = "folderId", defaultValue = "-root-") String folderId,
            @RequestParam(value = "skipCount", defaultValue = "0") int skipCount,
            @RequestParam(value = "maxItems", defaultValue = "25") int maxItems,
            Authentication auth
    ) {
        log.info("User '{}' listing folder={} skip={} max={}",
                username(auth), folderId, skipCount, maxItems);

        DocumentNode folder = requireReadable(folderId, auth);
        if (!folder.isFolder()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a folder");
        }

        ListResponse page = documentClient.listChildren(folderId, skipCount, maxItems);
        // Hide children the user can't read. Pagination still reflects Alfresco's
        // unfiltered page, so a page may hold fewer than maxItems entries.
        return new ListResponse(
                page.entries().stream()
                        .filter(child -> accessPolicy.isAllowed(auth, child, Operation.READ))
                        .toList(),
                page.pagination());
    }

    /**
     * Retrieves full metadata for a single document or folder.
     *
     * @param nodeId the Alfresco node UUID
     * @return the node metadata, or 404 if not found
     */
    @GetMapping("/{nodeId}")
    public ResponseEntity<DocumentNode> getDocument(
            @PathVariable String nodeId,
            Authentication auth
    ) {
        log.info("User '{}' getting metadata for node={}", username(auth), nodeId);
        return ResponseEntity.ok(requireReadable(nodeId, auth));
    }

    /**
     * Streams the binary content of a document for download or inline preview.
     * Sets appropriate Content-Type, Content-Length, and Content-Disposition headers.
     *
     * @param nodeId the Alfresco node UUID
     * @param disposition header type: "attachment" (default) or "inline"
     * @return the file content as a streaming response, or 404 if not found
     */
    @GetMapping("/{nodeId}/content")
    public ResponseEntity<StreamingResponseBody> downloadContent(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "attachment") String disposition,
            Authentication auth
    ) {
        log.info("User '{}' downloading content for node={}, disposition={}",
                username(auth), nodeId, disposition);

        DocumentNode node = requireReadable(nodeId, auth);
        if (node.isFolder()) {
            return ResponseEntity.notFound().build();
        }
        String mimeType = (node.mimeType() != null && !node.mimeType().isBlank())
                ? node.mimeType()
                : "application/octet-stream";

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(mimeType);
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        String dispType = "inline".equalsIgnoreCase(disposition) ? "inline" : "attachment";
        ContentDisposition contentDisposition = ContentDisposition.builder(dispType)
                .filename(node.name(), StandardCharsets.UTF_8)
                .build();

        StreamingResponseBody stream = out -> {
            boolean copied = documentClient.copyContentToStream(nodeId, out);
            if (!copied) {
                log.warn("Content could not be copied for node={}", nodeId);
            }
        };

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());

        if (node.sizeInBytes() > 0) {
            builder.contentLength(node.sizeInBytes());
        }

        return builder.body(stream);
    }

    /**
     * Uploads a file to an Alfresco folder, creating a new document node.
     *
     * <p>Alfresco will fire an {@code OnNodeCreated} event via ActiveMQ,
     * which the ingestion pipeline automatically picks up. The document
     * will be extractable and searchable within seconds.
     *
     * @param parentId the folder node UUID to upload into (default: repository root)
     * @param file     the file to upload
     * @return the created document node metadata
     */
    @PostMapping("/upload")
    public ResponseEntity<DocumentNode> uploadDocument(
            @RequestParam(value = "parentId", defaultValue = "-root-") String parentId,
            @RequestParam("file") MultipartFile file,
            Authentication auth
    ) throws IOException {
        log.info("User '{}' uploading '{}' ({} bytes) to folder={}",
                username(auth), file.getOriginalFilename(), file.getSize(), parentId);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        requireAllowed(parentId, auth, Operation.CREATE_CHILDREN);

        DocumentNode created = documentClient.uploadDocument(parentId, file);
        return ResponseEntity.ok(created);
    }

    /**
     * Updates the content of an existing document with a new file version.
     *
     * <p>Alfresco will fire an {@code OnNodeUpdated} event. The ingestion pipeline
     * will compare the SHA-256 hash — if content changed, it re-extracts and
     * re-embeds; if unchanged (metadata-only change), it fast-paths.
     *
     * @param nodeId the existing document node UUID
     * @param file   the new file content
     * @return the updated document node metadata
     */
    @PutMapping("/{nodeId}/content")
    public ResponseEntity<DocumentNode> updateContent(
            @PathVariable String nodeId,
            @RequestParam("file") MultipartFile file,
            Authentication auth
    ) throws IOException {
        log.info("User '{}' updating content for node={} with '{}' ({} bytes)",
                username(auth), nodeId, file.getOriginalFilename(), file.getSize());

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        requireAllowed(nodeId, auth, Operation.UPDATE_CONTENT);

        DocumentNode updated = documentClient.updateContent(nodeId, file);
        return ResponseEntity.ok(updated);
    }

    /**
     * Deletes a document from Alfresco (moves to trashcan).
     *
     * <p>Alfresco will fire an {@code OnNodeDeleted} event. The ingestion
     * pipeline automatically removes all associated chunks from Elasticsearch.
     *
     * @param nodeId the node UUID to delete
     * @return success confirmation
     */
    @DeleteMapping("/{nodeId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @PathVariable String nodeId,
            Authentication auth
    ) {
        log.info("User '{}' deleting node={}", username(auth), nodeId);
        requireAllowed(nodeId, auth, Operation.DELETE);
        documentClient.deleteNode(nodeId);
        return ResponseEntity.ok(Map.of(
                "status", "DELETED",
                "nodeId", nodeId
        ));
    }

    /**
     * Loads the node and checks READ. Missing and unreadable both return 404,
     * so a user can't probe which node IDs exist.
     */
    private DocumentNode requireReadable(String nodeId, Authentication auth) {
        Optional<DocumentNode> node = documentClient.getNode(nodeId);
        if (node.isEmpty() || !accessPolicy.isAllowed(auth, node.get(), Operation.READ)) {
            log.warn("User '{}' denied READ (or node missing) for node={}", username(auth), nodeId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return node.get();
    }

    /** READ first (404 if not), then the requested operation (403 if not). */
    private DocumentNode requireAllowed(String nodeId, Authentication auth, Operation op) {
        DocumentNode node = requireReadable(nodeId, auth);
        if (!accessPolicy.isAllowed(auth, node, op)) {
            log.warn("User '{}' denied {} on node={}", username(auth), op, nodeId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return node;
    }

    private String username(Authentication auth) {
        return (auth != null && auth.getName() != null) ? auth.getName() : "anonymous";
    }
}
