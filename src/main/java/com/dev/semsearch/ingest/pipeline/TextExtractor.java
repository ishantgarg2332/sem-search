package com.dev.semsearch.ingest.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts text from document files using Apache Tika via Spring AI's
 * {@link TikaDocumentReader}. Supports all formats Tika handles, but the
 * pipeline filters to supported MIME types before calling this.
 */
@Component
public class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    /**
     * Extracts all text content from the given file.
     *
     * @param filePath path to the file to extract text from
     * @return the extracted text as a single string
     */
    public String extractText(Path filePath) {
        Resource resource = new FileSystemResource(filePath);
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> docs = reader.get();
        String text = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));
        log.debug("Extracted {} characters from {}", text.length(), filePath.getFileName());
        return text;
    }
}
