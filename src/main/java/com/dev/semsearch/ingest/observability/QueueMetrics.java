package com.dev.semsearch.ingest.observability;

import com.dev.semsearch.ingest.job.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Publishes Micrometer metrics for monitoring the durable ingestion queue.
 * Provides gauges for PENDING, RUNNING, and FAILED jobs for Prometheus/Actuator.
 */
@Component
public class QueueMetrics {

    private final JobRepository jobRepository;
    private final MeterRegistry meterRegistry;

    public QueueMetrics(JobRepository jobRepository, MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("semsearch.ingest.jobs.pending", () -> jobRepository.countByStatus("PENDING"))
                .description("Number of jobs currently pending in the ingestion queue")
                .register(meterRegistry);

        Gauge.builder("semsearch.ingest.jobs.running", () -> jobRepository.countByStatus("RUNNING"))
                .description("Number of jobs currently running in the ingestion queue")
                .register(meterRegistry);

        Gauge.builder("semsearch.ingest.jobs.failed", () -> jobRepository.countByStatus("FAILED"))
                .description("Number of permanently failed jobs in the ingestion queue")
                .register(meterRegistry);
    }
}
