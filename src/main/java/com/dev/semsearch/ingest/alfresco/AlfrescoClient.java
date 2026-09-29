package com.dev.semsearch.ingest.alfresco;
import com.dev.semsearch.common.alfresco.AlfrescoProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Client for the Alfresco Content Services v1 REST API.
 *
 * <p>Uses a service account (configured in {@link AlfrescoProperties}) with Basic Auth.
 * All calls go through the public REST API at
 * {@code /alfresco/api/-default-/public/alfresco/versions/1/}.
 */
@Component
public class AlfrescoClient {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoClient.class);
    private static final String API_PATH = "/alfresco/api/-default-/public/alfresco/versions/1";

    private final RestClient restClient;

    public AlfrescoClient(AlfrescoProperties props) {
        String credentials = Base64.getEncoder().encodeToString(
                (props.getUsername() + ":" + props.getPassword()).getBytes());

        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl() + API_PATH)
                .defaultHeader("Authorization", "Basic " + credentials)
                .build();
    }

    /**
     * Fetches node metadata including permissions and path.
     *
     * @param nodeId the Alfresco node UUID
     * @return the node info, or empty if the node doesn't exist (404)
     */
    public Optional<NodeInfo> getNode(String nodeId) {
        try {
            NodeResponse response = restClient.get()
                    .uri("/nodes/{nodeId}?include=permissions,path", nodeId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 404) {
                            // Will be caught below via the null check
                            throw new NodeNotFoundException(nodeId);
                        }
                        throw new RuntimeException("Alfresco API error: " + res.getStatusCode());
                    })
                    .body(NodeResponse.class);

            if (response == null || response.entry == null) {
                return Optional.empty();
            }

            NodeEntry entry = response.entry;
            return Optional.of(new NodeInfo(
                    entry.id,
                    entry.name,
                    entry.nodeType,
                    entry.content != null ? entry.content.mimeType : null,
                    entry.content != null ? entry.content.sizeInBytes : 0,
                    parseInstant(entry.modifiedAt),
                    extractVersionLabel(entry),
                    entry.createdByUser != null ? entry.createdByUser.id : null,
                    extractPath(entry),
                    entry.permissions != null ? mapPermissions(entry.permissions) : NodeInfo.Permissions.EMPTY
            ));
        } catch (NodeNotFoundException e) {
            log.info("Node {} not found in Alfresco (deleted or trashed)", nodeId);
            return Optional.empty();
        }
    }

    /**
     * Downloads the content of a node to a temporary file.
     * The caller is responsible for deleting the temp file after use.
     *
     * @param nodeId the Alfresco node UUID
     * @return path to the temp file containing the downloaded content
     */
    public Path downloadContent(String nodeId) {
        try {
            Path tempFile = Files.createTempFile("alfresco-content-", ".tmp");

            restClient.get()
                    .uri("/nodes/{nodeId}/content", nodeId)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is2xxSuccessful()) {
                            try (InputStream is = res.getBody()) {
                                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } else {
                            throw new RuntimeException(
                                    "Failed to download content for node " + nodeId
                                    + ": HTTP " + res.getStatusCode());
                        }
                        return null;
                    });

            log.debug("Downloaded content for node {} to {} ({} bytes)",
                    nodeId, tempFile, Files.size(tempFile));
            return tempFile;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create temp file for node " + nodeId, e);
        }
    }

    // ── Internal JSON mapping classes ──

    private static String extractVersionLabel(NodeEntry entry) {
        if (entry.properties == null) return null;
        Object vl = entry.properties.get("cm:versionLabel");
        return vl != null ? vl.toString() : null;
    }

    private static String extractPath(NodeEntry entry) {
        if (entry.path == null || entry.path.elements == null) return "/";
        StringBuilder sb = new StringBuilder("/");
        for (PathElement el : entry.path.elements) {
            sb.append(el.name).append("/");
        }
        return sb.toString();
    }

    private static NodeInfo.Permissions mapPermissions(PermissionsData perms) {
        List<NodeInfo.Permission> locallySet = perms.locallySet != null
                ? perms.locallySet.stream()
                    .map(p -> new NodeInfo.Permission(p.authorityId, p.name, p.accessStatus))
                    .toList()
                : List.of();

        List<NodeInfo.Permission> inherited = perms.inherited != null
                ? perms.inherited.stream()
                    .map(p -> new NodeInfo.Permission(p.authorityId, p.name, p.accessStatus))
                    .toList()
                : List.of();

        boolean inheritanceEnabled = perms.isInheritanceEnabled != null
                ? perms.isInheritanceEnabled
                : true;

        return new NodeInfo.Permissions(locallySet, inherited, inheritanceEnabled);
    }

    private static final java.time.format.DateTimeFormatter ALFRESCO_DATE_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private static Instant parseInstant(String dateStr) {
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

    // ── JSON DTOs (only used for deserialization) ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class NodeResponse {
        @JsonProperty("entry") public NodeEntry entry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class NodeEntry {
        public String id;
        public String name;
        public String nodeType;
        public ContentInfo content;
        public String modifiedAt;
        public UserInfo createdByUser;
        public PathInfo path;
        public PermissionsData permissions;
        @JsonProperty("properties") public java.util.Map<String, Object> properties;
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
        public String name;       // the role name (Consumer, Collaborator, etc.)
        public String accessStatus; // ALLOWED or DENIED
    }

    /**
     * Thrown internally when the Alfresco API returns 404.
     */
    private static class NodeNotFoundException extends RuntimeException {
        NodeNotFoundException(String nodeId) {
            super("Node not found: " + nodeId);
        }
    }
}
