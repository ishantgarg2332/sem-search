package com.dev.semsearch.search.authority;

import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.DocumentNode;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.PermissionInfo;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether the signed-in user may perform an operation on an Alfresco node
 * before the document proxy calls Alfresco with its service account.
 *
 * <p><strong>Why this exists:</strong> the proxy talks to Alfresco as the admin service
 * account, and admin can do anything. Without this check, every signed-in user
 * inherits admin's access through the proxy.
 *
 * <p><strong>Rules (fail closed):</strong>
 * <ul>
 *   <li>App users with {@code ROLE_ADMIN} are allowed everything (mirrors Alfresco's admin).</li>
 *   <li>The node's creator is allowed everything (Alfresco's owner has full control).</li>
 *   <li>Otherwise an ALLOWED entry for one of the user's authorities must grant a role
 *       that covers the operation. Inherited entries count only if inheritance is on.</li>
 *   <li>A DENIED entry for any of the user's authorities blocks access. This is stricter
 *       than Alfresco's real evaluation order, which is the safe direction for a proxy.</li>
 * </ul>
 *
 * <p><strong>Known limitation:</strong> this re-implements a simplified version of Alfresco's
 * permission model. The exact fix is to call Alfresco <em>as the user</em> (a ticket or
 * the Identity Service), so Alfresco itself decides. Custom roles are unknown here and deny.
 */
@Component
public class DocumentAccessPolicy {

    public enum Operation { READ, UPDATE_CONTENT, DELETE, CREATE_CHILDREN }

    private static final Set<String> READ = Set.of(
            "Consumer", "Contributor", "Editor", "Collaborator", "Coordinator",
            "SiteConsumer", "SiteContributor", "SiteCollaborator", "SiteManager");

    /** Roles that may edit existing content. */
    private static final Set<String> UPDATE = Set.of(
            "Editor", "Collaborator", "Coordinator", "SiteCollaborator", "SiteManager");

    /** Roles that may delete content they don't own. */
    private static final Set<String> DELETE = Set.of("Coordinator", "SiteManager");

    /** Roles that may add files to a folder. */
    private static final Set<String> CREATE = Set.of(
            "Contributor", "Collaborator", "Coordinator",
            "SiteContributor", "SiteCollaborator", "SiteManager");

    private static final Map<Operation, Set<String>> ROLES_FOR = Map.of(
            Operation.READ, READ,
            Operation.UPDATE_CONTENT, UPDATE,
            Operation.DELETE, DELETE,
            Operation.CREATE_CHILDREN, CREATE);

    private final AuthorityService authorityService;

    public DocumentAccessPolicy(AuthorityService authorityService) {
        this.authorityService = authorityService;
    }

    public boolean isAllowed(Authentication auth, DocumentNode node, Operation op) {
        if (auth == null || auth.getName() == null || node == null) {
            return false;
        }
        if (isAppAdmin(auth)) {
            return true;
        }
        String username = auth.getName();
        if (username.equals(node.createdById())) {
            return true;
        }

        Set<String> authorities = new HashSet<>(authorityService.getAuthoritiesForUser(username));
        List<PermissionInfo> effective = node.permissions().stream()
                .filter(p -> node.inheritPermissions() || !"inherited".equals(p.source()))
                .filter(p -> p.authorityId() != null && authorities.contains(p.authorityId()))
                .toList();

        boolean denied = effective.stream().anyMatch(p -> "DENIED".equalsIgnoreCase(p.accessStatus()));
        if (denied) {
            return false;
        }
        Set<String> grantingRoles = ROLES_FOR.get(op);
        return effective.stream().anyMatch(p ->
                "ALLOWED".equalsIgnoreCase(p.accessStatus()) && grantingRoles.contains(p.role()));
    }

    private static boolean isAppAdmin(Authentication auth) {
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
