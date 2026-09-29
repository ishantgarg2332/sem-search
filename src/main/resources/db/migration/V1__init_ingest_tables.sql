CREATE TABLE ingest.job (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    node_id VARCHAR(255) NOT NULL,
    action VARCHAR(32) NOT NULL CHECK (action IN ('UPSERT', 'DELETE')),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_ingest_job_active_node 
ON ingest.job (node_id) 
WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX idx_ingest_job_pending_poll 
ON ingest.job (next_attempt_at) 
WHERE status = 'PENDING';

CREATE TABLE ingest.node_state (
    node_id VARCHAR(255) PRIMARY KEY,
    version_label VARCHAR(64),
    content_sha256 VARCHAR(64) NOT NULL,
    chunk_count INT NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
