package com.dev.semsearch.search.workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceFolder(
        UUID id,
        String alfrescoNodeId,
        String name,
        String description,
        String ownerUsername,
        Visibility visibility,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Visibility {
        PUBLIC,
        PRIVATE
    }
}
