package com.dev.semsearch.ingest.job;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a row from the {@code ingest.job} table.
 * Immutable snapshot of a job at the time it was read from the database.
 */
public record IngestJob(
        long id,
        String nodeId,
        String action,
        String status,
        int attempts,
        String lastError,
        Instant nextAttemptAt,
        Instant claimedAt,
        Instant createdAt,
        Instant updatedAt,
        /**
         * Fencing token set by each claim. Only the worker holding the current token may
         * mark the job DONE or FAILED. Internal bookkeeping, so it's left out of API responses.
         */
        @JsonIgnore UUID claimToken
) {

    /** Job actions — must match the CHECK constraint in V1__init_ingest_tables.sql. */
    public static final String ACTION_UPSERT = "UPSERT";
    public static final String ACTION_DELETE = "DELETE";

    /** Job statuses — must match the CHECK constraint in V1__init_ingest_tables.sql. */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
}
