package com.dev.semsearch.search.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkspaceFolderRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFolderRepository.class);

    private final JdbcClient jdbc;

    public WorkspaceFolderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public WorkspaceFolder save(WorkspaceFolder folder) {
        jdbc.sql("""
                INSERT INTO ingest.workspace_folder (id, alfresco_node_id, name, description, owner_username, visibility, created_at, updated_at)
                VALUES (:id, :alfrescoNodeId, :name, :description, :ownerUsername, :visibility, :createdAt, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    visibility = EXCLUDED.visibility,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("id", folder.id())
                .param("alfrescoNodeId", folder.alfrescoNodeId())
                .param("name", folder.name())
                .param("description", folder.description())
                .param("ownerUsername", folder.ownerUsername())
                .param("visibility", folder.visibility().name())
                .param("createdAt", Timestamp.from(folder.createdAt()))
                .param("updatedAt", Timestamp.from(folder.updatedAt()))
                .update();

        return folder;
    }

    public Optional<WorkspaceFolder> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, alfresco_node_id, name, description, owner_username, visibility, created_at, updated_at
                FROM ingest.workspace_folder
                WHERE id = :id
                """)
                .param("id", id)
                .query(this::mapFolder)
                .optional();
    }

    public Optional<WorkspaceFolder> findByAlfrescoNodeId(String alfrescoNodeId) {
        return jdbc.sql("""
                SELECT id, alfresco_node_id, name, description, owner_username, visibility, created_at, updated_at
                FROM ingest.workspace_folder
                WHERE alfresco_node_id = :alfrescoNodeId
                """)
                .param("alfrescoNodeId", alfrescoNodeId)
                .query(this::mapFolder)
                .optional();
    }

    public List<WorkspaceFolderWithRole> findAccessibleFolders(String username, boolean isAdmin) {
        if (isAdmin) {
            return jdbc.sql("""
                    SELECT f.id, f.alfresco_node_id, f.name, f.description, f.owner_username, f.visibility, f.created_at, f.updated_at,
                           CASE WHEN f.owner_username = :username THEN 'COORDINATOR' ELSE 'ADMIN' END as user_role
                    FROM ingest.workspace_folder f
                    ORDER BY f.created_at DESC
                    """)
                    .param("username", username)
                    .query((rs, rowNum) -> new WorkspaceFolderWithRole(mapFolder(rs, rowNum), rs.getString("user_role")))
                    .list();
        }

        return jdbc.sql("""
                SELECT f.id, f.alfresco_node_id, f.name, f.description, f.owner_username, f.visibility, f.created_at, f.updated_at,
                       COALESCE(p.role, CASE WHEN f.owner_username = :username THEN 'COORDINATOR' WHEN f.visibility = 'PUBLIC' THEN 'CONSUMER' ELSE NULL END) as user_role
                FROM ingest.workspace_folder f
                LEFT JOIN ingest.workspace_permission p ON p.workspace_folder_id = f.id AND p.username = :username
                WHERE f.owner_username = :username
                   OR f.visibility = 'PUBLIC'
                   OR p.id IS NOT NULL
                ORDER BY f.created_at DESC
                """)
                .param("username", username)
                .query((rs, rowNum) -> new WorkspaceFolderWithRole(mapFolder(rs, rowNum), rs.getString("user_role")))
                .list();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM ingest.workspace_folder WHERE id = :id")
                .param("id", id)
                .update();
    }

    private WorkspaceFolder mapFolder(ResultSet rs, int rowNum) throws SQLException {
        return new WorkspaceFolder(
                rs.getObject("id", UUID.class),
                rs.getString("alfresco_node_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("owner_username"),
                WorkspaceFolder.Visibility.valueOf(rs.getString("visibility")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    public record WorkspaceFolderWithRole(WorkspaceFolder folder, String userRole) {}
}
