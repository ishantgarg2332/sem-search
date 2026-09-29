package com.dev.semsearch.ingest.reconciliation;

import com.dev.semsearch.ingest.job.IngestJob;
import com.dev.semsearch.ingest.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Hourly content reconciliation job.
 * Discovers documents modified in Alfresco since the last reconciliation run
 * by querying the Alfresco Search API, and enqueues them as UPSERT jobs.
 * This guarantees eventual consistency if ActiveMQ events were dropped or missed during downtime.
 */
@Service
public class ContentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ContentReconciliationService.class);

    private final AlfrescoSearchClient searchClient;
    private final ReconciliationStateRepository stateRepository;
    private final JobRepository jobRepository;

    public ContentReconciliationService(
            AlfrescoSearchClient searchClient,
            ReconciliationStateRepository stateRepository,
            JobRepository jobRepository
    ) {
        this.searchClient = searchClient;
        this.stateRepository = stateRepository;
        this.jobRepository = jobRepository;
    }

    /**
     * Scheduled hourly reconciliation run.
     */
    @Scheduled(cron = "${ingest.reconciliation.content-cron:0 0 * * * *}")
    public void runScheduled() {
        log.info("Starting scheduled hourly content reconciliation...");
        int enqueued = reconcileContent();
        log.info("Finished scheduled content reconciliation. Enqueued {} job(s).", enqueued);
    }

    /**
     * Executes content reconciliation and returns the number of enqueued jobs.
     * Can be invoked programmatically or via admin endpoints.
     *
     * @return count of enqueued jobs
     */
    public int reconcileContent() {
        Instant now = Instant.now();
        Instant lastRun = stateRepository.getLastRunAt(ReconciliationStateRepository.TASK_CONTENT)
                .orElse(now.minus(1, ChronoUnit.HOURS));

        // If this throws, we return before setLastRunAt, so the next run retries the same window.
        List<String> modifiedNodeIds = searchClient.findNodesModifiedSince(lastRun);
        int enqueuedCount = 0;

        for (String nodeId : modifiedNodeIds) {
            jobRepository.enqueue(nodeId, IngestJob.ACTION_UPSERT);
            enqueuedCount++;
        }

        stateRepository.setLastRunAt(ReconciliationStateRepository.TASK_CONTENT, now);
        log.info("Content reconciliation complete: checked changes since {}, enqueued {} node(s)", lastRun, enqueuedCount);
        return enqueuedCount;
    }
}
