package com.dev.semsearch.ingest.reconciliation;

import com.dev.semsearch.ingest.alfresco.AlfrescoClient;
import com.dev.semsearch.ingest.alfresco.NodeInfo;
import com.dev.semsearch.ingest.job.IngestJob;
import com.dev.semsearch.ingest.job.JobRepository;
import com.dev.semsearch.ingest.pipeline.NodeStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrphanReconciliationServiceTest {

    @Mock
    private NodeStateRepository nodeStateRepository;

    @Mock
    private AlfrescoClient alfrescoClient;

    @Mock
    private JobRepository jobRepository;

    private OrphanReconciliationService orphanService;

    @BeforeEach
    void setUp() {
        orphanService = new OrphanReconciliationService(nodeStateRepository, alfrescoClient, jobRepository);
    }

    @Test
    void testReconcileOrphansIdentifiesAndEnqueuesDeletesForMissingNodes() {
        when(nodeStateRepository.findAllNodeIds())
                .thenReturn(List.of("node-alive", "node-dead"));

        NodeInfo aliveInfo = new NodeInfo(
                "node-alive", "alive.pdf", "cm:content", "application/pdf",
                1024L, Instant.now(), "1.0", "admin", "/Company Home", null
        );

        when(alfrescoClient.getNode("node-alive")).thenReturn(Optional.of(aliveInfo));
        when(alfrescoClient.getNode("node-dead")).thenReturn(Optional.empty());

        int count = orphanService.reconcileOrphans();

        assertThat(count).isEqualTo(1);
        verify(jobRepository, never()).enqueue("node-alive", IngestJob.ACTION_DELETE);
        verify(jobRepository).enqueue("node-dead", IngestJob.ACTION_DELETE);
    }
}
