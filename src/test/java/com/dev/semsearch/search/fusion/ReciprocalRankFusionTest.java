package com.dev.semsearch.search.fusion;

import com.dev.semsearch.search.engine.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReciprocalRankFusionTest {

    private ReciprocalRankFusion rrf;

    @BeforeEach
    void setUp() {
        rrf = new ReciprocalRankFusion();
    }

    private SearchHit createHit(String id, String nodeId, int chunkIndex, String name, String text) {
        return new SearchHit(id, nodeId, chunkIndex, name, "/Sites/finance", text, 1.0);
    }

    @Test
    void testExactRrfScoreCalculation() {
        // Doc 1 at rank 1 in BM25 only: score should be 1.0 / (60 + 1) = 1/61
        SearchHit hit1 = createHit("doc1_0", "doc1", 0, "Doc 1", "Content 1");
        List<ReciprocalRankFusion.FusedDocument> results = rrf.fuseAndCollapse(
                List.of(hit1),
                List.of(),
                10
        );

        assertThat(results).hasSize(1);
        double expectedScore = 1.0 / 61.0;
        assertThat(results.getFirst().score()).isCloseTo(expectedScore, within(1e-6));
    }

    @Test
    void testDualListRrfScoreAccumulation() {
        // Chunk appearing in BOTH lists: rank 1 in BM25, rank 2 in kNN
        // score should be: 1.0 / (60 + 1) + 1.0 / (60 + 2) = 1/61 + 1/62
        SearchHit hitA = createHit("docA_0", "docA", 0, "Doc A", "Content A");
        SearchHit hitB = createHit("docB_0", "docB", 0, "Doc B", "Content B");

        List<SearchHit> keywordHits = List.of(hitA, hitB);
        List<SearchHit> vectorHits = List.of(hitB, hitA);

        List<ReciprocalRankFusion.FusedDocument> results = rrf.fuseAndCollapse(keywordHits, vectorHits, 10);

        // hitA: 1/61 + 1/62
        // hitB: 1/62 + 1/61 -> identical combined score
        assertThat(results).hasSize(2);
        double expectedCombined = (1.0 / 61.0) + (1.0 / 62.0);
        assertThat(results.get(0).score()).isCloseTo(expectedCombined, within(1e-6));
        assertThat(results.get(1).score()).isCloseTo(expectedCombined, within(1e-6));
    }

    @Test
    void testChunkCollapsingChoosesHighestScoringChunkPerDocument() {
        // Document "docX" has two chunks:
        // docX_0 is at rank 1 in BM25 (score = 1/61)
        // docX_1 is at rank 5 in BM25 (score = 1/65)
        SearchHit chunk0 = createHit("docX_0", "docX", 0, "Doc X", "Top intro snippet");
        SearchHit chunk1 = createHit("docX_1", "docX", 1, "Doc X", "Secondary text");

        List<SearchHit> keywordHits = List.of(chunk0, chunk1);

        List<ReciprocalRankFusion.FusedDocument> results = rrf.fuseAndCollapse(keywordHits, List.of(), 10);

        // Should collapse to 1 document result with the best chunk (chunk0)
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().nodeId()).isEqualTo("docX");
        assertThat(results.getFirst().bestChunkIndex()).isEqualTo(0);
        assertThat(results.getFirst().snippet()).isEqualTo("Top intro snippet");
        assertThat(results.getFirst().score()).isCloseTo(1.0 / 61.0, within(1e-6));
    }

    @Test
    void testRankingOrderAndLimit() {
        SearchHit hit1 = createHit("doc1_0", "doc1", 0, "Doc 1", "Content 1");
        SearchHit hit2 = createHit("doc2_0", "doc2", 0, "Doc 2", "Content 2");
        SearchHit hit3 = createHit("doc3_0", "doc3", 0, "Doc 3", "Content 3");

        // doc2 is rank 1 in both lists -> highest score
        // doc1 is rank 2 in BM25
        // doc3 is rank 3 in BM25
        List<SearchHit> keywordHits = List.of(hit2, hit1, hit3);
        List<SearchHit> vectorHits = List.of(hit2);

        List<ReciprocalRankFusion.FusedDocument> results = rrf.fuseAndCollapse(keywordHits, vectorHits, 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).nodeId()).isEqualTo("doc2");
        assertThat(results.get(1).nodeId()).isEqualTo("doc1");
    }

    @Test
    void testEmptyAndNullLists() {
        List<ReciprocalRankFusion.FusedDocument> r1 = rrf.fuseAndCollapse(null, null, 10);
        assertThat(r1).isEmpty();

        List<ReciprocalRankFusion.FusedDocument> r2 = rrf.fuseAndCollapse(List.of(), List.of(), 10);
        assertThat(r2).isEmpty();
    }
}
