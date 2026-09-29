package com.dev.semsearch.search.workspace;

import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.DocumentNode;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.PermissionSetting;
import com.dev.semsearch.search.workspace.WorkspaceDtos.CreateWorkspaceRequest;
import com.dev.semsearch.search.workspace.WorkspaceDtos.WorkspaceFolderDetail;
import com.dev.semsearch.search.workspace.WorkspaceDtos.WorkspaceFolderSummary;
import com.dev.semsearch.search.workspace.WorkspaceFolder.Visibility;
import com.dev.semsearch.search.workspace.WorkspaceFolderRepository.WorkspaceFolderWithRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private WorkspaceFolderRepository folderRepo;

    @Mock
    private WorkspacePermissionRepository permissionRepo;

    @Mock
    private WorkspaceAccessRequestRepository accessRequestRepo;

    @Mock
    private WorkspaceActivityRepository activityRepo;

    @Mock
    private AlfrescoDocumentClient alfrescoClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkspaceService workspaceService;

    @Captor
    private ArgumentCaptor<List<PermissionSetting>> permissionsCaptor;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(
                folderRepo,
                permissionRepo,
                accessRequestRepo,
                activityRepo,
                alfrescoClient,
                objectMapper
        );
    }

    @Test
    void createPublicFolderConfiguresOwnerCoordinatorAndEveryoneConsumer() {
        DocumentNode mockAlfrescoNode = new DocumentNode(
                "alfresco-node-123", "Public Docs", "cm:folder", true, false,
                null, 0L, null, null, null, null, null, "/",
                List.of(), false, "abc"
        );

        when(alfrescoClient.createFolder("-root-", "Public Docs", "Public Docs", "Public description"))
                .thenReturn(mockAlfrescoNode);

        CreateWorkspaceRequest request = new CreateWorkspaceRequest(
                "Public Docs", "Public description", Visibility.PUBLIC);

        WorkspaceFolderSummary summary = workspaceService.createFolder("abc", request);

        assertThat(summary).isNotNull();
        assertThat(summary.name()).isEqualTo("Public Docs");
        assertThat(summary.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(summary.ownerUsername()).isEqualTo("abc");
        assertThat(summary.isOwner()).isTrue();
        assertThat(summary.userRole()).isEqualTo("COORDINATOR");

        // Verify Alfresco ACLs set inheritance to false and granted GROUP_EVERYONE Consumer
        verify(alfrescoClient).setPermissions(eq("alfresco-node-123"), eq(false), permissionsCaptor.capture());
        List<PermissionSetting> perms = permissionsCaptor.getValue();
        assertThat(perms).hasSize(2);
        assertThat(perms).anyMatch(p -> p.authorityId().equals("abc") && p.role().equals("Coordinator"));
        assertThat(perms).anyMatch(p -> p.authorityId().equals("GROUP_EVERYONE") && p.role().equals("Consumer"));

        // Verify persistence & activity logged
        verify(folderRepo).save(any(WorkspaceFolder.class));
        verify(permissionRepo).grantPermission(any(UUID.class), eq("abc"), eq("COORDINATOR"), eq("OWNER"), eq("abc"));
        verify(activityRepo).logActivity(any(UUID.class), eq("abc"), eq("FOLDER_CREATED"), anyString());
    }

    @Test
    void createPrivateFolderOnlyGrantsOwnerCoordinatorWithoutEveryoneGroup() {
        DocumentNode mockAlfrescoNode = new DocumentNode(
                "alfresco-node-private", "Confidential", "cm:folder", true, false,
                null, 0L, null, null, null, null, null, "/",
                List.of(), false, "abc"
        );

        when(alfrescoClient.createFolder("-root-", "Confidential", "Confidential", "Secret files"))
                .thenReturn(mockAlfrescoNode);

        CreateWorkspaceRequest request = new CreateWorkspaceRequest(
                "Confidential", "Secret files", Visibility.PRIVATE);

        WorkspaceFolderSummary summary = workspaceService.createFolder("abc", request);

        assertThat(summary.visibility()).isEqualTo(Visibility.PRIVATE);

        // Verify Alfresco ACL only contains the owner, NO GROUP_EVERYONE
        verify(alfrescoClient).setPermissions(eq("alfresco-node-private"), eq(false), permissionsCaptor.capture());
        List<PermissionSetting> perms = permissionsCaptor.getValue();
        assertThat(perms).hasSize(1);
        assertThat(perms.get(0).authorityId()).isEqualTo("abc");
        assertThat(perms.get(0).role()).isEqualTo("Coordinator");
    }

    @Test
    void listAccessibleFoldersReturnsFoldersForUser() {
        WorkspaceFolder folder = new WorkspaceFolder(
                UUID.randomUUID(), "node-1", "Team Docs", "Desc", "abc", Visibility.PUBLIC, Instant.now(), Instant.now()
        );
        when(folderRepo.findAccessibleFolders("bob", false))
                .thenReturn(List.of(new WorkspaceFolderWithRole(folder, "CONSUMER")));

        List<WorkspaceFolderSummary> list = workspaceService.listAccessibleFolders("bob", false);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("Team Docs");
        assertThat(list.get(0).userRole()).isEqualTo("CONSUMER");
        assertThat(list.get(0).isOwner()).isFalse();
    }

    @Test
    void getFolderDetailForUnauthorizedUserOnPrivateFolderThrowsAccessDenied() {
        UUID folderId = UUID.randomUUID();
        WorkspaceFolder privateFolder = new WorkspaceFolder(
                folderId, "node-p", "Secret", "Desc", "alice", Visibility.PRIVATE, Instant.now(), Instant.now()
        );

        when(folderRepo.findById(folderId)).thenReturn(Optional.of(privateFolder));
        when(permissionRepo.findPermission(folderId, "bob")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workspaceService.getFolderDetail(folderId, "bob", false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("access to this private folder");
    }

    @Test
    void deleteFolderByNonOwnerThrowsAccessDenied() {
        UUID folderId = UUID.randomUUID();
        WorkspaceFolder folder = new WorkspaceFolder(
                folderId, "node-x", "Project", "Desc", "alice", Visibility.PUBLIC, Instant.now(), Instant.now()
        );

        when(folderRepo.findById(folderId)).thenReturn(Optional.of(folder));

        assertThatThrownBy(() -> workspaceService.deleteFolder(folderId, "bob", false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only the folder owner or an administrator can delete");
    }

    @Test
    void deleteFolderByOwnerSucceedsAndCleansUpAlfrescoAndDb() {
        UUID folderId = UUID.randomUUID();
        WorkspaceFolder folder = new WorkspaceFolder(
                folderId, "node-x", "Project", "Desc", "alice", Visibility.PUBLIC, Instant.now(), Instant.now()
        );

        when(folderRepo.findById(folderId)).thenReturn(Optional.of(folder));

        workspaceService.deleteFolder(folderId, "alice", false);

        verify(alfrescoClient).deleteNode("node-x");
        verify(folderRepo).delete(folderId);
    }

    @Test
    void createAccessRequestSucceedsOnPublicFolder() {
        UUID folderId = UUID.randomUUID();
        UUID reqId = UUID.randomUUID();
        WorkspaceFolder folder = new WorkspaceFolder(
                folderId, "node-pub", "Open Data", "Desc", "alice", Visibility.PUBLIC, Instant.now(), Instant.now()
        );

        when(folderRepo.findById(folderId)).thenReturn(Optional.of(folder));
        when(permissionRepo.findPermission(folderId, "bob")).thenReturn(Optional.empty());
        when(accessRequestRepo.findPendingByFolderAndRequester(folderId, "bob")).thenReturn(Optional.empty());
        when(accessRequestRepo.createRequest(folderId, "bob", "COLLABORATOR", "Need write access"))
                .thenReturn(reqId);

        var savedRecord = new WorkspaceAccessRequestRepository.WorkspaceAccessRequestRecord(
                reqId, folderId, "Open Data", "alice", "bob", "COLLABORATOR", "PENDING",
                "Need write access", Instant.now(), null, null, null
        );
        when(accessRequestRepo.findById(reqId)).thenReturn(Optional.of(savedRecord));

        var req = new WorkspaceDtos.CreateAccessRequest("Need write access", "COLLABORATOR");
        var result = workspaceService.createAccessRequest(folderId, "bob", req);

        assertThat(result.id()).isEqualTo(reqId);
        assertThat(result.requesterUsername()).isEqualTo("bob");
        assertThat(result.status()).isEqualTo("PENDING");
        verify(activityRepo).logActivity(eq(folderId), eq("bob"), eq("ACCESS_REQUESTED"), anyString());
    }

    @Test
    void createAccessRequestFailsWhenUserIsOwner() {
        UUID folderId = UUID.randomUUID();
        WorkspaceFolder folder = new WorkspaceFolder(
                folderId, "node-pub", "Open Data", "Desc", "alice", Visibility.PUBLIC, Instant.now(), Instant.now()
        );
        when(folderRepo.findById(folderId)).thenReturn(Optional.of(folder));

        var req = new WorkspaceDtos.CreateAccessRequest("Need access", "COLLABORATOR");
        assertThatThrownBy(() -> workspaceService.createAccessRequest(folderId, "alice", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already own this folder");
    }

    @Test
    void reviewAccessRequestApproveGrantsPermissionAndSyncsAlfresco() {
        UUID folderId = UUID.randomUUID();
        UUID reqId = UUID.randomUUID();
        WorkspaceFolder folder = new WorkspaceFolder(
                folderId, "node-pub", "Open Data", "Desc", "alice", Visibility.PUBLIC, Instant.now(), Instant.now()
        );

        var pendingReq = new WorkspaceAccessRequestRepository.WorkspaceAccessRequestRecord(
                reqId, folderId, "Open Data", "alice", "bob", "COLLABORATOR", "PENDING",
                "Need write", Instant.now(), null, null, null
        );
        var approvedReq = new WorkspaceAccessRequestRepository.WorkspaceAccessRequestRecord(
                reqId, folderId, "Open Data", "alice", "bob", "COLLABORATOR", "APPROVED",
                "Need write", Instant.now(), Instant.now(), "alice", "Approved for sprint"
        );

        when(accessRequestRepo.findById(reqId)).thenReturn(Optional.of(pendingReq), Optional.of(approvedReq));
        when(folderRepo.findById(folderId)).thenReturn(Optional.of(folder));
        when(permissionRepo.findPermissionsForFolder(folderId)).thenReturn(List.of(
                new WorkspacePermissionRepository.WorkspacePermissionRecord(1L, folderId, "alice", "COORDINATOR", "OWNER", Instant.now(), "alice"),
                new WorkspacePermissionRepository.WorkspacePermissionRecord(2L, folderId, "bob", "COLLABORATOR", "APPROVED_REQUEST", Instant.now(), "alice")
        ));

        var review = new WorkspaceDtos.ReviewAccessRequest("APPROVE", "Approved for sprint");
        var result = workspaceService.reviewAccessRequest(reqId, "alice", false, review);

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(permissionRepo).grantPermission(folderId, "bob", "COLLABORATOR", "APPROVED_REQUEST", "alice");
        verify(alfrescoClient).setPermissions(eq("node-pub"), eq(false), permissionsCaptor.capture());

        List<PermissionSetting> synced = permissionsCaptor.getValue();
        assertThat(synced).anyMatch(p -> p.authorityId().equals("bob") && p.role().equals("Collaborator"));
        assertThat(synced).anyMatch(p -> p.authorityId().equals("GROUP_EVERYONE") && p.role().equals("Consumer"));
    }

    @Test
    void revokePermissionRemovesPermissionAndSyncsAlfresco() {
        UUID folderId = UUID.randomUUID();
        WorkspaceFolder folder = new WorkspaceFolder(
                folderId, "node-pub", "Open Data", "Desc", "alice", Visibility.PUBLIC, Instant.now(), Instant.now()
        );

        when(folderRepo.findById(folderId)).thenReturn(Optional.of(folder));
        when(permissionRepo.findPermissionsForFolder(folderId)).thenReturn(List.of(
                new WorkspacePermissionRepository.WorkspacePermissionRecord(1L, folderId, "alice", "COORDINATOR", "OWNER", Instant.now(), "alice")
        ));

        workspaceService.revokePermission(folderId, "bob", "alice", false);

        verify(permissionRepo).revokePermission(folderId, "bob");
        verify(alfrescoClient).setPermissions(eq("node-pub"), eq(false), permissionsCaptor.capture());
        List<PermissionSetting> synced = permissionsCaptor.getValue();
        // bob should not be in the synced list
        assertThat(synced).noneMatch(p -> p.authorityId().equals("bob"));
        verify(activityRepo).logActivity(eq(folderId), eq("alice"), eq("PERMISSION_REVOKED"), anyString());
    }
}
