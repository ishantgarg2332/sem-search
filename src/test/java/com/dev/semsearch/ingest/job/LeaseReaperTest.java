package com.dev.semsearch.ingest.job;

import com.dev.semsearch.ingest.IngestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaseReaperTest {

    @Mock
    private JobRepository jobRepository;

    private SimpleMeterRegistry registry;
    private LeaseReaper reaper;
    private IngestProperties properties;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        properties = new IngestProperties();
        properties.getWorker().setLeaseTimeout(Duration.ofMinutes(15));
        reaper = new LeaseReaper(jobRepository, properties, registry);
    }

    @Test
    void reapedJobsAreCountedInTheMetric() {
        when(jobRepository.reapExpiredLeases(Duration.ofMinutes(15), 5)).thenReturn(3);

        reaper.reap();

        assertThat(registry.get("semsearch.ingest.leases.expired").counter().count()).isEqualTo(3.0);
    }

    @Test
    void nothingReapedLeavesTheMetricAtZero() {
        when(jobRepository.reapExpiredLeases(Duration.ofMinutes(15), 5)).thenReturn(0);

        reaper.reap();

        assertThat(registry.get("semsearch.ingest.leases.expired").counter().count()).isZero();
    }
}
