package com.dev.semsearch.ingest.alfresco;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts an Alfresco node's permission entries into a flat list of authority IDs
 * that have read access. This list is stored on every chunk as the {@code readers}
 * field and used as a terms filter at query time.
 *
 * <p><strong>Rules:</strong>
 * <ul>
 *   <li>The document owner always has read access.</li>
 *   <li>Only ALLOWED entries with a role that implies read are included.</li>
 *   <li>Inherited permissions are included only if inheritance is enabled on the node.</li>
 *   <li>DENY entries are ignored in month one (known limitation — can over-grant).</li>
 * </ul>
 *
 * @see NodeInfo.Permissions
 */
@Component
public class PermissionMapper {

    private static final Logger log = LoggerFactory.getLogger(PermissionMapper.class);

    /**
     * All Alfresco roles that imply read access.
     * Includes both repository roles and site roles.
     */
    private static final Set<String> READ_ROLES = Set.of(
            "Consumer", "Contributor", "Editor", "Collaborator", "Coordinator",
            "SiteConsumer", "SiteContributor", "SiteCollaborator", "SiteManager"
    );

    /**
     * Extracts the list of authority IDs that can read this node.
     *
     * @param nodeInfo the node metadata including permissions and owner
     * @return an immutable list of reader authority IDs
     */
    public List<String> toReaders(NodeInfo nodeInfo) {
        if (nodeInfo == null) {
            return List.of();
        }
        Set<String> readers = new HashSet<>();

        // Owner always has read access
        if (nodeInfo.createdByUserId() != null) {
            readers.add(nodeInfo.createdByUserId());
        }

        NodeInfo.Permissions perms = nodeInfo.permissions();
        if (perms != null) {
            // Process locally-set permissions
            if (perms.locallySet() != null) {
                addAllowedReaders(readers, perms.locallySet());
            }

            // Process inherited permissions only if inheritance is enabled
            if (perms.isInheritanceEnabled() && perms.inherited() != null) {
                addAllowedReaders(readers, perms.inherited());
            }
        }

        log.debug("Node {} has {} reader(s): {}", nodeInfo.id(), readers.size(), readers);
        return readers.stream().sorted().toList();
    }

    private void addAllowedReaders(Set<String> readers, List<NodeInfo.Permission> permissions) {
        if (permissions == null) {
            return;
        }
        for (NodeInfo.Permission perm : permissions) {
            if (perm != null && "ALLOWED".equalsIgnoreCase(perm.accessStatus()) && READ_ROLES.contains(perm.role())) {
                if (perm.authorityId() != null) {
                    readers.add(perm.authorityId());
                }
            }
        }
    }
}
