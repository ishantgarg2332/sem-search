package com.dev.semsearch.search.authority;

import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.DocumentNode;
import com.dev.semsearch.common.alfresco.AlfrescoDocumentClient.PermissionInfo;
import com.dev.semsearch.search.authority.DocumentAccessPolicy.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DocumentAccessPolicyTest {

    @Mock
    private AuthorityService authorityService;

    private DocumentAccessPolicy policy;

    private final Authentication alice = user("alice", "ROLE_USER");
    private final Authentication bob = user("bob", "ROLE_USER");
    private final Authentication admin = user("admin", "ROLE_USER", "ROLE_ADMIN");

    @BeforeEach
    void setUp() {
        policy = new DocumentAccessPolicy(authorityService);
        lenient().when(authorityService.getAuthoritiesForUser("alice"))
                .thenReturn(List.of("alice", "GROUP_finance", "GROUP_EVERYONE"));
        lenient().when(authorityService.getAuthoritiesForUser("bob"))
                .thenReturn(List.of("bob", "GROUP_engineering", "GROUP_EVERYONE"));
    }

    @Test
    void groupConsumerCanReadButNotChangeOrDelete() {
        DocumentNode doc = node("owner", true, local("GROUP_finance", "Consumer", "ALLOWED"));

        assertThat(policy.isAllowed(alice, doc, Operation.READ)).isTrue();
        assertThat(policy.isAllowed(alice, doc, Operation.UPDATE_CONTENT)).isFalse();
        assertThat(policy.isAllowed(alice, doc, Operation.DELETE)).isFalse();
    }

    @Test
    void userOutsideTheGroupCannotRead() {
        DocumentNode doc = node("owner", true, local("GROUP_finance", "Consumer", "ALLOWED"));

        assertThat(policy.isAllowed(bob, doc, Operation.READ)).isFalse();
    }

    @Test
    void inheritedEntriesIgnoredWhenInheritanceDisabled() {
        DocumentNode doc = node("owner", false, inherited("GROUP_EVERYONE", "Consumer", "ALLOWED"));

        assertThat(policy.isAllowed(bob, doc, Operation.READ)).isFalse();
    }

    @Test
    void inheritedEntriesCountWhenInheritanceEnabled() {
        DocumentNode doc = node("owner", true, inherited("GROUP_EVERYONE", "Consumer", "ALLOWED"));

        assertThat(policy.isAllowed(bob, doc, Operation.READ)).isTrue();
    }

    @Test
    void denyEntryBlocksEvenWhenAnotherEntryAllows() {
        DocumentNode doc = node("owner", true,
                inherited("GROUP_EVERYONE", "Consumer", "ALLOWED"),
                local("bob", "Consumer", "DENIED"));

        assertThat(policy.isAllowed(bob, doc, Operation.READ)).isFalse();
    }

    @Test
    void ownerHasFullAccessWithoutAnyEntries() {
        DocumentNode doc = node("alice", true);

        assertThat(policy.isAllowed(alice, doc, Operation.READ)).isTrue();
        assertThat(policy.isAllowed(alice, doc, Operation.DELETE)).isTrue();
    }

    @Test
    void appAdminHasFullAccess() {
        DocumentNode doc = node("owner", false);

        assertThat(policy.isAllowed(admin, doc, Operation.DELETE)).isTrue();
    }

    @Test
    void contributorCanAddToFolderButCoordinatorIsNeededToDelete() {
        DocumentNode folder = node("owner", true, local("GROUP_engineering", "Contributor", "ALLOWED"));

        assertThat(policy.isAllowed(bob, folder, Operation.CREATE_CHILDREN)).isTrue();
        assertThat(policy.isAllowed(bob, folder, Operation.DELETE)).isFalse();
    }

    @Test
    void unknownCustomRoleDenies() {
        DocumentNode doc = node("owner", true, local("GROUP_engineering", "MyCustomRole", "ALLOWED"));

        assertThat(policy.isAllowed(bob, doc, Operation.READ)).isFalse();
    }

    @Test
    void missingAuthenticationDenies() {
        DocumentNode doc = node("owner", true, inherited("GROUP_EVERYONE", "Consumer", "ALLOWED"));

        assertThat(policy.isAllowed(null, doc, Operation.READ)).isFalse();
    }

    // ── helpers ──

    private static Authentication user(String name, String... roles) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", AuthorityUtils.createAuthorityList(roles));
    }

    private static PermissionInfo local(String authority, String role, String status) {
        return new PermissionInfo(authority, role, status, "local");
    }

    private static PermissionInfo inherited(String authority, String role, String status) {
        return new PermissionInfo(authority, role, status, "inherited");
    }

    private static DocumentNode node(String createdById, boolean inherit, PermissionInfo... perms) {
        return new DocumentNode("node-1", "doc.pdf", "cm:content", false, true,
                "application/pdf", 100L, null, null, "Creator Name", null, null, "/",
                List.of(perms), inherit, createdById);
    }
}
