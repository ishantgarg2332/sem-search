package com.dev.semsearch.ingest.reconciliation;

import com.dev.semsearch.ingest.alfresco.AlfrescoClient;
import com.dev.semsearch.ingest.job.IngestJob;
import com.dev.semsearch.ingest.job.JobRepository;
import com.dev.semsearch.ingest.pipeline.NodeStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Weekly orphan check reconciliation job.
 * Scans all nodes tracked in {@code ingest.node_state} and verifies their existence in Alfresco.
 * If a node no longer exists in Alfresco (returns 404), an asynchronous DELETE job is enqueued
 * to clean up its chunks from Elasticsearch and remove its tracking state.
 */
@Service
public class OrphanReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrphanReconciliationService.class);

    private final NodeStateRepository nodeStateRepository;
    private final AlfrescoClient alfrescoClient;
    private final JobRepository jobRepository;

    public OrphanReconciliationService(
            NodeStateRepository nodeStateRepository,
            AlfrescoClient alfrescoClient,
            JobRepository jobRepository
    ) {
        this.nodeStateRepository = nodeStateRepository;
        this.alfrescoClient = alfrescoClient;
        this.jobRepository = jobRepository;
    }

    /**
     * Scheduled weekly orphan check run (runs Sundays at 2:00 AM by default).
     */
    @Scheduled(cron = "${ingest.reconciliation.orphan-cron:0 0 2 * * SUN}")
    public void runScheduled() {
        log.info("Starting scheduled weekly orphan reconciliation check...");
        int deleted = reconcileOrphans();
        log.info("Finished scheduled orphan reconciliation. Enqueued {} delete job(s).", deleted);
    }

    /**
     * Executes orphan reconciliation and returns the number of orphan delete jobs enqueued.
     * Can be invoked programmatically or via admin endpoints.
     *
     * @return count of enqueued DELETE jobs
     */
    public int reconcileOrphans() {
        List<String> indexedNodeIds = nodeStateRepository.findAllNodeIds();
        log.info("Checking {} indexed node(s) for orphan status in Alfresco...", indexedNodeIds.size());
        int orphanCount = 0;

        for (String nodeId : indexedNodeIds) {
            try {
                if (alfrescoClient.getNode(nodeId).isEmpty()) {
                    log.warn("Node {} no longer exists in Alfresco. Enqueuing DELETE job.", nodeId);
                    jobRepository.enqueue(nodeId, IngestJob.ACTION_DELETE);
                    orphanCount++;
                }
            } catch (Exception e) {
                log.error("Failed to verify existence of node {}: {}", nodeId, e.getMessage());
            }
        }

        log.info("Orphan reconciliation complete: detected {} orphan node(s)", orphanCount);
        return orphanCount;
    }
}
