package com.dev.semsearch.search.workspace;

import com.dev.semsearch.search.config.SearchSecurityConfig;
import com.dev.semsearch.search.workspace.WorkspaceDtos.CreateWorkspaceRequest;
import com.dev.semsearch.search.workspace.WorkspaceDtos.WorkspaceFolderDetail;
import com.dev.semsearch.search.workspace.WorkspaceDtos.WorkspaceFolderSummary;
import com.dev.semsearch.search.workspace.WorkspaceFolder.Visibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkspaceController.class)
@Import(SearchSecurityConfig.class)
class WorkspaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkspaceService workspaceService;

    @Test
    void createFolderReturns201Created() throws Exception {
        UUID id = UUID.randomUUID();
        WorkspaceFolderSummary summary = new WorkspaceFolderSummary(
                id, "alfresco-123", "Marketing Docs", "Campaigns", "abc", Visibility.PUBLIC, "COORDINATOR", true, Instant.now(), Instant.now()
        );

        when(workspaceService.createFolder(eq("abc"), any(CreateWorkspaceRequest.class)))
                .thenReturn(summary);

        CreateWorkspaceRequest request = new CreateWorkspaceRequest("Marketing Docs", "Campaigns", Visibility.PUBLIC);

        mockMvc.perform(post("/api/workspaces")
                        .with(user("abc").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Marketing Docs"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.ownerUsername").value("abc"));
    }

    @Test
    void createFolderWithoutAuthReturns401() throws Exception {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest("My Folder", "Desc", Visibility.PRIVATE);

        mockMvc.perform(post("/api/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listFoldersReturnsAccessibleList() throws Exception {
        UUID id = UUID.randomUUID();
        WorkspaceFolderSummary summary = new WorkspaceFolderSummary(
                id, "alfresco-123", "Marketing Docs", "Campaigns", "abc", Visibility.PUBLIC, "CONSUMER", false, Instant.now(), Instant.now()
        );

        when(workspaceService.listAccessibleFolders(eq("bob"), eq(false)))
                .thenReturn(List.of(summary));

        mockMvc.perform(get("/api/workspaces").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Marketing Docs"));
    }

    @Test
    void getFolderDetailReturnsFolderInfo() throws Exception {
        UUID id = UUID.randomUUID();
        WorkspaceFolderDetail detail = new WorkspaceFolderDetail(
                id, "alfresco-123", "Marketing Docs", "Campaigns", "abc", Visibility.PUBLIC, "COORDINATOR", true,
                List.of(), List.of(), Instant.now(), Instant.now()
        );

        when(workspaceService.getFolderDetail(eq(id), eq("abc"), eq(false)))
                .thenReturn(detail);

        mockMvc.perform(get("/api/workspaces/" + id).with(user("abc").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Marketing Docs"))
                .andExpect(jsonPath("$.ownerUsername").value("abc"))
                .andExpect(jsonPath("$.userRole").value("COORDINATOR"));
    }

    @Test
    void getFolderDetailNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(workspaceService.getFolderDetail(eq(id), eq("abc"), eq(false)))
                .thenThrow(new NoSuchElementException("Folder not found"));

        mockMvc.perform(get("/api/workspaces/" + id).with(user("abc").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Folder not found"));
    }

    @Test
    void deleteFolderByOwnerReturns200() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/workspaces/" + id).with(user("abc").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Folder deleted successfully"));
    }

    @Test
    void deleteFolderForbiddenReturns403() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new AccessDeniedException("Only owner can delete"))
                .when(workspaceService).deleteFolder(eq(id), eq("bob"), eq(false));

        mockMvc.perform(delete("/api/workspaces/" + id).with(user("bob").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only owner can delete"));
    }

    @Test
    void requestAccessReturns201Created() throws Exception {
        UUID folderId = UUID.randomUUID();
        UUID reqId = UUID.randomUUID();
        var info = new WorkspaceDtos.WorkspaceAccessRequestInfo(
                reqId, folderId, "Open Data", "alice", "bob", "COLLABORATOR", "PENDING",
                "Need write access", Instant.now(), null, null, null
        );

        when(workspaceService.createAccessRequest(eq(folderId), eq("bob"), any(WorkspaceDtos.CreateAccessRequest.class)))
                .thenReturn(info);

        var req = new WorkspaceDtos.CreateAccessRequest("Need write access", "COLLABORATOR");

        mockMvc.perform(post("/api/workspaces/" + folderId + "/requests")
                        .with(user("bob").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(reqId.toString()))
                .andExpect(jsonPath("$.requesterUsername").value("bob"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getPendingRequestsReturnsList() throws Exception {
        UUID folderId = UUID.randomUUID();
        UUID reqId = UUID.randomUUID();
        var info = new WorkspaceDtos.WorkspaceAccessRequestInfo(
                reqId, folderId, "Open Data", "alice", "bob", "COLLABORATOR", "PENDING",
                "Need write access", Instant.now(), null, null, null
        );

        when(workspaceService.getPendingRequestsForOwner("alice"))
                .thenReturn(List.of(info));

        mockMvc.perform(get("/api/workspaces/requests/pending")
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requesterUsername").value("bob"));
    }

    @Test
    void reviewRequestReturns200WithUpdatedStatus() throws Exception {
        UUID reqId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        var info = new WorkspaceDtos.WorkspaceAccessRequestInfo(
                reqId, folderId, "Open Data", "alice", "bob", "COLLABORATOR", "APPROVED",
                "Need write access", Instant.now(), Instant.now(), "alice", "Approved"
        );

        when(workspaceService.reviewAccessRequest(eq(reqId), eq("alice"), eq(false), any(WorkspaceDtos.ReviewAccessRequest.class)))
                .thenReturn(info);

        var review = new WorkspaceDtos.ReviewAccessRequest("APPROVE", "Approved");

        mockMvc.perform(post("/api/workspaces/requests/" + reqId + "/review")
                        .with(user("alice").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(review)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedBy").value("alice"));
    }

    @Test
    void revokePermissionReturns200() throws Exception {
        UUID folderId = UUID.randomUUID();

        mockMvc.perform(delete("/api/workspaces/" + folderId + "/permissions/bob")
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Permission revoked for bob"));
    }
}
