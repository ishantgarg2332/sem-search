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
public class WorkspacePermissionRepository {

    private final JdbcClient jdbc;

    public WorkspacePermissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void grantPermission(UUID folderId, String username, String role, String source, String grantedBy) {
        jdbc.sql("""
                INSERT INTO ingest.workspace_permission (workspace_folder_id, username, role, source, granted_at, granted_by)
                VALUES (:folderId, :username, :role, :source, now(), :grantedBy)
                ON CONFLICT (workspace_folder_id, username) DO UPDATE SET
                    role = EXCLUDED.role,
                    source = EXCLUDED.source,
                    granted_at = now(),
                    granted_by = EXCLUDED.granted_by
                """)
                .param("folderId", folderId)
                .param("username", username)
                .param("role", role)
                .param("source", source)
                .param("grantedBy", grantedBy)
                .update();
    }

    public void revokePermission(UUID folderId, String username) {
        jdbc.sql("""
                DELETE FROM ingest.workspace_permission
                WHERE workspace_folder_id = :folderId AND username = :username
                """)
                .param("folderId", folderId)
                .param("username", username)
                .update();
    }

    public List<WorkspacePermissionRecord> findPermissionsForFolder(UUID folderId) {
        return jdbc.sql("""
                SELECT id, workspace_folder_id, username, role, source, granted_at, granted_by
                FROM ingest.workspace_permission
                WHERE workspace_folder_id = :folderId
                ORDER BY granted_at ASC
                """)
                .param("folderId", folderId)
                .query(this::mapRecord)
                .list();
    }

    public Optional<WorkspacePermissionRecord> findPermission(UUID folderId, String username) {
        return jdbc.sql("""
                SELECT id, workspace_folder_id, username, role, source, granted_at, granted_by
                FROM ingest.workspace_permission
                WHERE workspace_folder_id = :folderId AND username = :username
                """)
                .param("folderId", folderId)
                .param("username", username)
                .query(this::mapRecord)
                .optional();
    }

    private WorkspacePermissionRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new WorkspacePermissionRecord(
                rs.getLong("id"),
                rs.getObject("workspace_folder_id", UUID.class),
                rs.getString("username"),
                rs.getString("role"),
                rs.getString("source"),
                rs.getTimestamp("granted_at").toInstant(),
                rs.getString("granted_by")
        );
    }

    public record WorkspacePermissionRecord(
            Long id,
            UUID workspaceFolderId,
            String username,
            String role,
            String source,
            Instant grantedAt,
            String grantedBy
    ) {}
}
