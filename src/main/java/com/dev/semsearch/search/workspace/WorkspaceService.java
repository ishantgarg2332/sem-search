package com.dev.semsearch.search.workspace;

import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.DocumentNode;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.PermissionSetting;
import com.dev.semsearch.search.workspace.WorkspaceDtos.CreateWorkspaceRequest;
import com.dev.semsearch.search.workspace.WorkspaceDtos.WorkspaceActivityInfo;
import com.dev.semsearch.search.workspace.WorkspaceDtos.WorkspaceFolderDetail;
import com.dev.semsearch.search.workspace.WorkspaceDtos.WorkspaceFolderSummary;
import com.dev.semsearch.search.workspace.WorkspaceDtos.WorkspacePermissionInfo;
import com.dev.semsearch.search.workspace.WorkspaceFolder.Visibility;
import com.dev.semsearch.search.workspace.WorkspaceFolderRepository.WorkspaceFolderWithRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    private static final String GROUP_EVERYONE = "GROUP_EVERYONE";

    private final WorkspaceFolderRepository folderRepo;
    private final WorkspacePermissionRepository permissionRepo;
    private final WorkspaceAccessRequestRepository accessRequestRepo;
    private final WorkspaceActivityRepository activityRepo;
    private final AlfrescoDocumentClient alfrescoClient;
    private final ObjectMapper objectMapper;

    public WorkspaceService(
            WorkspaceFolderRepository folderRepo,
            WorkspacePermissionRepository permissionRepo,
            WorkspaceAccessRequestRepository accessRequestRepo,
            WorkspaceActivityRepository activityRepo,
            AlfrescoDocumentClient alfrescoClient,
            ObjectMapper objectMapper
    ) {
        this.folderRepo = folderRepo;
        this.permissionRepo = permissionRepo;
        this.accessRequestRepo = accessRequestRepo;
        this.activityRepo = activityRepo;
        this.alfrescoClient = alfrescoClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new workspace folder in Alfresco and establishes its access control.
     * Public folders are read-only for GROUP_EVERYONE; private folders are isolated to the owner.
     */
    @Transactional
    public WorkspaceFolderSummary createFolder(String ownerUsername, CreateWorkspaceRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Folder name is required");
        }

        String name = request.name().trim();
        String description = request.description() != null ? request.description().trim() : "";
        Visibility visibility = request.visibility() != null ? request.visibility() : Visibility.PRIVATE;

        // 1. Create the folder in Alfresco under Company Home ("-root-")
        DocumentNode alfrescoFolder = alfrescoClient.createFolder("-root-", name, name, description);
        String alfrescoNodeId = alfrescoFolder.id();

        // 2. Synchronize Alfresco ACLs
        List<PermissionSetting> localRules = new ArrayList<>();
        // Owner always gets full Coordinator permissions
        localRules.add(new PermissionSetting(ownerUsername, "Coordinator", "ALLOWED"));

        if (visibility == Visibility.PUBLIC) {
            // Default permission of a public folder for other registered users is read-only (Consumer)
            localRules.add(new PermissionSetting(GROUP_EVERYONE, "Consumer", "ALLOWED"));
        }

        // isInheritanceEnabled = false isolates the workspace folder from Company Home
        alfrescoClient.setPermissions(alfrescoNodeId, false, localRules);

        // 3. Persist metadata in Postgres
        Instant now = Instant.now();
        UUID folderId = UUID.randomUUID();
        WorkspaceFolder folder = new WorkspaceFolder(
                folderId,
                alfrescoNodeId,
                name,
                description,
                ownerUsername,
                visibility,
                now,
                now
        );
        folderRepo.save(folder);

        // 4. Record owner permission
        permissionRepo.grantPermission(folderId, ownerUsername, "COORDINATOR", "OWNER", ownerUsername);

        // 5. Audit log
        String detailsJson = toJson(Map.of(
                "name", name,
                "visibility", visibility.name(),
                "alfrescoNodeId", alfrescoNodeId
        ));
        activityRepo.logActivity(folderId, ownerUsername, "FOLDER_CREATED", detailsJson);

        log.info("Created workspace folder '{}' (id={}, node={}) by user '{}' [visibility={}]",
                name, folderId, alfrescoNodeId, ownerUsername, visibility);

        return new WorkspaceFolderSummary(
                folderId,
                alfrescoNodeId,
                name,
                description,
                ownerUsername,
                visibility,
                "COORDINATOR",
                true,
                now,
                now
        );
    }

    /**
     * Lists all folders accessible to the current user (owned, public, or explicitly granted).
     */
    public List<WorkspaceFolderSummary> listAccessibleFolders(String username, boolean isAdmin) {
        List<WorkspaceFolderWithRole> results = folderRepo.findAccessibleFolders(username, isAdmin);
        return results.stream()
                .map(r -> new WorkspaceFolderSummary(
                        r.folder().id(),
                        r.folder().alfrescoNodeId(),
                        r.folder().name(),
                        r.folder().description(),
                        r.folder().ownerUsername(),
                        r.folder().visibility(),
                        r.userRole() != null ? r.userRole() : "CONSUMER",
                        r.folder().ownerUsername().equals(username),
                        r.folder().createdAt(),
                        r.folder().updatedAt()
                ))
                .toList();
    }

    /**
     * Retrieves full folder details including permissions and activity log.
     */
    public WorkspaceFolderDetail getFolderDetail(UUID folderId, String username, boolean isAdmin) {
        WorkspaceFolder folder = folderRepo.findById(folderId)
                .orElseThrow(() -> new NoSuchElementException("Folder not found: " + folderId));

        boolean isOwner = folder.ownerUsername().equals(username);
        String userRole = null;

        if (isAdmin) {
            userRole = isOwner ? "COORDINATOR" : "ADMIN";
        } else if (isOwner) {
            userRole = "COORDINATOR";
        } else {
            var perm = permissionRepo.findPermission(folderId, username);
            if (perm.isPresent()) {
                userRole = perm.get().role();
            } else if (folder.visibility() == Visibility.PUBLIC) {
                userRole = "CONSUMER";
            } else {
                throw new AccessDeniedException("You do not have access to this private folder");
            }
        }

        List<WorkspacePermissionInfo> permissions = permissionRepo.findPermissionsForFolder(folderId).stream()
                .map(p -> new WorkspacePermissionInfo(
                        p.id(), p.username(), p.role(), p.source(), p.grantedAt(), p.grantedBy()
                ))
                .toList();

        List<WorkspaceActivityInfo> activities = activityRepo.findActivitiesForFolder(folderId, 50).stream()
                .map(a -> new WorkspaceActivityInfo(
                        a.id(), a.username(), a.activityType(), a.details(), a.occurredAt()
                ))
                .toList();

        return new WorkspaceFolderDetail(
                folder.id(),
                folder.alfrescoNodeId(),
                folder.name(),
                folder.description(),
                folder.ownerUsername(),
                folder.visibility(),
                userRole,
                isOwner,
                permissions,
                activities,
                folder.createdAt(),
                folder.updatedAt()
        );
    }

    /**
     * Deletes a folder from Alfresco and Postgres. Only the owner or an admin may delete it.
     */
    @Transactional
    public void deleteFolder(UUID folderId, String username, boolean isAdmin) {
        WorkspaceFolder folder = folderRepo.findById(folderId)
                .orElseThrow(() -> new NoSuchElementException("Folder not found: " + folderId));

        if (!isAdmin && !folder.ownerUsername().equals(username)) {
            throw new AccessDeniedException("Only the folder owner or an administrator can delete this workspace");
        }

        // Delete from Alfresco (moves to trashcan)
        try {
            alfrescoClient.deleteNode(folder.alfrescoNodeId());
        } catch (Exception e) {
            log.warn("Failed to delete Alfresco node {} during workspace cleanup: {}", folder.alfrescoNodeId(), e.getMessage());
        }

        // Delete from Postgres (cascades to permissions and activities)
        folderRepo.delete(folderId);

        log.info("Deleted workspace folder '{}' (id={}) by user '{}'", folder.name(), folderId, username);
    }

    /**
     * Creates a write access request for a public folder.
     */
    @Transactional
    public WorkspaceDtos.WorkspaceAccessRequestInfo createAccessRequest(UUID folderId, String requesterUsername, WorkspaceDtos.CreateAccessRequest request) {
        WorkspaceFolder folder = folderRepo.findById(folderId)
                .orElseThrow(() -> new NoSuchElementException("Folder not found: " + folderId));

        if (folder.ownerUsername().equalsIgnoreCase(requesterUsername)) {
            throw new IllegalArgumentException("You already own this folder");
        }

        if (folder.visibility() == Visibility.PRIVATE) {
            // Private folders cannot have open access requests
            throw new AccessDeniedException("Cannot request access to private folders");
        }

        // Check if user already has an active permission beyond Consumer
        var existingPerm = permissionRepo.findPermission(folderId, requesterUsername);
        if (existingPerm.isPresent() && !"CONSUMER".equalsIgnoreCase(existingPerm.get().role())) {
            throw new IllegalStateException("You already have " + existingPerm.get().role() + " permissions for this folder");
        }

        // Check if a pending request already exists
        var pendingReq = accessRequestRepo.findPendingByFolderAndRequester(folderId, requesterUsername);
        if (pendingReq.isPresent()) {
            throw new IllegalStateException("You already have a pending access request for this folder");
        }

        String role = (request != null && request.requestedRole() != null && !request.requestedRole().isBlank())
                ? request.requestedRole().trim().toUpperCase() : "COLLABORATOR";
        String reason = (request != null && request.reason() != null) ? request.reason().trim() : "";

        UUID reqId = accessRequestRepo.createRequest(folderId, requesterUsername, role, reason);

        // Audit log
        String detailsJson = toJson(Map.of(
                "folderName", folder.name(),
                "requestedRole", role,
                "reason", reason
        ));
        activityRepo.logActivity(folderId, requesterUsername, "ACCESS_REQUESTED", detailsJson);

        log.info("User '{}' requested role '{}' for workspace folder '{}' ({})",
                requesterUsername, role, folder.name(), folderId);

        var saved = accessRequestRepo.findById(reqId).orElseThrow();
        return toAccessRequestInfo(saved);
    }

    /**
     * Lists all pending access requests for folders owned by the given user.
     */
    public List<WorkspaceDtos.WorkspaceAccessRequestInfo> getPendingRequestsForOwner(String ownerUsername) {
        return accessRequestRepo.findPendingRequestsForOwner(ownerUsername).stream()
                .map(this::toAccessRequestInfo)
                .toList();
    }

    /**
     * Lists access requests submitted by the given user.
     */
    public List<WorkspaceDtos.WorkspaceAccessRequestInfo> getMyRequests(String requesterUsername) {
        return accessRequestRepo.findRequestsByRequester(requesterUsername).stream()
                .map(this::toAccessRequestInfo)
                .toList();
    }

    /**
     * Lists access requests for a specific folder (accessible to owner or admin).
     */
    public List<WorkspaceDtos.WorkspaceAccessRequestInfo> getRequestsForFolder(UUID folderId, String username, boolean isAdmin) {
        WorkspaceFolder folder = folderRepo.findById(folderId)
                .orElseThrow(() -> new NoSuchElementException("Folder not found: " + folderId));

        if (!isAdmin && !folder.ownerUsername().equalsIgnoreCase(username)) {
            throw new AccessDeniedException("Only the folder owner or an administrator can view folder requests");
        }

        return accessRequestRepo.findRequestsForFolder(folderId).stream()
                .map(this::toAccessRequestInfo)
                .toList();
    }

    /**
     * Reviews an access request (APPROVE or DENY).
     * If approved, updates Alfresco ACLs and grants permission in Postgres.
     */
    @Transactional
    public WorkspaceDtos.WorkspaceAccessRequestInfo reviewAccessRequest(
            UUID requestId,
            String reviewerUsername,
            boolean isAdmin,
            WorkspaceDtos.ReviewAccessRequest review
    ) {
        var req = accessRequestRepo.findById(requestId)
                .orElseThrow(() -> new NoSuchElementException("Access request not found: " + requestId));

        if (!"PENDING".equalsIgnoreCase(req.status())) {
            throw new IllegalStateException("Request is already " + req.status());
        }

        WorkspaceFolder folder = folderRepo.findById(req.workspaceFolderId())
                .orElseThrow(() -> new NoSuchElementException("Folder not found: " + req.workspaceFolderId()));

        if (!isAdmin && !folder.ownerUsername().equalsIgnoreCase(reviewerUsername)) {
            throw new AccessDeniedException("Only the folder owner or an administrator can review this request");
        }

        String action = review != null && review.action() != null ? review.action().trim().toUpperCase() : "";
        if (!"APPROVE".equals(action) && !"DENY".equals(action)) {
            throw new IllegalArgumentException("Action must be either APPROVE or DENY");
        }

        String comment = review != null && review.reviewComment() != null ? review.reviewComment().trim() : "";

        if ("APPROVE".equals(action)) {
            accessRequestRepo.updateStatus(requestId, "APPROVED", reviewerUsername, comment);

            // Grant permission in Postgres
            permissionRepo.grantPermission(
                    folder.id(),
                    req.requesterUsername(),
                    req.requestedRole(),
                    "APPROVED_REQUEST",
                    reviewerUsername
            );

            // Synchronize with Alfresco repository ACLs
            syncAlfrescoPermissions(folder);

            // Audit log
            String detailsJson = toJson(Map.of(
                    "requester", req.requesterUsername(),
                    "role", req.requestedRole(),
                    "comment", comment
            ));
            activityRepo.logActivity(folder.id(), reviewerUsername, "ACCESS_REQUEST_APPROVED", detailsJson);

            log.info("User '{}' APPROVED access request {} for user '{}' on folder '{}'",
                    reviewerUsername, requestId, req.requesterUsername(), folder.name());
        } else {
            accessRequestRepo.updateStatus(requestId, "DENIED", reviewerUsername, comment);

            // Audit log
            String detailsJson = toJson(Map.of(
                    "requester", req.requesterUsername(),
                    "comment", comment
            ));
            activityRepo.logActivity(folder.id(), reviewerUsername, "ACCESS_REQUEST_DENIED", detailsJson);

            log.info("User '{}' DENIED access request {} for user '{}' on folder '{}'",
                    reviewerUsername, requestId, req.requesterUsername(), folder.name());
        }

        var updated = accessRequestRepo.findById(requestId).orElseThrow();
        return toAccessRequestInfo(updated);
    }

    /**
     * Revokes a user's permission on a folder and synchronizes with Alfresco.
     */
    @Transactional
    public void revokePermission(UUID folderId, String targetUsername, String actorUsername, boolean isAdmin) {
        WorkspaceFolder folder = folderRepo.findById(folderId)
                .orElseThrow(() -> new NoSuchElementException("Folder not found: " + folderId));

        if (!isAdmin && !folder.ownerUsername().equalsIgnoreCase(actorUsername)) {
            throw new AccessDeniedException("Only the folder owner or an administrator can revoke permissions");
        }

        if (folder.ownerUsername().equalsIgnoreCase(targetUsername)) {
            throw new IllegalArgumentException("Cannot revoke the folder owner's permissions");
        }

        permissionRepo.revokePermission(folderId, targetUsername);

        // Synchronize updated ACLs with Alfresco
        syncAlfrescoPermissions(folder);

        // Audit log
        String detailsJson = toJson(Map.of(
                "targetUser", targetUsername,
                "revokedBy", actorUsername
        ));
        activityRepo.logActivity(folderId, actorUsername, "PERMISSION_REVOKED", detailsJson);

        log.info("Revoked permission for user '{}' on folder '{}' ({}) by '{}'",
                targetUsername, folder.name(), folderId, actorUsername);
    }

    private void syncAlfrescoPermissions(WorkspaceFolder folder) {
        List<PermissionSetting> localRules = new ArrayList<>();
        // Owner is always Coordinator
        localRules.add(new PermissionSetting(folder.ownerUsername(), "Coordinator", "ALLOWED"));

        // If public, GROUP_EVERYONE gets Consumer
        if (folder.visibility() == Visibility.PUBLIC) {
            localRules.add(new PermissionSetting(GROUP_EVERYONE, "Consumer", "ALLOWED"));
        }

        // Add explicit permissions from Postgres
        List<WorkspacePermissionRepository.WorkspacePermissionRecord> perms =
                permissionRepo.findPermissionsForFolder(folder.id());
        for (var p : perms) {
            if (!p.username().equalsIgnoreCase(folder.ownerUsername())) {
                localRules.add(new PermissionSetting(p.username(), toAlfrescoRole(p.role()), "ALLOWED"));
            }
        }

        alfrescoClient.setPermissions(folder.alfrescoNodeId(), false, localRules);
    }

    private String toAlfrescoRole(String role) {
        if (role == null) return "Collaborator";
        return switch (role.toUpperCase()) {
            case "COORDINATOR", "OWNER" -> "Coordinator";
            case "COLLABORATOR", "EDITOR", "WRITE" -> "Collaborator";
            case "CONTRIBUTOR" -> "Contributor";
            case "CONSUMER", "READ" -> "Consumer";
            default -> "Collaborator";
        };
    }

    private WorkspaceDtos.WorkspaceAccessRequestInfo toAccessRequestInfo(
            WorkspaceAccessRequestRepository.WorkspaceAccessRequestRecord r
    ) {
        return new WorkspaceDtos.WorkspaceAccessRequestInfo(
                r.id(),
                r.workspaceFolderId(),
                r.folderName(),
                r.ownerUsername(),
                r.requesterUsername(),
                r.requestedRole(),
                r.status(),
                r.reason(),
                r.createdAt(),
                r.reviewedAt(),
                r.reviewedBy(),
                r.reviewComment()
        );
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
