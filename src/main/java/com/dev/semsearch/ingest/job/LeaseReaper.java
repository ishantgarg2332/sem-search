package com.dev.semsearch.ingest.job;

import com.dev.semsearch.ingest.IngestProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute, returns jobs whose worker died mid-processing to the queue.
 * The SQL lives in JobRepository.reapExpiredLeases(); this class only decides
 * when to run it and reports what happened.
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final JobRepository jobRepository;
    private final IngestProperties properties;
    private final Counter expiredLeases;

    public LeaseReaper(JobRepository jobRepository,
                       IngestProperties properties,
                       MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.expiredLeases = Counter.builder("semsearch.ingest.leases.expired")
                .description("Jobs returned to the queue because their worker never finished")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 60_000)
    public void reap() {
        int reaped = jobRepository.reapExpiredLeases(
                properties.getWorker().getLeaseTimeout(),
                properties.getWorker().getMaxAttempts());

        if (reaped > 0) {
            log.warn("Reaped {} job(s) whose lease expired (worker crashed or took longer than {})",
                    reaped, properties.getWorker().getLeaseTimeout());
            expiredLeases.increment(reaped);
        }
    }
}
