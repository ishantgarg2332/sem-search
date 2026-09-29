package com.dev.semsearch.ingest.job;

import com.dev.semsearch.ingest.IngestProperties;
import com.dev.semsearch.ingest.pipeline.ContentProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Background worker that polls the job queue and processes claimed jobs.
 *
 * <p>Two scheduled methods:
 * <ul>
 *   <li>{@link #poll()} — claims and processes a batch of due PENDING jobs</li>
 *   <li>{@link #cleanup()} — deletes DONE jobs past the retention window</li>
 * </ul>
 *
 * <p>Processing is delegated to {@link ContentProcessor}, which handles the full
 * pipeline: fetch → download → hash → extract → chunk → embed → index.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository jobRepository;
    private final IngestProperties properties;
    private final ContentProcessor contentProcessor;

    public JobWorker(JobRepository jobRepository, IngestProperties properties,
                     ContentProcessor contentProcessor) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.contentProcessor = contentProcessor;
    }

    /**
     * Polls for due PENDING jobs and processes them sequentially.
     * Uses fixedDelay so the next poll starts after the previous batch completes,
     * preventing overlap if processing takes longer than the interval.
     */
    @Scheduled(fixedDelayString = "${ingest.worker.poll-interval-ms:2000}")
    public void poll() {
        List<IngestJob> jobs = jobRepository.claimBatch(properties.getWorker().getBatchSize());
        if (!jobs.isEmpty()) {
            log.info("Claimed {} job(s) for processing", jobs.size());
        }
        for (IngestJob job : jobs) {
            processJob(job);
        }
    }

    /**
     * Deletes completed jobs older than the configured retention period.
     * Runs daily at 3:00 AM.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        int deleted = jobRepository.cleanupDone(properties.getCleanup().getRetentionDays());
        if (deleted > 0) {
            log.info("Cleaned up {} completed job(s) older than {} day(s)",
                    deleted, properties.getCleanup().getRetentionDays());
        }
    }

    /**
     * Processes a single claimed job by delegating to {@link ContentProcessor}.
     *
     * <p>After processing, the job is marked DONE using the claim token, which only works
     * while this worker still holds the lease. If the lease was lost (this job ran past
     * the lease timeout and {@link LeaseReaper} reset it, or another worker re-claimed it),
     * nothing more is done here: the job's current owner is responsible for it now.
     *
     * <p>If a new event arrived while the job was running ({@code updated_at > claimed_at}),
     * a fresh PENDING job is enqueued using the <em>latest</em> event's action.
     */
    void processJob(IngestJob job) {
        log.info("Processing job id={} node={} action={} attempt={}",
                job.id(), job.nodeId(), job.action(), job.attempts());
        try {
            if (IngestJob.ACTION_DELETE.equals(job.action())) {
                contentProcessor.processDelete(job.nodeId());
            } else {
                contentProcessor.processUpsert(job.nodeId());
            }

            Optional<IngestJob> done = jobRepository.markDone(job.id(), job.claimToken());
            if (done.isEmpty()) {
                log.warn("Job id={} node={} finished after its lease was lost; leaving it to its current owner",
                        job.id(), job.nodeId());
                return;
            }
            IngestJob doneJob = done.get();

            // Check if a new event arrived while we were processing.
            // If updated_at was bumped after claimed_at, the ON CONFLICT path
            // touched the row, meaning a new event needs processing.
            if (doneJob.updatedAt() != null && doneJob.claimedAt() != null
                    && doneJob.updatedAt().isAfter(doneJob.claimedAt())) {
                // doneJob.action() holds the latest event's action (ON CONFLICT overwrote it);
                // job.action() is only what the job was when we claimed it.
                log.info("Event arrived during processing of job id={}. Enqueuing fresh {} job for node={}",
                        job.id(), doneJob.action(), job.nodeId());
                jobRepository.enqueue(job.nodeId(), doneJob.action());
            }

        } catch (Exception e) {
            log.error("Job id={} failed: {}", job.id(), e.getMessage(), e);
            boolean recorded = jobRepository.markFailed(job.id(), job.claimToken(), e.getMessage(),
                    properties.getWorker().getMaxAttempts());
            if (!recorded) {
                log.warn("Job id={} failed after its lease was lost; failure not recorded", job.id());
            }
        }
    }
}
