package com.dev.semsearch.common.alfresco;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client for document management operations against the Alfresco Content Services v1 REST API.
 *
 * <p>Lives in {@code com.dev.semsearch.common.alfresco} so it can be used by both the
 * search (UI) and ingest packages without cross-package imports.
 *
 * <p>Operations: list folder children, get node metadata, upload, update content, delete.
 */
@Component
public class AlfrescoDocumentClient {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoDocumentClient.class);
    private static final String API_PATH = "/alfresco/api/-default-/public/alfresco/versions/1";

    private final RestClient restClient;
    private final String baseUrl;

    public AlfrescoDocumentClient(AlfrescoProperties props) {
        this.baseUrl = props.getBaseUrl();
        String credentials = Base64.getEncoder().encodeToString(
                (props.getUsername() + ":" + props.getPassword()).getBytes());

        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl() + API_PATH)
                .defaultHeader("Authorization", "Basic " + credentials)
                .build();
    }

    // ── List folder children ─────────────────────────────────────────────────

    /**
     * Lists the children of an Alfresco folder.
     *
     * @param folderId the Alfresco node UUID of the folder (use {@code -root-} for the root)
     * @param skipCount pagination offset
     * @param maxItems maximum items per page
     * @return paginated list response
     */
    public ListResponse listChildren(String folderId, int skipCount, int maxItems) {
        String nodeRef = (folderId == null || folderId.isBlank()) ? "-root-" : folderId;

        AlfrescoListResponse alfResponse = restClient.get()
                .uri("/nodes/{nodeId}/children?include=permissions,path&skipCount={skip}&maxItems={max}",
                        nodeRef, skipCount, maxItems)
                .retrieve()
                .body(AlfrescoListResponse.class);

        if (alfResponse == null || alfResponse.list == null) {
            return new ListResponse(List.of(), new Pagination(0, false, 0, 0));
        }

        List<DocumentNode> nodes = alfResponse.list.entries.stream()
                .map(e -> mapEntry(e.entry))
                .toList();

        AlfrescoPagination p = alfResponse.list.pagination;
        Pagination pagination = new Pagination(
                p.totalItems, p.hasMoreItems, p.skipCount, p.maxItems);

        return new ListResponse(nodes, pagination);
    }

    // ── Get node metadata ────────────────────────────────────────────────────

    /**
     * Fetches detailed metadata for a single node, including permissions and path.
     *
     * @param nodeId the Alfresco node UUID
     * @return the node details, or empty if the node doesn't exist (404)
     */
    public Optional<DocumentNode> getNode(String nodeId) {
        try {
            AlfrescoEntryResponse response = restClient.get()
                    .uri("/nodes/{nodeId}?include=permissions,path", nodeId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 404) {
                            throw new NodeNotFoundException(nodeId);
                        }
                        throw new RuntimeException("Alfresco API error: " + res.getStatusCode());
                    })
                    .body(AlfrescoEntryResponse.class);

            if (response == null || response.entry == null) {
                return Optional.empty();
            }
            return Optional.of(mapEntry(response.entry));
        } catch (NodeNotFoundException e) {
            log.info("Node {} not found in Alfresco", nodeId);
            return Optional.empty();
        }
    }

    // ── Stream content ───────────────────────────────────────────────────────

    /**
     * Streams the binary content of a node.
     *
     * @param nodeId the Alfresco node UUID
     * @return the content as an InputStream (caller must close), or empty on 404
     */
    /**
     * Streams the binary content of a node directly into the provided OutputStream.
     * Prevents premature stream closure by streaming within the HTTP exchange.
     *
     * @param nodeId the Alfresco node UUID
     * @param outputStream the target stream to write into
     * @return true if content was streamed, false if not found
     * @throws IOException on streaming failure
     */
    public boolean copyContentToStream(String nodeId, OutputStream outputStream) throws IOException {
        try {
            Boolean success = restClient.get()
                    .uri("/nodes/{nodeId}/content", nodeId)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is2xxSuccessful()) {
                            try (InputStream is = res.getBody()) {
                                is.transferTo(outputStream);
                                outputStream.flush();
                                return true;
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }
                        return false;
                    });
            return Boolean.TRUE.equals(success);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (Exception e) {
            log.warn("Failed to stream content for node {}: {}", nodeId, e.getMessage());
            return false;
        }
    }

    /**
     * Streams the binary content of a node as an in-memory ContentStream.
     *
     * @param nodeId the Alfresco node UUID
     * @return the content stream, or empty on 404
     */
    public Optional<ContentStream> streamContent(String nodeId) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AtomicReference<String> ctRef = new AtomicReference<>();
            AtomicReference<String> fnRef = new AtomicReference<>();

            Boolean found = restClient.get()
                    .uri("/nodes/{nodeId}/content", nodeId)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is2xxSuccessful()) {
                            ctRef.set(res.getHeaders().getFirst("Content-Type"));
                            fnRef.set(extractFilename(res.getHeaders().getFirst("Content-Disposition")));
                            try (InputStream is = res.getBody()) {
                                is.transferTo(baos);
                                return true;
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }
                        return false;
                    });

            if (Boolean.TRUE.equals(found)) {
                return Optional.of(new ContentStream(
                        new ByteArrayInputStream(baos.toByteArray()),
                        ctRef.get(),
                        fnRef.get()
                ));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to stream content for node {}: {}", nodeId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Upload ───────────────────────────────────────────────────────────────

    /**
     * Uploads a file to an Alfresco folder, creating a new document node.
     *
     * @param parentId the folder node UUID to upload into
     * @param file     the multipart file from the HTTP request
     * @return the created document node
     * @throws IOException if the file stream cannot be read
     */
    public DocumentNode uploadDocument(String parentId, MultipartFile file) throws IOException {
        String targetFolder = (parentId == null || parentId.isBlank()) ? "-root-" : parentId;

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("filedata", new InputStreamResource(file.getInputStream()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }

            @Override
            public long contentLength() {
                return file.getSize();
            }
        });
        body.add("name", file.getOriginalFilename());
        body.add("nodeType", "cm:content");

        AlfrescoEntryResponse response = restClient.post()
                .uri("/nodes/{parentId}/children", targetFolder)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(AlfrescoEntryResponse.class);

        if (response == null || response.entry == null) {
            throw new RuntimeException("Upload returned no entry for file: " + file.getOriginalFilename());
        }

        log.info("Uploaded document '{}' to folder {} → nodeId={}",
                file.getOriginalFilename(), targetFolder, response.entry.id);
        return mapEntry(response.entry);
    }

    // ── Update content ───────────────────────────────────────────────────────

    /**
     * Updates the content of an existing node with a new file version.
     * Alfresco auto-versions the document (creates a new minor version).
     *
     * @param nodeId the existing document node UUID
     * @param file   the multipart file containing the new content
     * @return the updated node metadata
     * @throws IOException if the file stream cannot be read
     */
    public DocumentNode updateContent(String nodeId, MultipartFile file) throws IOException {
        AlfrescoEntryResponse response = restClient.put()
                .uri("/nodes/{nodeId}/content?majorVersion=false&comment=Updated+via+UI", nodeId)
                .contentType(MediaType.parseMediaType(
                        file.getContentType() != null ? file.getContentType() : "application/octet-stream"))
                .body(new InputStreamResource(file.getInputStream()) {
                    @Override
                    public String getFilename() {
                        return file.getOriginalFilename();
                    }

                    @Override
                    public long contentLength() {
                        return file.getSize();
                    }
                })
                .retrieve()
                .body(AlfrescoEntryResponse.class);

        if (response == null || response.entry == null) {
            throw new RuntimeException("Update returned no entry for node: " + nodeId);
        }

        log.info("Updated content for node {} (new file: '{}')", nodeId, file.getOriginalFilename());
        return mapEntry(response.entry);
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    /**
     * Moves a node to the Alfresco trashcan (soft delete).
     *
     * @param nodeId the node UUID to delete
     */
    public void deleteNode(String nodeId) {
        restClient.delete()
                .uri("/nodes/{nodeId}", nodeId)
                .retrieve()
                .toBodilessEntity();

        log.info("Deleted node {} (moved to trashcan)", nodeId);
    }

    // ── Folder Creation & Permission Management ──────────────────────────────

    /**
     * Creates a new folder in Alfresco under the specified parent node.
     *
     * @param parentId the parent folder node UUID (use "-root-" for Company Home)
     * @param name the folder name (e.g. "Public Workspace")
     * @param title optional title
     * @param description optional description
     * @return newly created DocumentNode
     */
    public DocumentNode createFolder(String parentId, String name, String title, String description) {
        Map<String, Object> properties = new java.util.HashMap<>();
        if (title != null && !title.isBlank()) {
            properties.put("cm:title", title);
        }
        if (description != null && !description.isBlank()) {
            properties.put("cm:description", description);
        }

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        body.put("nodeType", "cm:folder");
        if (!properties.isEmpty()) {
            body.put("properties", properties);
        }

        AlfrescoEntryResponse response = restClient.post()
                .uri("/nodes/{parentId}/children", parentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(AlfrescoEntryResponse.class);

        if (response == null || response.entry == null) {
            throw new IllegalStateException("Alfresco createFolder returned no entry for: " + name);
        }

        log.info("Created folder '{}' (nodeId={}) under parent={}", name, response.entry.id, parentId);
        return mapEntry(response.entry);
    }

    /**
     * Sets local permissions and inheritance toggle on an Alfresco node.
     *
     * @param nodeId the node UUID
     * @param isInheritanceEnabled whether permissions inherit from parent
     * @param localPermissions list of authority permissions to set locally
     * @return updated DocumentNode
     */
    public DocumentNode setPermissions(String nodeId, boolean isInheritanceEnabled, List<PermissionSetting> localPermissions) {
        List<Map<String, String>> locallySet = (localPermissions != null ? localPermissions : List.<PermissionSetting>of()).stream()
                .map(p -> Map.of(
                        "authorityId", p.authorityId(),
                        "name", p.role(),
                        "accessStatus", p.accessStatus() != null ? p.accessStatus() : "ALLOWED"
                ))
                .toList();

        Map<String, Object> permissionsPayload = Map.of(
                "isInheritanceEnabled", isInheritanceEnabled,
                "locallySet", locallySet
        );

        Map<String, Object> body = Map.of("permissions", permissionsPayload);

        AlfrescoEntryResponse response = restClient.put()
                .uri("/nodes/{nodeId}?include=permissions,path", nodeId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(AlfrescoEntryResponse.class);

        if (response == null || response.entry == null) {
            throw new IllegalStateException("Alfresco setPermissions returned no entry for node: " + nodeId);
        }

        log.info("Updated permissions on node {} (inherit={}, rules={})", nodeId, isInheritanceEnabled, locallySet.size());
        return mapEntry(response.entry);
    }

    public record PermissionSetting(String authorityId, String role, String accessStatus) {}

    // ── Internal helpers ─────────────────────────────────────────────────────

    private DocumentNode mapEntry(AlfrescoNodeEntry entry) {
        String path = "/";
        if (entry.path != null && entry.path.elements != null) {
            StringBuilder sb = new StringBuilder("/");
            for (PathElement el : entry.path.elements) {
                sb.append(el.name).append("/");
            }
            path = sb.toString();
        }

        String mimeType = entry.content != null ? entry.content.mimeType : null;
        long sizeInBytes = entry.content != null ? entry.content.sizeInBytes : 0;
        String versionLabel = null;
        if (entry.properties != null && entry.properties.containsKey("cm:versionLabel")) {
            versionLabel = entry.properties.get("cm:versionLabel").toString();
        }
        String createdBy = entry.createdByUser != null ? entry.createdByUser.displayName : null;
        String modifiedBy = entry.modifiedByUser != null ? entry.modifiedByUser.displayName : null;

        List<PermissionInfo> permissions = List.of();
        boolean inheritPerms = true;
        if (entry.permissions != null) {
            List<PermissionInfo> combined = new java.util.ArrayList<>();
            if (entry.permissions.locallySet != null) {
                entry.permissions.locallySet.forEach(p ->
                        combined.add(new PermissionInfo(p.authorityId, p.name, p.accessStatus, "local")));
            }
            if (entry.permissions.inherited != null) {
                entry.permissions.inherited.forEach(p ->
                        combined.add(new PermissionInfo(p.authorityId, p.name, p.accessStatus, "inherited")));
            }
            permissions = List.copyOf(combined);
            inheritPerms = entry.permissions.isInheritanceEnabled != null
                    ? entry.permissions.isInheritanceEnabled : true;
        }

        return new DocumentNode(
                entry.id,
                entry.name,
                entry.nodeType,
                entry.isFolder != null && entry.isFolder,
                entry.isFile != null && entry.isFile,
                mimeType,
                sizeInBytes,
                parseInstant(entry.createdAt),
                parseInstant(entry.modifiedAt),
                createdBy,
                modifiedBy,
                versionLabel,
                path,
                permissions,
                inheritPerms,
                entry.createdByUser != null ? entry.createdByUser.id : null
        );
    }

    private static final java.time.format.DateTimeFormatter ALFRESCO_DATE_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private Instant parseInstant(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(dateStr, ALFRESCO_DATE_FORMAT).toInstant();
        } catch (Exception e) {
            try {
                return java.time.Instant.parse(dateStr);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private String extractFilename(String contentDisposition) {
        if (contentDisposition == null) return "download";
        for (String part : contentDisposition.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("filename=")) {
                return trimmed.substring("filename=".length()).replace("\"", "").trim();
            }
        }
        return "download";
    }

    // ── Public DTOs ──────────────────────────────────────────────────────────

    /**
     * Represents a document or folder node from Alfresco, with metadata and permissions.
     */
    public record DocumentNode(
            String id,
            String name,
            String nodeType,
            boolean isFolder,
            boolean isFile,
            String mimeType,
            long sizeInBytes,
            Instant createdAt,
            Instant modifiedAt,
            String createdBy,
            String modifiedBy,
            String versionLabel,
            String path,
            List<PermissionInfo> permissions,
            boolean inheritPermissions,
            String createdById
    ) {}

    /**
     * A single permission entry with its source (local vs inherited).
     */
    public record PermissionInfo(
            String authorityId,
            String role,
            String accessStatus,
            String source
    ) {}

    /**
     * Wrapper for content streaming — holds the InputStream, content type, and filename.
     * Caller is responsible for closing the InputStream.
     */
    public record ContentStream(
            InputStream inputStream,
            String contentType,
            String filename
    ) {}

    /**
     * Paginated list of document nodes.
     */
    public record ListResponse(
            List<DocumentNode> entries,
            Pagination pagination
    ) {}

    /**
     * Pagination metadata for list operations.
     */
    public record Pagination(
            long totalItems,
            boolean hasMoreItems,
            int skipCount,
            int maxItems
    ) {}

    // ── Internal JSON DTOs (deserialization only) ────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AlfrescoListResponse {
        @JsonProperty("list") public AlfrescoList list;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AlfrescoList {
        @JsonProperty("entries") public List<AlfrescoEntryWrapper> entries;
        @JsonProperty("pagination") public AlfrescoPagination pagination;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AlfrescoEntryWrapper {
        @JsonProperty("entry") public AlfrescoNodeEntry entry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AlfrescoEntryResponse {
        @JsonProperty("entry") public AlfrescoNodeEntry entry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AlfrescoPagination {
        @JsonProperty("totalItems") public long totalItems;
        @JsonProperty("hasMoreItems") public boolean hasMoreItems;
        @JsonProperty("skipCount") public int skipCount;
        @JsonProperty("maxItems") public int maxItems;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AlfrescoNodeEntry {
        public String id;
        public String name;
        public String nodeType;
        public Boolean isFolder;
        public Boolean isFile;
        public ContentInfo content;
        public String createdAt;
        public String modifiedAt;
        public UserInfo createdByUser;
        public UserInfo modifiedByUser;
        public PathInfo path;
        public PermissionsData permissions;
        @JsonProperty("properties") public Map<String, Object> properties;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ContentInfo {
        public String mimeType;
        public long sizeInBytes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class UserInfo {
        public String id;
        public String displayName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PathInfo {
        public List<PathElement> elements;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PathElement {
        public String name;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PermissionsData {
        public List<PermissionEntry> locallySet;
        public List<PermissionEntry> inherited;
        public Boolean isInheritanceEnabled;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PermissionEntry {
        public String authorityId;
        public String name;
        public String accessStatus;
    }

    private static class NodeNotFoundException extends RuntimeException {
        NodeNotFoundException(String nodeId) {
            super("Node not found: " + nodeId);
        }
    }
}
