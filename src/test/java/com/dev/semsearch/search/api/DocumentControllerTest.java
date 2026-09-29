package com.dev.semsearch.search.api;

import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.DocumentNode;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.ListResponse;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.Pagination;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.PermissionInfo;
import com.dev.semsearch.search.authority.AuthorityService;
import com.dev.semsearch.search.authority.DocumentAccessPolicy;
import com.dev.semsearch.search.config.SearchSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The document proxy acts as the Alfresco admin account, so these tests prove it refuses
 * to act for users who lack the permission themselves. Uses the real
 * {@link DocumentAccessPolicy}; only Alfresco and the group lookup are mocked.
 */
@WebMvcTest(DocumentController.class)
@Import({SearchSecurityConfig.class, DocumentAccessPolicy.class})
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlfrescoDocumentClient documentClient;

    @MockitoBean
    private AuthorityService authorityService;

    /** Readable only by GROUP_finance (alice's group), as Consumer. Created by admin. */
    private final DocumentNode financeDoc = file("finance-doc",
            new PermissionInfo("GROUP_finance", "Consumer", "ALLOWED", "local"));

    /** Readable by everyone. */
    private final DocumentNode publicDoc = file("public-doc",
            new PermissionInfo("GROUP_EVERYONE", "Consumer", "ALLOWED", "local"));

    @BeforeEach
    void setUp() {
        lenient().when(authorityService.getAuthoritiesForUser("alice"))
                .thenReturn(List.of("alice", "GROUP_finance", "GROUP_EVERYONE"));
        lenient().when(authorityService.getAuthoritiesForUser("bob"))
                .thenReturn(List.of("bob", "GROUP_EVERYONE"));
        lenient().when(documentClient.getNode("finance-doc")).thenReturn(Optional.of(financeDoc));
        lenient().when(documentClient.getNode("public-doc")).thenReturn(Optional.of(publicDoc));
    }

    @Test
    void userOutsideTheGroupGets404AndNothingIsDownloaded() throws Exception {
        mockMvc.perform(get("/api/documents/finance-doc/content").with(user("bob").roles("USER")))
                .andExpect(status().isNotFound());

        verify(documentClient, never()).copyContentToStream(anyString(), any());
    }

    @Test
    void groupMemberCanReadMetadata() throws Exception {
        mockMvc.perform(get("/api/documents/finance-doc").with(user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("finance-doc"));
    }

    @Test
    void consumerCannotDelete() throws Exception {
        mockMvc.perform(delete("/api/documents/finance-doc").with(user("alice").roles("USER")))
                .andExpect(status().isForbidden());

        verify(documentClient, never()).deleteNode(anyString());
    }

    @Test
    void userWhoCannotReadGets404OnDeleteToo() throws Exception {
        mockMvc.perform(delete("/api/documents/finance-doc").with(user("bob").roles("USER")))
                .andExpect(status().isNotFound());

        verify(documentClient, never()).deleteNode(anyString());
    }

    @Test
    void adminCanDelete() throws Exception {
        mockMvc.perform(delete("/api/documents/finance-doc").with(user("admin").roles("USER", "ADMIN")))
                .andExpect(status().isOk());

        verify(documentClient).deleteNode("finance-doc");
    }

    @Test
    void folderListingHidesFilesTheUserCannotRead() throws Exception {
        DocumentNode root = new DocumentNode("-root-", "Company Home", "cm:folder", true, false,
                null, 0L, null, null, null, null, null, "/",
                List.of(new PermissionInfo("GROUP_EVERYONE", "Consumer", "ALLOWED", "local")),
                true, "admin");
        when(documentClient.getNode("-root-")).thenReturn(Optional.of(root));
        when(documentClient.listChildren("-root-", 0, 25)).thenReturn(new ListResponse(
                List.of(financeDoc, publicDoc), new Pagination(2, false, 0, 25)));

        mockMvc.perform(get("/api/documents").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].id").value("public-doc"));
    }

    private static DocumentNode file(String id, PermissionInfo... perms) {
        return new DocumentNode(id, id + ".pdf", "cm:content", false, true,
                "application/pdf", 1234L, null, null, "Administrator", null, null, "/",
                List.of(perms), false, "admin");
    }
}
