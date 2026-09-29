package com.dev.semsearch.search.workspace;

import com.dev.semsearch.search.workspace.WorkspaceDtos.CreateWorkspaceRequest;
import com.dev.semsearch.search.workspace.WorkspaceDtos.WorkspaceFolderDetail;
import com.dev.semsearch.search.workspace.WorkspaceDtos.WorkspaceFolderSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * Creates a new public or private workspace folder with synchronized Alfresco ACLs.
     */
    @PostMapping
    public ResponseEntity<?> createFolder(@RequestBody CreateWorkspaceRequest request, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }

        try {
            WorkspaceFolderSummary created = workspaceService.createFolder(authentication.getName(), request);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create workspace: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create workspace: " + e.getMessage()));
        }
    }

    /**
     * Lists all folders accessible to the current user (owned, public, or shared).
     */
    @GetMapping
    public ResponseEntity<List<WorkspaceFolderSummary>> listFolders(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String username = authentication.getName();
        boolean isAdmin = isAdmin(authentication);

        List<WorkspaceFolderSummary> folders = workspaceService.listAccessibleFolders(username, isAdmin);
        return ResponseEntity.ok(folders);
    }

    /**
     * Retrieves folder details, member permissions, and activity timeline.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getFolderDetail(@PathVariable UUID id, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            WorkspaceFolderDetail detail = workspaceService.getFolderDetail(id, authentication.getName(), isAdmin(authentication));
            return ResponseEntity.ok(detail);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Deletes a workspace folder. Only the owner or an administrator is permitted.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFolder(@PathVariable UUID id, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            workspaceService.deleteFolder(id, authentication.getName(), isAdmin(authentication));
            return ResponseEntity.ok(Map.of("message", "Folder deleted successfully"));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Creates an access request for write/edit permissions on a workspace folder.
     */
    @PostMapping("/{id}/requests")
    public ResponseEntity<?> requestAccess(
            @PathVariable UUID id,
            @RequestBody(required = false) WorkspaceDtos.CreateAccessRequest request,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            var created = workspaceService.createAccessRequest(id, authentication.getName(), request);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Lists pending access requests for folders owned by the logged-in user.
     */
    @GetMapping("/requests/pending")
    public ResponseEntity<?> getPendingRequests(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        var list = workspaceService.getPendingRequestsForOwner(authentication.getName());
        return ResponseEntity.ok(list);
    }

    /**
     * Lists access requests submitted by the logged-in user.
     */
    @GetMapping("/requests/mine")
    public ResponseEntity<?> getMyRequests(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        var list = workspaceService.getMyRequests(authentication.getName());
        return ResponseEntity.ok(list);
    }

    /**
     * Lists access requests for a specific folder.
     */
    @GetMapping("/{id}/requests")
    public ResponseEntity<?> getFolderRequests(@PathVariable UUID id, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            var list = workspaceService.getRequestsForFolder(id, authentication.getName(), isAdmin(authentication));
            return ResponseEntity.ok(list);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Reviews an access request (APPROVE or DENY).
     */
    @PostMapping("/requests/{requestId}/review")
    public ResponseEntity<?> reviewRequest(
            @PathVariable UUID requestId,
            @RequestBody WorkspaceDtos.ReviewAccessRequest review,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            var reviewed = workspaceService.reviewAccessRequest(
                    requestId,
                    authentication.getName(),
                    isAdmin(authentication),
                    review
            );
            return ResponseEntity.ok(reviewed);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Revokes a user's permissions on a workspace folder.
     */
    @DeleteMapping("/{id}/permissions/{targetUser}")
    public ResponseEntity<?> revokePermission(
            @PathVariable UUID id,
            @PathVariable String targetUser,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            workspaceService.revokePermission(id, targetUser, authentication.getName(), isAdmin(authentication));
            return ResponseEntity.ok(Map.of("message", "Permission revoked for " + targetUser));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
