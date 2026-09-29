package com.dev.semsearch.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * Configuration properties for the ingestion subsystem.
 * Bound from the {@code ingest.*} keys in application.yml.
 */
@ConfigurationProperties(prefix = "ingest")
public class IngestProperties {

    private Worker worker = new Worker();
    private Cleanup cleanup = new Cleanup();
    private Pipeline pipeline = new Pipeline();

    public Worker getWorker() {
        return worker;
    }

    public void setWorker(Worker worker) {
        this.worker = worker;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public void setCleanup(Cleanup cleanup) {
        this.cleanup = cleanup;
    }

    public Pipeline getPipeline() {
        return pipeline;
    }

    public void setPipeline(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    public static class Worker {
        /** How often the worker polls for pending jobs, in milliseconds. */
        private long pollIntervalMs = 2000;
        /** Maximum number of jobs claimed per poll cycle. */
        private int batchSize = 10;
        /** Number of attempts before a job is permanently marked FAILED. */
        private int maxAttempts = 5;

        /**
         * How long a claimed job may stay RUNNING before LeaseReaper assumes its worker
         * died and returns it to PENDING (or FAILED at max attempts). Bound from
         * ingest.worker.lease-timeout, e.g. "15m".
         */
        private Duration leaseTimeout = Duration.ofMinutes(15);

        public Duration getLeaseTimeout() {
            return leaseTimeout;
        }

        public void setLeaseTimeout(Duration leaseTimeout) {
            this.leaseTimeout = leaseTimeout;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    public static class Cleanup {
        /** Completed jobs older than this many days are deleted. */
        private int retentionDays = 7;

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }
    }

    public static class Pipeline {
        /** Number of tokens per chunk. */
        private int chunkSize = 400;
        /** Number of overlapping tokens between consecutive chunks. */
        private int chunkOverlap = 50;
        /** Maximum file size in megabytes; larger files are skipped. */
        private int maxFileSizeMb = 50;
        /** MIME types supported for text extraction. */
        private java.util.List<String> supportedMimeTypes = java.util.List.of("application/pdf");

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public int getMaxFileSizeMb() {
            return maxFileSizeMb;
        }

        public void setMaxFileSizeMb(int maxFileSizeMb) {
            this.maxFileSizeMb = maxFileSizeMb;
        }

        public java.util.List<String> getSupportedMimeTypes() {
            return supportedMimeTypes;
        }

        public void setSupportedMimeTypes(java.util.List<String> supportedMimeTypes) {
            this.supportedMimeTypes = supportedMimeTypes;
        }
    }
}
