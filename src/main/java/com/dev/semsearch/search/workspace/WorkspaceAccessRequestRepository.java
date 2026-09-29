package com.dev.semsearch.search.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkspaceAccessRequestRepository {

    private final JdbcClient jdbc;

    public WorkspaceAccessRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID createRequest(UUID folderId, String requesterUsername, String requestedRole, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ingest.workspace_access_request
                    (id, workspace_folder_id, requester_username, requested_role, status, reason, created_at)
                VALUES
                    (:id, :folderId, :requester, :requestedRole, 'PENDING', :reason, now())
                """)
                .param("id", id)
                .param("folderId", folderId)
                .param("requester", requesterUsername)
                .param("requestedRole", requestedRole != null ? requestedRole : "COLLABORATOR")
                .param("reason", reason)
                .update();
        return id;
    }

    public Optional<WorkspaceAccessRequestRecord> findById(UUID requestId) {
        return jdbc.sql("""
                SELECT r.id, r.workspace_folder_id, f.name AS folder_name, f.owner_username,
                       r.requester_username, r.requested_role, r.status, r.reason,
                       r.created_at, r.reviewed_at, r.reviewed_by, r.review_comment
                FROM ingest.workspace_access_request r
                JOIN ingest.workspace_folder f ON f.id = r.workspace_folder_id
                WHERE r.id = :id
                """)
                .param("id", requestId)
                .query(this::mapRecord)
                .optional();
    }

    public Optional<WorkspaceAccessRequestRecord> findPendingByFolderAndRequester(UUID folderId, String requesterUsername) {
        return jdbc.sql("""
                SELECT r.id, r.workspace_folder_id, f.name AS folder_name, f.owner_username,
                       r.requester_username, r.requested_role, r.status, r.reason,
                       r.created_at, r.reviewed_at, r.reviewed_by, r.review_comment
                FROM ingest.workspace_access_request r
                JOIN ingest.workspace_folder f ON f.id = r.workspace_folder_id
                WHERE r.workspace_folder_id = :folderId
                  AND r.requester_username = :requester
                  AND r.status = 'PENDING'
                """)
                .param("folderId", folderId)
                .param("requester", requesterUsername)
                .query(this::mapRecord)
                .optional();
    }

    public List<WorkspaceAccessRequestRecord> findPendingRequestsForOwner(String ownerUsername) {
        return jdbc.sql("""
                SELECT r.id, r.workspace_folder_id, f.name AS folder_name, f.owner_username,
                       r.requester_username, r.requested_role, r.status, r.reason,
                       r.created_at, r.reviewed_at, r.reviewed_by, r.review_comment
                FROM ingest.workspace_access_request r
                JOIN ingest.workspace_folder f ON f.id = r.workspace_folder_id
                WHERE f.owner_username = :owner
                  AND r.status = 'PENDING'
                ORDER BY r.created_at DESC
                """)
                .param("owner", ownerUsername)
                .query(this::mapRecord)
                .list();
    }

    public List<WorkspaceAccessRequestRecord> findRequestsByRequester(String requesterUsername) {
        return jdbc.sql("""
                SELECT r.id, r.workspace_folder_id, f.name AS folder_name, f.owner_username,
                       r.requester_username, r.requested_role, r.status, r.reason,
                       r.created_at, r.reviewed_at, r.reviewed_by, r.review_comment
                FROM ingest.workspace_access_request r
                JOIN ingest.workspace_folder f ON f.id = r.workspace_folder_id
                WHERE r.requester_username = :requester
                ORDER BY r.created_at DESC
                """)
                .param("requester", requesterUsername)
                .query(this::mapRecord)
                .list();
    }

    public List<WorkspaceAccessRequestRecord> findRequestsForFolder(UUID folderId) {
        return jdbc.sql("""
                SELECT r.id, r.workspace_folder_id, f.name AS folder_name, f.owner_username,
                       r.requester_username, r.requested_role, r.status, r.reason,
                       r.created_at, r.reviewed_at, r.reviewed_by, r.review_comment
                FROM ingest.workspace_access_request r
                JOIN ingest.workspace_folder f ON f.id = r.workspace_folder_id
                WHERE r.workspace_folder_id = :folderId
                ORDER BY r.created_at DESC
                """)
                .param("folderId", folderId)
                .query(this::mapRecord)
                .list();
    }

    public void updateStatus(UUID requestId, String status, String reviewedBy, String reviewComment) {
        jdbc.sql("""
                UPDATE ingest.workspace_access_request
                SET status = :status,
                    reviewed_by = :reviewedBy,
                    review_comment = :reviewComment,
                    reviewed_at = now()
                WHERE id = :id
                """)
                .param("id", requestId)
                .param("status", status)
                .param("reviewedBy", reviewedBy)
                .param("reviewComment", reviewComment)
                .update();
    }

    private WorkspaceAccessRequestRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new WorkspaceAccessRequestRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_folder_id", UUID.class),
                rs.getString("folder_name"),
                rs.getString("owner_username"),
                rs.getString("requester_username"),
                rs.getString("requested_role"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getString("reviewed_by"),
                rs.getString("review_comment")
        );
    }

    public record WorkspaceAccessRequestRecord(
            UUID id,
            UUID workspaceFolderId,
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
}
