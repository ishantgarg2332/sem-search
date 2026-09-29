package com.dev.semsearch.ingest.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Data access for the {@code ingest.job} table using Spring's {@link JdbcClient}.
 *
 * <p>Key SQL patterns used:
 * <ul>
 *   <li>{@code INSERT ... ON CONFLICT} with the partial unique index for deduplication</li>
 *   <li>{@code SELECT ... FOR UPDATE SKIP LOCKED} for concurrent job claiming</li>
 *   <li>Exponential backoff via {@code interval * power(2, attempts - 1)}</li>
 * </ul>
 */
@Repository
public class JobRepository {

    private static final Logger log = LoggerFactory.getLogger(JobRepository.class);

    private final JdbcClient jdbc;

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Enqueues a job for the given node. If an active job (PENDING or RUNNING) already
     * exists for this node, the action is updated to the latest event's action
     * (last-event-wins) and {@code updated_at} is refreshed.
     *
     * <p>The ON CONFLICT clause targets the partial unique index
     * {@code idx_ingest_job_active_node}, which only covers rows where
     * {@code status IN ('PENDING', 'RUNNING')}.
     *
     * @param nodeId the Alfresco node UUID
     * @param action either {@link IngestJob#ACTION_UPSERT} or {@link IngestJob#ACTION_DELETE}
     */
    @Transactional
    public void enqueue(String nodeId, String action) {
        int rows = jdbc.sql("""
                INSERT INTO ingest.job (node_id, action)
                VALUES (:nodeId, :action)
                ON CONFLICT (node_id) WHERE status IN ('PENDING', 'RUNNING')
                DO UPDATE SET
                    action = EXCLUDED.action,
                    updated_at = now()
                """)
                .param("nodeId", nodeId)
                .param("action", action)
                .update();
        log.debug("Enqueued job for node={} action={} (rows affected={})", nodeId, action, rows);
    }

    /**
     * Claims up to {@code batchSize} due PENDING jobs atomically.
     *
     * <p>Uses {@code FOR UPDATE SKIP LOCKED} so that concurrent workers never claim the
     * same job — a locked row is simply skipped. The claimed jobs are set to RUNNING,
     * their {@code claimed_at} is stamped, {@code attempts} is incremented, and each
     * gets a fresh {@code claim_token} that the worker must present to finish the job.
     *
     * @param batchSize maximum number of jobs to claim
     * @return the claimed jobs, or an empty list if none are due
     */
    @Transactional
    public List<IngestJob> claimBatch(int batchSize) {
        return jdbc.sql("""
                UPDATE ingest.job
                SET status = 'RUNNING',
                    claimed_at = now(),
                    attempts = attempts + 1,
                    claim_token = gen_random_uuid(),
                    updated_at = now()
                WHERE id IN (
                    SELECT id FROM ingest.job
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY next_attempt_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """)
                .param("batchSize", batchSize)
                .query(JobRepository::mapRow)
                .list();
    }

    /**
     * Marks a job as DONE, but only if the caller still holds the lease.
     *
     * <p>Returns the job so the caller can check if {@code updated_at > claimed_at},
     * which means a new event arrived while the job was processing, and a fresh
     * PENDING job should be enqueued for the same node.
     *
     * <p>{@code updated_at} is deliberately left unchanged here: it's the signal that an
     * event arrived during processing, and overwriting it would hide that.
     *
     * @param jobId      the job ID
     * @param claimToken the token returned by {@link #claimBatch(int)} for this job
     * @return the updated job, or empty if the lease was lost (the reaper reset the job
     *         or another worker re-claimed it), in which case the caller must do nothing
     */
    @Transactional
    public Optional<IngestJob> markDone(long jobId, UUID claimToken) {
        return jdbc.sql("""
                UPDATE ingest.job
                SET status = 'DONE'
                WHERE id = :jobId
                  AND status = 'RUNNING'
                  AND claim_token = :claimToken
                RETURNING *
                """)
                .param("jobId", jobId)
                .param("claimToken", claimToken)
                .query(JobRepository::mapRow)
                .optional();
    }

    /**
     * Returns jobs whose worker died mid-processing to the queue.
     *
     * A job is "expired" when it has been RUNNING longer than the lease timeout.
     * Below maxAttempts it goes back to PENDING for another try; at maxAttempts it
     * becomes FAILED, so a file that crashes the worker every time can't loop forever.
     *
     * @return number of jobs reaped (for logging and metrics)
     */
    @Transactional
    public int reapExpiredLeases(java.time.Duration leaseTimeout, int maxAttempts) {
        return jdbc.sql("""
            UPDATE ingest.job
            SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                last_error = 'Lease expired: worker did not finish within the lease timeout',
                next_attempt_at = now(),
                claim_token = NULL,
                updated_at = now()
            WHERE status = 'RUNNING'
              AND claimed_at < now() - make_interval(secs => :leaseSeconds)
            """)
            .param("maxAttempts", maxAttempts)
            .param("leaseSeconds", leaseTimeout.toSeconds())
            .update();
    }

    /**
     * Marks a job as failed with an error message. If the attempt count is below
     * {@code maxAttempts}, the job reverts to PENDING with exponential backoff.
     * Otherwise it stays permanently FAILED.
     *
     * <p>Backoff schedule: 30s, 60s, 120s, 240s (30s × 2^(attempt-1)).
     *
     * <p>Like {@link #markDone}, this only applies while the caller holds the lease.
     *
     * @param jobId       the job ID
     * @param claimToken  the token returned by {@link #claimBatch(int)} for this job
     * @param error       the error message to record
     * @param maxAttempts threshold after which the job stays FAILED
     * @return true if the job was updated, false if the lease was already lost
     */
    @Transactional
    public boolean markFailed(long jobId, UUID claimToken, String error, int maxAttempts) {
        return jdbc.sql("""
                UPDATE ingest.job
                SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                    last_error = :error,
                    next_attempt_at = CASE
                        WHEN attempts >= :maxAttempts THEN next_attempt_at
                        ELSE now() + LEAST(interval '30 minutes', (interval '30 seconds' * power(2, attempts - 1)) * (1.0 + random() * 0.20))
                    END,
                    updated_at = now()
                WHERE id = :jobId
                  AND status = 'RUNNING'
                  AND claim_token = :claimToken
                """)
                .param("jobId", jobId)
                .param("claimToken", claimToken)
                .param("error", error)
                .param("maxAttempts", maxAttempts)
                .update() == 1;
    }

    /**
     * Deletes completed jobs older than the specified retention period.
     *
     * @param retentionDays number of days to retain completed jobs
     * @return the number of deleted rows
     */
    @Transactional
    public int cleanupDone(int retentionDays) {
        return jdbc.sql("""
                DELETE FROM ingest.job
                WHERE status = 'DONE'
                  AND updated_at < now() - make_interval(days => :days)
                """)
                .param("days", retentionDays)
                .update();
    }

    /**
     * Retrieves recent permanently failed jobs for administrative review.
     *
     * @param limit maximum number of failed jobs to return
     * @return list of failed jobs sorted by updated_at descending
     */
    public List<IngestJob> findFailedJobs(int limit) {
        return jdbc.sql("""
                SELECT * FROM ingest.job
                WHERE status = 'FAILED'
                ORDER BY updated_at DESC
                LIMIT :limit
                """)
                .param("limit", limit > 0 ? limit : 50)
                .query(JobRepository::mapRow)
                .list();
    }

    /**
     * Requeues all permanently failed jobs back to PENDING status,
     * resetting their attempt counter and clearing errors.
     *
     * @return number of requeued jobs
     */
    @Transactional
    public int requeueFailedJobs() {
        return jdbc.sql("""
                UPDATE ingest.job
                SET status = 'PENDING',
                    attempts = 0,
                    next_attempt_at = now(),
                    last_error = null,
                    updated_at = now()
                WHERE status = 'FAILED'
                """)
                .update();
    }

    /**
     * Counts the number of jobs currently in the specified status.
     * Used by Micrometer gauges for queue monitoring.
     *
     * @param status the status string (e.g. 'PENDING', 'RUNNING', 'FAILED')
     * @return the count of jobs
     */
    public long countByStatus(String status) {
        Long count = jdbc.sql("SELECT count(*) FROM ingest.job WHERE status = :status")
                .param("status", status)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    /**
     * Returns aggregate job counts grouped by status.
     * Used by the admin dashboard for the stats overview cards.
     *
     * @return map of status → count (keys: PENDING, RUNNING, DONE, FAILED)
     */
    public Map<String, Long> getJobStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("PENDING", 0L);
        stats.put("RUNNING", 0L);
        stats.put("DONE", 0L);
        stats.put("FAILED", 0L);

        jdbc.sql("SELECT status, count(*) AS cnt FROM ingest.job GROUP BY status")
                .query((rs, rowNum) -> {
                    stats.put(rs.getString("status"), rs.getLong("cnt"));
                    return null;
                })
                .list();
        return stats;
    }

    /**
     * Retrieves the most recent jobs across all statuses for the admin activity feed.
     *
     * @param limit maximum number of jobs to return
     * @return list of recent jobs sorted by updated_at descending
     */
    public List<IngestJob> findRecentJobs(int limit) {
        return jdbc.sql("""
                SELECT * FROM ingest.job
                ORDER BY updated_at DESC
                LIMIT :limit
                """)
                .param("limit", limit > 0 ? limit : 20)
                .query(JobRepository::mapRow)
                .list();
    }

    /**
     * Maps a ResultSet row to an {@link IngestJob} record.
     */
    private static IngestJob mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new IngestJob(
                rs.getLong("id"),
                rs.getString("node_id"),
                rs.getString("action"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                toInstant(rs, "next_attempt_at"),
                toInstant(rs, "claimed_at"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"),
                rs.getObject("claim_token", UUID.class)
        );
    }

    /**
     * Safely reads a nullable TIMESTAMPTZ column as an Instant.
     */
    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant() : null;
    }
}
