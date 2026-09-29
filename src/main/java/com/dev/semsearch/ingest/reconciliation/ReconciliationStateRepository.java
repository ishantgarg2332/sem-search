package com.dev.semsearch.ingest.reconciliation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Repository for managing high-water mark timestamps in {@code ingest.reconciliation_state}.
 */
@Repository
public class ReconciliationStateRepository {

    public static final String TASK_CONTENT = "CONTENT_RECONCILIATION";

    private final JdbcClient jdbc;

    public ReconciliationStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Gets the last run timestamp for a reconciliation task.
     *
     * @param taskName task name identifier
     * @return optional timestamp
     */
    public Optional<Instant> getLastRunAt(String taskName) {
        return jdbc.sql("SELECT last_run_at FROM ingest.reconciliation_state WHERE task_name = :taskName")
                .param("taskName", taskName)
                .query((rs, rowNum) -> rs.getTimestamp("last_run_at").toInstant())
                .optional();
    }

    /**
     * Updates or inserts the last run timestamp for a reconciliation task.
     *
     * @param taskName task name identifier
     * @param lastRunAt new high-water mark timestamp
     */
    @Transactional
    public void setLastRunAt(String taskName, Instant lastRunAt) {
        jdbc.sql("""
                INSERT INTO ingest.reconciliation_state (task_name, last_run_at, updated_at)
                VALUES (:taskName, :lastRunAt, now())
                ON CONFLICT (task_name) DO UPDATE
                SET last_run_at = EXCLUDED.last_run_at,
                    updated_at = now()
                """)
                .param("taskName", taskName)
                .param("lastRunAt", Timestamp.from(lastRunAt))
                .update();
    }
}
