package com.dev.semsearch.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class IngestDataModelIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @Transactional
    void testDuplicatePendingJobFails() {
        String nodeId = UUID.randomUUID().toString();

        // First PENDING job insert must succeed
        int inserted1 = jdbcClient.sql("""
                INSERT INTO ingest.job (node_id, action, status)
                VALUES (?, 'UPSERT', 'PENDING')
                """).params(nodeId).update();
        assertThat(inserted1).isEqualTo(1);

        // Second PENDING job insert for the same node_id must fail due to partial unique index
        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO ingest.job (node_id, action, status)
                VALUES (?, 'UPSERT', 'PENDING')
                """).params(nodeId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void testRequeueAfterDoneSucceeds() {
        String nodeId = UUID.randomUUID().toString();

        // First job inserted and completed
        jdbcClient.sql("""
                INSERT INTO ingest.job (node_id, action, status)
                VALUES (?, 'UPSERT', 'PENDING')
                """).params(nodeId).update();

        jdbcClient.sql("""
                UPDATE ingest.job
                SET status = 'DONE', updated_at = now()
                WHERE node_id = ?
                """).params(nodeId).update();

        // Inserting a new PENDING job for the same node_id now must succeed
        int inserted2 = jdbcClient.sql("""
                INSERT INTO ingest.job (node_id, action, status)
                VALUES (?, 'UPSERT', 'PENDING')
                """).params(nodeId).update();
        assertThat(inserted2).isEqualTo(1);
    }

    @Test
    @Transactional
    void testInvalidStatusCheckConstraint() {
        String nodeId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO ingest.job (node_id, action, status)
                VALUES (?, 'UPSERT', 'PENDNG')
                """).params(nodeId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void testInvalidActionCheckConstraint() {
        String nodeId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO ingest.job (node_id, action, status)
                VALUES (?, 'INVALID', 'PENDING')
                """).params(nodeId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void testNegativeAttemptsCheckConstraint() {
        String nodeId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO ingest.job (node_id, action, status, attempts)
                VALUES (?, 'UPSERT', 'PENDING', -1)
                """).params(nodeId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void testNullableVersionLabelSucceeds() {
        String nodeId = UUID.randomUUID().toString();

        int inserted = jdbcClient.sql("""
                INSERT INTO ingest.node_state (node_id, version_label, content_sha256, chunk_count)
                VALUES (?, null, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 0)
                """).params(nodeId).update();

        assertThat(inserted).isEqualTo(1);

        String fetchedVersion = jdbcClient.sql("SELECT version_label FROM ingest.node_state WHERE node_id = ?")
                .params(nodeId)
                .query(String.class)
                .optional()
                .orElse(null);

        assertThat(fetchedVersion).isNull();
    }
}
