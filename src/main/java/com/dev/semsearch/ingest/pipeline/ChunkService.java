package com.dev.semsearch.ingest.pipeline;

import com.dev.semsearch.ingest.IngestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Splits extracted text into token-counted chunks using Spring AI's
 * {@link TokenTextSplitter}. Chunk size and overlap are configurable
 * via {@link IngestProperties.Pipeline}.
 */
@Component
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    private final IngestProperties properties;

    public ChunkService(IngestProperties properties) {
        this.properties = properties;
    }

    /**
     * Splits text into chunks of the configured token size with overlap.
     *
     * @param text the full document text
     * @return list of chunk text strings
     */
    public List<String> chunk(String text) {
        TokenTextSplitter splitter = new TokenTextSplitter(
                properties.getPipeline().getChunkSize(),
                properties.getPipeline().getChunkOverlap(),
                5,     // minChunkSizeChars — minimum characters for a chunk to be kept
                10000, // maxNumChunks — safety limit
                true   // keepSeparator
        );

        List<Document> docs = splitter.apply(List.of(new Document(text)));
        List<String> chunks = docs.stream().map(Document::getText).toList();
        log.debug("Split text into {} chunk(s) (size={}, overlap={})",
                chunks.size(),
                properties.getPipeline().getChunkSize(),
                properties.getPipeline().getChunkOverlap());
        return chunks;
    }
}
