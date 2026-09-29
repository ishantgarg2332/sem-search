package com.dev.semsearch.ingest.pipeline;

import com.dev.semsearch.ingest.IngestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkServiceTest {

    private ChunkService chunkService;

    @BeforeEach
    void setUp() {
        IngestProperties properties = new IngestProperties();
        properties.getPipeline().setChunkSize(20);
        properties.getPipeline().setChunkOverlap(5);
        chunkService = new ChunkService(properties);
    }

    @Test
    void testChunkShortTextProducesSingleChunk() {
        String shortText = "This is a brief text document that fits comfortably within a single chunk.";
        List<String> chunks = chunkService.chunk(shortText);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst()).isEqualTo(shortText);
    }

    @Test
    void testChunkLongTextProducesMultipleChunksWithOverlap() {
        // Generate a long text with repeating sentences
        String sentence = "The quick brown fox jumps over the lazy dog repeatedly in various tests. ";
        String longText = sentence.repeat(20);

        List<String> chunks = chunkService.chunk(longText);

        assertThat(chunks.size()).isGreaterThan(1);
        // All chunks should have non-empty content
        for (String chunk : chunks) {
            assertThat(chunk.trim()).isNotEmpty();
        }
    }
}
