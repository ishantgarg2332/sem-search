package com.dev.semsearch.ingest.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the job lease rules against the real app Postgres, like
 * {@code IngestDataModelIntegrationTest}: expired leases are reaped, active ones are
 * left alone, and only the holder of the current claim token can finish a job.
 *
 * <p>Each test runs in a transaction that is rolled back, so nothing is left behind.
 * Inside one transaction Postgres' {@code now()} is fixed, which makes the time maths exact.
 */
@SpringBootTest
@Transactional
class JobLeaseIntegrationTest {

    private static final Duration LEASE = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JobRepository jobRepository;

    @Test
    void expiredLeaseBelowMaxAttemptsGoesBackToPending() {
        long id = insertRunning(1, "20 minutes", UUID.randomUUID());

        jobRepository.reapExpiredLeases(LEASE, MAX_ATTEMPTS);

        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(tokenIsNull(id)).isTrue();
        assertThat(lastError(id)).startsWith("Lease expired");
    }

    @Test
    void expiredLeaseAtMaxAttemptsBecomesFailed() {
        long id = insertRunning(MAX_ATTEMPTS, "20 minutes", UUID.randomUUID());

        jobRepository.reapExpiredLeases(LEASE, MAX_ATTEMPTS);

        assertThat(status(id)).isEqualTo("FAILED");
    }

    @Test
    void jobStillWithinItsLeaseIsLeftAlone() {
        long id = insertRunning(1, "1 minute", UUID.randomUUID());

        jobRepository.reapExpiredLeases(LEASE, MAX_ATTEMPTS);

        assertThat(status(id)).isEqualTo("RUNNING");
    }

    @Test
    void claimIssuesAToken() {
        String nodeId = UUID.randomUUID().toString();
        // Due far in the past, so it sorts first in the claim query's ORDER BY.
        long id = jdbc.sql("""
                INSERT INTO ingest.job (node_id, action, status, next_attempt_at)
                VALUES (:nodeId, 'UPSERT', 'PENDING', now() - interval '100 years')
                RETURNING id
                """).param("nodeId", nodeId).query(Long.class).single();

        List<IngestJob> claimed = jobRepository.claimBatch(1);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).id()).isEqualTo(id);
        assertThat(claimed.get(0).claimToken()).isNotNull();
        assertThat(status(id)).isEqualTo("RUNNING");
    }

    @Test
    void onlyTheCurrentTokenCanMarkDone() {
        UUID token = UUID.randomUUID();
        long id = insertRunning(1, "1 minute", token);

        Optional<IngestJob> withWrongToken = jobRepository.markDone(id, UUID.randomUUID());
        assertThat(withWrongToken).isEmpty();
        assertThat(status(id)).isEqualTo("RUNNING");

        Optional<IngestJob> withRightToken = jobRepository.markDone(id, token);
        assertThat(withRightToken).isPresent();
        assertThat(status(id)).isEqualTo("DONE");
    }

    @Test
    void slowWorkerCannotFinishAJobThatWasReaped() {
        UUID oldToken = UUID.randomUUID();
        long id = insertRunning(1, "20 minutes", oldToken);

        jobRepository.reapExpiredLeases(LEASE, MAX_ATTEMPTS);

        // The original worker finally finishes and tries to report back.
        assertThat(jobRepository.markDone(id, oldToken)).isEmpty();
        assertThat(jobRepository.markFailed(id, oldToken, "late failure", MAX_ATTEMPTS)).isFalse();
        assertThat(status(id)).isEqualTo("PENDING");
    }

    @Test
    void markFailedWithCurrentTokenSchedulesARetry() {
        UUID token = UUID.randomUUID();
        long id = insertRunning(1, "1 minute", token);

        assertThat(jobRepository.markFailed(id, token, "boom", MAX_ATTEMPTS)).isTrue();

        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(lastError(id)).isEqualTo("boom");
    }

    // ── helpers ──

    private long insertRunning(int attempts, String claimedAgo, UUID token) {
        return jdbc.sql("""
                INSERT INTO ingest.job (node_id, action, status, attempts, claimed_at, claim_token)
                VALUES (:nodeId, 'UPSERT', 'RUNNING', :attempts, now() - CAST(:ago AS interval), :token)
                RETURNING id
                """)
                .param("nodeId", UUID.randomUUID().toString())
                .param("attempts", attempts)
                .param("ago", claimedAgo)
                .param("token", token)
                .query(Long.class)
                .single();
    }

    private String status(long id) {
        return jdbc.sql("SELECT status FROM ingest.job WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private String lastError(long id) {
        return jdbc.sql("SELECT coalesce(last_error, '') FROM ingest.job WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private boolean tokenIsNull(long id) {
        return jdbc.sql("SELECT claim_token IS NULL FROM ingest.job WHERE id = :id")
                .param("id", id).query(Boolean.class).single();
    }
}
