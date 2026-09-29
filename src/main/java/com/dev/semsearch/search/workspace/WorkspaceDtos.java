package com.dev.semsearch.search.workspace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class WorkspaceDtos {

    public record CreateWorkspaceRequest(
            String name,
            String description,
            WorkspaceFolder.Visibility visibility
    ) {}

    public record WorkspaceFolderSummary(
            UUID id,
            String alfrescoNodeId,
            String name,
            String description,
            String ownerUsername,
            WorkspaceFolder.Visibility visibility,
            String userRole,
            boolean isOwner,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record WorkspaceFolderDetail(
            UUID id,
            String alfrescoNodeId,
            String name,
            String description,
            String ownerUsername,
            WorkspaceFolder.Visibility visibility,
            String userRole,
            boolean isOwner,
            List<WorkspacePermissionInfo> permissions,
            List<WorkspaceActivityInfo> recentActivities,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record WorkspacePermissionInfo(
            Long id,
            String username,
            String role,
            String source,
            Instant grantedAt,
            String grantedBy
    ) {}

    public record WorkspaceActivityInfo(
            Long id,
            String username,
            String activityType,
            String details,
            Instant occurredAt
    ) {}

    public record CreateAccessRequest(
            String reason,
            String requestedRole
    ) {}

    public record ReviewAccessRequest(
            String action, // APPROVE or DENY
            String reviewComment
    ) {}

    public record WorkspaceAccessRequestInfo(
            UUID id,
            UUID folderId,
            String folderName,
            String ownerUsername,
            String requesterUsername,
            String requestedRole,
            String status,
            String reason,
            Instant createdAt,
            Instant reviewedAt,
            String reviewedBy,
            String reviewComment
    ) {}

    public record RevokePermissionRequest(
            String username
    ) {}
}
