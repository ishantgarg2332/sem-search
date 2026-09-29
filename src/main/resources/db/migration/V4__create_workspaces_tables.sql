-- Phase 2, 3 & 4: Workspace folders, permissions, access requests, and activity tracking.

CREATE TABLE IF NOT EXISTS ingest.workspace_folder (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alfresco_node_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    owner_username VARCHAR(100) NOT NULL,
    visibility VARCHAR(20) NOT NULL CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_workspace_folder_owner ON ingest.workspace_folder (owner_username);
CREATE INDEX IF NOT EXISTS idx_workspace_folder_visibility ON ingest.workspace_folder (visibility);
CREATE INDEX IF NOT EXISTS idx_workspace_folder_node_id ON ingest.workspace_folder (alfresco_node_id);

CREATE TABLE IF NOT EXISTS ingest.workspace_permission (
    id BIGSERIAL PRIMARY KEY,
    workspace_folder_id UUID NOT NULL REFERENCES ingest.workspace_folder(id) ON DELETE CASCADE,
    username VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL,
    source VARCHAR(20) NOT NULL CHECK (source IN ('OWNER', 'DEFAULT_PUBLIC', 'APPROVED_REQUEST', 'ADMIN_GRANT')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by VARCHAR(100) NOT NULL,
    UNIQUE (workspace_folder_id, username)
);

CREATE INDEX IF NOT EXISTS idx_workspace_permission_user ON ingest.workspace_permission (username);

CREATE TABLE IF NOT EXISTS ingest.workspace_access_request (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_folder_id UUID NOT NULL REFERENCES ingest.workspace_folder(id) ON DELETE CASCADE,
    requester_username VARCHAR(100) NOT NULL,
    requested_role VARCHAR(50) NOT NULL DEFAULT 'COLLABORATOR',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'DENIED')),
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    reviewed_by VARCHAR(100),
    review_comment TEXT
);

CREATE INDEX IF NOT EXISTS idx_access_request_folder_status ON ingest.workspace_access_request (workspace_folder_id, status);
CREATE INDEX IF NOT EXISTS idx_access_request_requester ON ingest.workspace_access_request (requester_username);

CREATE TABLE IF NOT EXISTS ingest.workspace_activity (
    id BIGSERIAL PRIMARY KEY,
    workspace_folder_id UUID NOT NULL REFERENCES ingest.workspace_folder(id) ON DELETE CASCADE,
    username VARCHAR(100) NOT NULL,
    activity_type VARCHAR(50) NOT NULL,
    details JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_workspace_activity_folder ON ingest.workspace_activity (workspace_folder_id, occurred_at DESC);
