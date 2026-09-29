package com.dev.semsearch.ingest.admin;

import com.dev.semsearch.ingest.job.IngestJob;
import com.dev.semsearch.ingest.job.JobRepository;
import com.dev.semsearch.ingest.reconciliation.ContentReconciliationService;
import com.dev.semsearch.ingest.reconciliation.OrphanReconciliationService;
import com.dev.semsearch.ingest.reconciliation.PermissionDriftService;
import com.dev.semsearch.search.config.SearchSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminJobController.class)
@Import(SearchSecurityConfig.class)
class AdminJobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobRepository jobRepository;

    @MockitoBean
    private ContentReconciliationService contentService;

    @MockitoBean
    private OrphanReconciliationService orphanService;

    @MockitoBean
    private PermissionDriftService permissionService;

    @Test
    void testAdminEndpointsUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/admin/jobs/failed"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/jobs/requeue"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testAdminEndpointsForbiddenForNonAdminUser() throws Exception {
        // "alice" has role USER, not ADMIN
        mockMvc.perform(get("/api/admin/jobs/failed")
                        .with(user("alice").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/jobs/requeue")
                        .with(user("alice").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void testListFailedJobsAllowedForAdmin() throws Exception {
        IngestJob failedJob = new IngestJob(
                1L, "node-fail", "UPSERT", "FAILED", 5, "Connection refused",
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), null
        );
        when(jobRepository.findFailedJobs(50)).thenReturn(List.of(failedJob));

        mockMvc.perform(get("/api/admin/jobs/failed")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nodeId").value("node-fail"))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].lastError").value("Connection refused"));
    }

    @Test
    void testRequeueFailedJobsAllowedForAdmin() throws Exception {
        when(jobRepository.requeueFailedJobs()).thenReturn(3);

        mockMvc.perform(post("/api/admin/jobs/requeue")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.requeuedCount").value(3));
    }

    @Test
    void testTriggerContentReconciliationAllowedForAdmin() throws Exception {
        when(contentService.reconcileContent()).thenReturn(5);

        mockMvc.perform(post("/api/admin/reconcile/content")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").value("CONTENT_RECONCILIATION"))
                .andExpect(jsonPath("$.enqueuedCount").value(5));
    }
}
