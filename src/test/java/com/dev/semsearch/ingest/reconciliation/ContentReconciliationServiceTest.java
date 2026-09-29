package com.dev.semsearch.ingest.reconciliation;

import com.dev.semsearch.ingest.job.IngestJob;
import com.dev.semsearch.ingest.job.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentReconciliationServiceTest {

    @Mock
    private AlfrescoSearchClient searchClient;

    @Mock
    private ReconciliationStateRepository stateRepository;

    @Mock
    private JobRepository jobRepository;

    private ContentReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        reconciliationService = new ContentReconciliationService(searchClient, stateRepository, jobRepository);
    }

    @Test
    void testReconcileContentEnqueuesUpsertJobsForModifiedNodes() {
        Instant lastRun = Instant.now().minus(2, ChronoUnit.HOURS);
        when(stateRepository.getLastRunAt(ReconciliationStateRepository.TASK_CONTENT))
                .thenReturn(Optional.of(lastRun));

        when(searchClient.findNodesModifiedSince(lastRun))
                .thenReturn(List.of("node-1", "node-2"));

        int count = reconciliationService.reconcileContent();

        assertThat(count).isEqualTo(2);
        verify(jobRepository).enqueue("node-1", IngestJob.ACTION_UPSERT);
        verify(jobRepository).enqueue("node-2", IngestJob.ACTION_UPSERT);
        verify(stateRepository).setLastRunAt(eq(ReconciliationStateRepository.TASK_CONTENT), any(Instant.class));
    }

    @Test
    void testReconcileContentWhenNoChangesFound() {
        Instant lastRun = Instant.now().minus(30, ChronoUnit.MINUTES);
        when(stateRepository.getLastRunAt(ReconciliationStateRepository.TASK_CONTENT))
                .thenReturn(Optional.of(lastRun));

        when(searchClient.findNodesModifiedSince(lastRun))
                .thenReturn(List.of());

        int count = reconciliationService.reconcileContent();

        assertThat(count).isEqualTo(0);
        verify(stateRepository).setLastRunAt(eq(ReconciliationStateRepository.TASK_CONTENT), any(Instant.class));
    }

    @Test
    void testFailedSearchDoesNotAdvanceHighWaterMark() {
        Instant lastRun = Instant.now().minus(3, ChronoUnit.HOURS);
        when(stateRepository.getLastRunAt(ReconciliationStateRepository.TASK_CONTENT))
                .thenReturn(Optional.of(lastRun));

        when(searchClient.findNodesModifiedSince(lastRun))
                .thenThrow(new IllegalStateException("Alfresco unreachable"));

        assertThatThrownBy(() -> reconciliationService.reconcileContent())
                .isInstanceOf(IllegalStateException.class);

        // The window must be retried next time, so the mark stays where it was.
        verify(stateRepository, never()).setLastRunAt(any(), any());
        verifyNoInteractions(jobRepository);
    }
}
