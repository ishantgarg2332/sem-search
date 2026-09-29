package com.dev.semsearch.search.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class WorkspaceActivityRepository {

    private final JdbcClient jdbc;

    public WorkspaceActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void logActivity(UUID folderId, String username, String activityType, String details) {
        jdbc.sql("""
                INSERT INTO ingest.workspace_activity (workspace_folder_id, username, activity_type, details, occurred_at)
                VALUES (:folderId, :username, :activityType, CAST(:details AS jsonb), now())
                """)
                .param("folderId", folderId)
                .param("username", username)
                .param("activityType", activityType)
                .param("details", details != null ? details : "{}")
                .update();
    }

    public List<WorkspaceActivityRecord> findActivitiesForFolder(UUID folderId, int limit) {
        return jdbc.sql("""
                SELECT id, workspace_folder_id, username, activity_type, details, occurred_at
                FROM ingest.workspace_activity
                WHERE workspace_folder_id = :folderId
                ORDER BY occurred_at DESC
                LIMIT :limit
                """)
                .param("folderId", folderId)
                .param("limit", limit)
                .query(this::mapRecord)
                .list();
    }

    private WorkspaceActivityRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new WorkspaceActivityRecord(
                rs.getLong("id"),
                rs.getObject("workspace_folder_id", UUID.class),
                rs.getString("username"),
                rs.getString("activity_type"),
                rs.getString("details"),
                rs.getTimestamp("occurred_at").toInstant()
        );
    }

    public record WorkspaceActivityRecord(
            Long id,
            UUID workspaceFolderId,
            String username,
            String activityType,
            String details,
            Instant occurredAt
    ) {}
}
