package com.dev.semsearch.search.api;

import com.dev.semsearch.common.alfresco.AlfrescoProperties;
import com.dev.semsearch.search.authority.AuthorityService;
import com.dev.semsearch.search.config.SearchSecurityConfig;
import com.dev.semsearch.search.embedding.QueryEmbeddingService;
import com.dev.semsearch.search.engine.ElasticsearchSearchService;
import com.dev.semsearch.search.engine.SearchHit;
import com.dev.semsearch.search.fusion.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
@Import(SearchSecurityConfig.class)
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthorityService authorityService;

    @MockitoBean
    private QueryEmbeddingService embeddingService;

    @MockitoBean
    private ElasticsearchSearchService searchService;

    @MockitoBean
    private ReciprocalRankFusion rankFusion;

    @MockitoBean
    private AlfrescoProperties alfrescoProperties;

    @Test
    void testSearchWithoutAuthenticationReturns401() throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("q", "loan policy"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testSearchWithEmptyQueryReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/search")
                        .with(user("admin").roles("USER", "ADMIN"))
                        .param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void testSearchWithValidQueryReturnsRankedResultsWithAlfrescoLinks() throws Exception {
        when(authorityService.getAuthoritiesForUser("alice"))
                .thenReturn(List.of("alice", "GROUP_EVERYONE"));

        when(embeddingService.embedQuery("repayment rules"))
                .thenReturn(new float[]{0.1f, 0.2f});

        SearchHit hit = new SearchHit(
                "node-123_0", "node-123", 0, "Loan_Rules.pdf", "/Sites/finance", "Penalty break costs apply.", 1.5
        );
        when(searchService.searchKeyword(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(hit));
        when(searchService.searchVector(any(), anyList(), anyInt()))
                .thenReturn(List.of(hit));

        when(rankFusion.fuseAndCollapse(anyList(), anyList(), anyInt()))
                .thenReturn(List.of(
                        new ReciprocalRankFusion.FusedDocument(
                                "node-123",
                                "Loan_Rules.pdf",
                                "/Sites/finance",
                                "Penalty break costs apply.",
                                0,
                                0.0327
                        )
                ));

        mockMvc.perform(get("/api/search")
                        .with(user("alice").roles("USER"))
                        .param("q", "repayment rules")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("node-123"))
                .andExpect(jsonPath("$[0].name").value("Loan_Rules.pdf"))
                .andExpect(jsonPath("$[0].path").value("/Sites/finance"))
                .andExpect(jsonPath("$[0].snippet").value("Penalty break costs apply."))
                .andExpect(jsonPath("$[0].score").value(0.0327))
                // Links go through the permission-checked proxy, never straight to Alfresco,
                // so the browser never sees Alfresco's address and every download is checked.
                .andExpect(jsonPath("$[0].alfrescoLink").value("/api/documents/node-123/content"));
    }
}
