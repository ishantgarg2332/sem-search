package com.dev.semsearch.common.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class ElasticsearchIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexInitializer.class);
    public static final String ALIAS_NAME = "doc-chunks";
    public static final String INDEX_NAME = "doc-chunks-v1";
    public static final String MAPPING_RESOURCE = "es/doc-chunks-v1-mapping.json";

    private final ElasticsearchClient client;

    public ElasticsearchIndexInitializer(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Checking if Elasticsearch alias '{}' exists...", ALIAS_NAME);
        boolean aliasExists = client.indices().existsAlias(a -> a.name(ALIAS_NAME)).value();
        if (aliasExists) {
            log.info("Elasticsearch alias '{}' already exists. Skipping initialization.", ALIAS_NAME);
            return;
        }

        log.info("Alias '{}' does not exist. Creating index '{}' with alias '{}'...", ALIAS_NAME, INDEX_NAME, ALIAS_NAME);
        ClassPathResource resource = new ClassPathResource(MAPPING_RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException("Mapping resource not found on classpath: " + MAPPING_RESOURCE);
        }

        try (InputStream is = resource.getInputStream()) {
            client.indices().create(c -> c
                    .index(INDEX_NAME)
                    .aliases(ALIAS_NAME, a -> a.isWriteIndex(true))
                    .withJson(is)
            );
            log.info("Successfully created index '{}' with alias '{}'.", INDEX_NAME, ALIAS_NAME);
        } catch (ElasticsearchException e) {
            String errorType = (e.error() != null) ? e.error().type() : "";
            if ("resource_already_exists_exception".equals(errorType)) {
                log.warn("Index '{}' already exists (resource_already_exists_exception). Re-checking alias '{}'...",
                        INDEX_NAME, ALIAS_NAME);
                boolean aliasPresent = client.indices().existsAlias(a -> a.name(ALIAS_NAME)).value();
                if (!aliasPresent) {
                    throw new IllegalStateException(
                            "Index '" + INDEX_NAME + "' exists but alias '" + ALIAS_NAME + "' is missing. "
                            + "Refusing to start to avoid Elasticsearch auto-creating an unmapped index."
                    );
                }
                log.info("Confirmed alias '{}' exists after concurrent creation.", ALIAS_NAME);
            } else {
                log.error("Failed to create index '{}' with mapping from {}: {}", INDEX_NAME, MAPPING_RESOURCE, e.getMessage(), e);
                throw e;
            }
        }
    }
}
