package com.dev.semsearch.ingest.alfresco;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionMapperTest {

    private PermissionMapper permissionMapper;

    @BeforeEach
    void setUp() {
        permissionMapper = new PermissionMapper();
    }

    private NodeInfo createNodeInfo(String owner, NodeInfo.Permissions permissions) {
        return new NodeInfo(
                "node-123",
                "test.pdf",
                "cm:content",
                "application/pdf",
                1024L,
                Instant.now(),
                "1.0",
                owner,
                "/Company Home/Docs",
                permissions
        );
    }

    @Test
    void testExtractReadersNullNodeInfo() {
        List<String> readers = permissionMapper.toReaders(null);
        assertThat(readers).isEmpty();
    }

    @Test
    void testExtractReadersNullPermissionsPreservesOwner() {
        NodeInfo node = createNodeInfo("admin", null);
        List<String> readers = permissionMapper.toReaders(node);
        assertThat(readers).containsExactly("admin");
    }

    @Test
    void testExtractReadersDirectPermissionsOnly() {
        NodeInfo.Permissions permissions = new NodeInfo.Permissions(
                List.of(
                        new NodeInfo.Permission("alice", "Consumer", "ALLOWED"),
                        new NodeInfo.Permission("GROUP_ENGINEERING", "Contributor", "ALLOWED")
                ),
                null,
                false
        );

        NodeInfo node = createNodeInfo("owner1", permissions);
        List<String> readers = permissionMapper.toReaders(node);
        assertThat(readers).containsExactly("GROUP_ENGINEERING", "alice", "owner1");
    }

    @Test
    void testExtractReadersWithInheritedWhenInheritanceEnabled() {
        NodeInfo.Permissions permissions = new NodeInfo.Permissions(
                List.of(
                        new NodeInfo.Permission("bob", "Consumer", "ALLOWED")
                ),
                List.of(
                        new NodeInfo.Permission("GROUP_EVERYONE", "Consumer", "ALLOWED"),
                        new NodeInfo.Permission("manager", "Coordinator", "ALLOWED")
                ),
                true
        );

        NodeInfo node = createNodeInfo("owner1", permissions);
        List<String> readers = permissionMapper.toReaders(node);
        assertThat(readers).containsExactly("GROUP_EVERYONE", "bob", "manager", "owner1");
    }

    @Test
    void testExtractReadersIgnoresInheritedWhenInheritanceDisabled() {
        NodeInfo.Permissions permissions = new NodeInfo.Permissions(
                List.of(
                        new NodeInfo.Permission("charlie", "Consumer", "ALLOWED")
                ),
                List.of(
                        new NodeInfo.Permission("GROUP_EVERYONE", "Consumer", "ALLOWED")
                ),
                false
        );

        NodeInfo node = createNodeInfo(null, permissions);
        List<String> readers = permissionMapper.toReaders(node);
        assertThat(readers).containsExactly("charlie");
    }

    @Test
    void testExtractReadersFiltersDeniedPermissions() {
        NodeInfo.Permissions permissions = new NodeInfo.Permissions(
                List.of(
                        new NodeInfo.Permission("allowed_user", "Consumer", "ALLOWED"),
                        new NodeInfo.Permission("denied_user", "Consumer", "DENIED")
                ),
                List.of(
                        new NodeInfo.Permission("allowed_group", "Consumer", "ALLOWED"),
                        new NodeInfo.Permission("denied_group", "Consumer", "DENIED")
                ),
                true
        );

        NodeInfo node = createNodeInfo(null, permissions);
        List<String> readers = permissionMapper.toReaders(node);
        assertThat(readers).containsExactly("allowed_group", "allowed_user");
    }

    @Test
    void testExtractReadersDeduplicatesAuthorities() {
        NodeInfo.Permissions permissions = new NodeInfo.Permissions(
                List.of(
                        new NodeInfo.Permission("alice", "Coordinator", "ALLOWED")
                ),
                List.of(
                        new NodeInfo.Permission("alice", "Consumer", "ALLOWED")
                ),
                true
        );

        NodeInfo node = createNodeInfo("alice", permissions);
        List<String> readers = permissionMapper.toReaders(node);
        assertThat(readers).containsExactly("alice");
    }
}
