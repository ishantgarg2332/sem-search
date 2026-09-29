package com.dev.semsearch.common.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ElasticsearchMappingTest {

    @Autowired
    private ElasticsearchClient client;

    private String testIndex;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        testIndex = "doc-chunks-test-" + UUID.randomUUID();
        ClassPathResource resource = new ClassPathResource("es/doc-chunks-v1-mapping.json");
        try (InputStream is = resource.getInputStream()) {
            client.indices().create(c -> c
                    .index(testIndex)
                    .withJson(is)
            );
        }
    }

    @AfterEach
    void tearDown() {
        if (testIndex != null) {
            try {
                client.indices().delete(d -> d.index(testIndex));
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void testUnknownFieldRejectedByStrictDynamicMapping() {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("node_id", "test-node");
        doc.put("unknown_field", "some value");

        assertThatThrownBy(() -> client.index(i -> i
                .index(testIndex)
                .id("test-chunk-unknown")
                .document(doc)
        )).isInstanceOf(ElasticsearchException.class)
          .satisfies(e -> {
              ElasticsearchException ee = (ElasticsearchException) e;
              assertThat(ee.error().type()).contains("strict_dynamic_mapping_exception");
          });
    }

    @Test
    void testIncorrectVectorDimensionsRejected() {
        // Test 767 dimensions (expected 768)
        ObjectNode doc767 = createBaseChunk("node-1", 0);
        ArrayNode vec767 = doc767.putArray("embedding");
        for (int i = 0; i < 767; i++) {
            vec767.add(0.01f);
        }

        assertThatThrownBy(() -> client.index(i -> i
                .index(testIndex)
                .id("test-chunk-767")
                .document(doc767)
        )).isInstanceOf(ElasticsearchException.class);

        // Test 769 dimensions (expected 768)
        ObjectNode doc769 = createBaseChunk("node-1", 0);
        ArrayNode vec769 = doc769.putArray("embedding");
        for (int i = 0; i < 769; i++) {
            vec769.add(0.01f);
        }

        assertThatThrownBy(() -> client.index(i -> i
                .index(testIndex)
                .id("test-chunk-769")
                .document(doc769)
        )).isInstanceOf(ElasticsearchException.class);
    }

    @Test
    void testReadersKeywordFieldNotAnalyzed() throws Exception {
        ObjectNode validDoc = createBaseChunk("node-perm", 0);
        ArrayNode readers = validDoc.putArray("readers");
        readers.add("GROUP_finance");
        readers.add("alice");

        ArrayNode vec768 = validDoc.putArray("embedding");
        for (int i = 0; i < 768; i++) {
            vec768.add(0.01f);
        }

        IndexResponse indexResp = client.index(i -> i
                .index(testIndex)
                .id("node-perm_0000")
                .document(validDoc)
        );
        assertThat(indexResp.result().name()).isEqualTo("Created");

        // Refresh index to make document searchable immediately
        client.indices().refresh(r -> r.index(testIndex));

        // Exact term match for GROUP_finance -> must be found
        SearchResponse<ObjectNode> exactSearch = client.search(s -> s
                .index(testIndex)
                .query(q -> q.term(t -> t.field("readers").value("GROUP_finance"))),
                ObjectNode.class
        );
        assertThat(exactSearch.hits().total().value()).isEqualTo(1);

        // Lowercase term search for group_finance -> must NOT be found (proves non-analyzed exact match)
        SearchResponse<ObjectNode> lowerSearch = client.search(s -> s
                .index(testIndex)
                .query(q -> q.term(t -> t.field("readers").value("group_finance"))),
                ObjectNode.class
        );
        assertThat(lowerSearch.hits().total().value()).isEqualTo(0);

        // Substring term search for finance -> must NOT be found
        SearchResponse<ObjectNode> subSearch = client.search(s -> s
                .index(testIndex)
                .query(q -> q.term(t -> t.field("readers").value("finance"))),
                ObjectNode.class
        );
        assertThat(subSearch.hits().total().value()).isEqualTo(0);
    }

    private ObjectNode createBaseChunk(String nodeId, int chunkIndex) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("node_id", nodeId);
        doc.put("version_label", "1.0");
        doc.put("chunk_index", chunkIndex);
        doc.put("name", "sample.pdf");
        doc.put("path", "/Company Home/Sites");
        doc.put("mime_type", "application/pdf");
        doc.put("modified_at", "2026-09-17T12:00:00Z");
        doc.put("text", "This is an example chunk of document text.");
        return doc;
    }
}
