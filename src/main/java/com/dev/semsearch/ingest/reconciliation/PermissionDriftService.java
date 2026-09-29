package com.dev.semsearch.ingest.reconciliation;

import com.dev.semsearch.ingest.job.IngestJob;
import com.dev.semsearch.ingest.job.JobRepository;
import com.dev.semsearch.ingest.pipeline.NodeStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Nightly permission drift reconciliation job.
 * Enqueues UPSERT jobs for all indexed nodes.
 *
 * <p>Because folder permission changes in Alfresco do not fire events for every child document,
 * this nightly run catches up on permission changes.
 *
 * <p><strong>Efficiency:</strong> ContentProcessor checks the SHA-256 hash first. Because the
 * document content has not changed, it immediately takes the <em>metadata-only update path</em>,
 * executing an in-place Elasticsearch script update on {@code readers} in milliseconds, completely
 * bypassing Apache Tika text extraction and Ollama vector embeddings.
 */
@Service
public class PermissionDriftService {

    private static final Logger log = LoggerFactory.getLogger(PermissionDriftService.class);

    private final NodeStateRepository nodeStateRepository;
    private final JobRepository jobRepository;

    public PermissionDriftService(
            NodeStateRepository nodeStateRepository,
            JobRepository jobRepository
    ) {
        this.nodeStateRepository = nodeStateRepository;
        this.jobRepository = jobRepository;
    }

    /**
     * Scheduled nightly permission drift run (runs daily at 1:00 AM by default).
     */
    @Scheduled(cron = "${ingest.reconciliation.permission-cron:0 0 1 * * *}")
    public void runScheduled() {
        log.info("Starting scheduled nightly permission drift reconciliation...");
        int enqueued = reconcilePermissions();
        log.info("Finished scheduled permission drift reconciliation. Enqueued {} node(s).", enqueued);
    }

    /**
     * Executes permission drift reconciliation for all indexed nodes.
     * Can be invoked programmatically or via admin endpoints.
     *
     * @return count of enqueued UPSERT jobs
     */
    public int reconcilePermissions() {
        List<String> indexedNodeIds = nodeStateRepository.findAllNodeIds();
        log.info("Enqueuing permission refresh for {} indexed node(s)...", indexedNodeIds.size());

        int count = 0;
        for (String nodeId : indexedNodeIds) {
            jobRepository.enqueue(nodeId, IngestJob.ACTION_UPSERT);
            count++;
        }

        log.info("Permission drift reconciliation complete: enqueued {} node(s)", count);
        return count;
    }
}
