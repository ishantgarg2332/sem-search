package com.dev.semsearch.search.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Micrometer metrics helper for measuring search latencies across stages
 * (keyword search, kNN vector search, query embedding, total search request).
 */
@Component
public class SearchMetrics {

    private final Timer keywordSearchTimer;
    private final Timer vectorSearchTimer;
    private final Timer queryEmbeddingTimer;
    private final Timer totalSearchTimer;

    public SearchMetrics(MeterRegistry registry) {
        this.keywordSearchTimer = Timer.builder("semsearch.search.keyword.duration")
                .description("Latency of BM25 keyword searches in Elasticsearch")
                .publishPercentileHistogram()
                .register(registry);

        this.vectorSearchTimer = Timer.builder("semsearch.search.vector.duration")
                .description("Latency of kNN vector searches in Elasticsearch")
                .publishPercentileHistogram()
                .register(registry);

        this.queryEmbeddingTimer = Timer.builder("semsearch.embedding.query.duration")
                .description("Latency of query vector embeddings via Ollama")
                .publishPercentileHistogram()
                .register(registry);

        this.totalSearchTimer = Timer.builder("semsearch.search.total.duration")
                .description("Total end-to-end latency of search requests")
                .publishPercentileHistogram()
                .register(registry);
    }

    public <T> T recordKeyword(Callable<T> callable) throws Exception {
        return keywordSearchTimer.recordCallable(callable);
    }

    public <T> T recordVector(Callable<T> callable) throws Exception {
        return vectorSearchTimer.recordCallable(callable);
    }

    public <T> T recordEmbedding(Supplier<T> supplier) {
        return queryEmbeddingTimer.record(supplier);
    }

    public Timer getTotalSearchTimer() {
        return totalSearchTimer;
    }
}
