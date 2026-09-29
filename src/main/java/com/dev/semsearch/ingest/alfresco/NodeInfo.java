package com.dev.semsearch.ingest.alfresco;

import java.time.Instant;
import java.util.List;

/**
 * Immutable representation of an Alfresco node's metadata, permissions, and path.
 * Parsed from the Alfresco v1 REST API response by {@link AlfrescoClient}.
 */
public record NodeInfo(
        String id,
        String name,
        String nodeType,
        String mimeType,
        long sizeInBytes,
        Instant modifiedAt,
        String versionLabel,
        String createdByUserId,
        String path,
        Permissions permissions
) {

    /**
     * A single permission entry from the Alfresco ACL.
     *
     * @param authorityId  the user or group ID (e.g. "alice", "GROUP_FINANCE")
     * @param role         the role name (e.g. "Consumer", "Collaborator")
     * @param accessStatus "ALLOWED" or "DENIED"
     */
    public record Permission(String authorityId, String role, String accessStatus) {}

    /**
     * Aggregated permissions for a node, separating locally-set from inherited entries.
     *
     * @param locallySet           permissions explicitly set on this node
     * @param inherited            permissions inherited from parent folders
     * @param isInheritanceEnabled whether this node inherits permissions from its parent
     */
    public record Permissions(
            List<Permission> locallySet,
            List<Permission> inherited,
            boolean isInheritanceEnabled
    ) {
        public static final Permissions EMPTY = new Permissions(List.of(), List.of(), true);
    }
}
