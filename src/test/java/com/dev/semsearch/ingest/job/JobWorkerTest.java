package com.dev.semsearch.ingest.job;

import com.dev.semsearch.ingest.IngestProperties;
import com.dev.semsearch.ingest.pipeline.ContentProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobWorkerTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private ContentProcessor contentProcessor;

    private JobWorker worker;

    private final UUID token = UUID.randomUUID();
    private final Instant claimedAt = Instant.parse("2026-09-28T10:00:00Z");

    @BeforeEach
    void setUp() {
        worker = new JobWorker(jobRepository, new IngestProperties(), contentProcessor);
    }

    @Test
    void finishedJobIsMarkedDoneWithItsClaimToken() throws Exception {
        IngestJob job = job("UPSERT", claimedAt);
        when(jobRepository.markDone(1L, token)).thenReturn(Optional.of(job("UPSERT", claimedAt)));

        worker.processJob(job);

        verify(contentProcessor).processUpsert("node-1");
        verify(jobRepository).markDone(1L, token);
        verify(jobRepository, never()).enqueue(anyString(), anyString());
    }

    @Test
    void lostLeaseMeansNoFollowUpWork() throws Exception {
        when(jobRepository.markDone(1L, token)).thenReturn(Optional.empty());

        worker.processJob(job("UPSERT", claimedAt));

        verify(jobRepository, never()).enqueue(anyString(), anyString());
    }

    @Test
    void eventDuringProcessingIsRequeuedWithTheLatestAction() throws Exception {
        // Claimed as UPSERT; a delete event arrived mid-processing, so the row now says DELETE
        // and its updated_at is later than claimed_at.
        IngestJob afterDone = new IngestJob(1L, "node-1", "DELETE", "DONE", 1, null,
                claimedAt, claimedAt, claimedAt, claimedAt.plusSeconds(5), token);
        when(jobRepository.markDone(1L, token)).thenReturn(Optional.of(afterDone));

        worker.processJob(job("UPSERT", claimedAt));

        verify(jobRepository).enqueue("node-1", "DELETE");
    }

    @Test
    void failureIsRecordedWithTheClaimToken() throws Exception {
        doThrow(new IOException("Ollama unreachable")).when(contentProcessor).processUpsert("node-1");

        worker.processJob(job("UPSERT", claimedAt));

        verify(jobRepository).markFailed(1L, token, "Ollama unreachable", 5);
        verify(jobRepository, never()).markDone(1L, token);
    }

    private IngestJob job(String action, Instant updatedAt) {
        return new IngestJob(1L, "node-1", action, "RUNNING", 1, null,
                claimedAt, claimedAt, claimedAt, updatedAt, token);
    }
}
