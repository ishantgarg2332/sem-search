package com.dev.semsearch.ingest.admin;

import com.dev.semsearch.ingest.job.IngestJob;
import com.dev.semsearch.ingest.job.JobRepository;
import com.dev.semsearch.ingest.reconciliation.ContentReconciliationService;
import com.dev.semsearch.ingest.reconciliation.OrphanReconciliationService;
import com.dev.semsearch.ingest.reconciliation.PermissionDriftService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Administrative REST endpoints for managing failed jobs and triggering reconciliation runs.
 * Protected by Spring Security — requires {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminJobController {

    private static final Logger log = LoggerFactory.getLogger(AdminJobController.class);

    private final JobRepository jobRepository;
    private final ContentReconciliationService contentService;
    private final OrphanReconciliationService orphanService;
    private final PermissionDriftService permissionService;

    public AdminJobController(
            JobRepository jobRepository,
            ContentReconciliationService contentService,
            OrphanReconciliationService orphanService,
            PermissionDriftService permissionService
    ) {
        this.jobRepository = jobRepository;
        this.contentService = contentService;
        this.orphanService = orphanService;
        this.permissionService = permissionService;
    }

    /**
     * Returns aggregate job counts grouped by status.
     * Used by the admin dashboard stats overview cards.
     *
     * @return map with keys: PENDING, RUNNING, DONE, FAILED → count
     */
    @GetMapping("/jobs/stats")
    public Map<String, Long> getJobStats() {
        log.debug("Admin requested job stats");
        return jobRepository.getJobStats();
    }

    /**
     * Lists recent jobs across all statuses for the admin activity feed.
     *
     * @param limit maximum number of recent jobs to return (default: 20)
     * @return list of recent jobs sorted by updated_at descending
     */
    @GetMapping("/jobs/recent")
    public List<IngestJob> listRecentJobs(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        log.debug("Admin requested recent jobs, limit={}", limit);
        return jobRepository.findRecentJobs(limit);
    }

    /**
     * Lists recent permanently failed jobs for inspection.
     *
     * @param limit maximum number of failed jobs to return (default: 50)
     * @return list of failed job records
     */
    @GetMapping("/jobs/failed")
    public List<IngestJob> listFailedJobs(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        log.info("Admin requested list of failed jobs, limit={}", limit);
        return jobRepository.findFailedJobs(limit);
    }

    /**
     * Requeues all permanently failed jobs back to PENDING status,
     * resetting their attempt counters.
     *
     * @return map with count of requeued jobs
     */
    @PostMapping("/jobs/requeue")
    public ResponseEntity<Map<String, Object>> requeueFailedJobs() {
        int requeued = jobRepository.requeueFailedJobs();
        log.info("Admin requeued {} failed job(s)", requeued);
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "requeuedCount", requeued
        ));
    }

    /**
     * Manually triggers content reconciliation (hourly task).
     */
    @PostMapping("/reconcile/content")
    public ResponseEntity<Map<String, Object>> triggerContentReconciliation() {
        log.info("Admin triggered content reconciliation run");
        int enqueued = contentService.reconcileContent();
        return ResponseEntity.ok(Map.of(
                "task", "CONTENT_RECONCILIATION",
                "enqueuedCount", enqueued
        ));
    }

    /**
     * Manually triggers orphan document verification (weekly task).
     */
    @PostMapping("/reconcile/orphans")
    public ResponseEntity<Map<String, Object>> triggerOrphanReconciliation() {
        log.info("Admin triggered orphan reconciliation run");
        int enqueued = orphanService.reconcileOrphans();
        return ResponseEntity.ok(Map.of(
                "task", "ORPHAN_RECONCILIATION",
                "enqueuedCount", enqueued
        ));
    }

    /**
     * Manually triggers permission drift reconciliation (nightly task).
     */
    @PostMapping("/reconcile/permissions")
    public ResponseEntity<Map<String, Object>> triggerPermissionReconciliation() {
        log.info("Admin triggered permission drift reconciliation run");
        int enqueued = permissionService.reconcilePermissions();
        return ResponseEntity.ok(Map.of(
                "task", "PERMISSION_DRIFT",
                "enqueuedCount", enqueued
        ));
    }
}
