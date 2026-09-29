CREATE TABLE ingest.reconciliation_state (
    task_name VARCHAR(64) PRIMARY KEY,
    last_run_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed initial record for content reconciliation
INSERT INTO ingest.reconciliation_state (task_name, last_run_at)
VALUES ('CONTENT_RECONCILIATION', now() - interval '1 hour')
ON CONFLICT (task_name) DO NOTHING;
