package com.dev.semsearch.search.authority;

import com.dev.semsearch.common.alfresco.AlfrescoProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;

/**
 * REST client for retrieving user group memberships from Alfresco v1 API.
 * Calls {@code /api/-default-/public/alfresco/versions/1/people/{personId}/groups}.
 */
@Component
public class AlfrescoAuthorityClient {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoAuthorityClient.class);

    private final RestClient restClient;

    public AlfrescoAuthorityClient(AlfrescoProperties props) {
        String credentials = props.getUsername() + ":" + props.getPassword();
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

        String apiBase = props.getBaseUrl().replaceAll("/+$", "")
                + "/alfresco/api/-default-/public/alfresco/versions/1";

        this.restClient = RestClient.builder()
                .baseUrl(apiBase)
                .defaultHeader("Authorization", basicAuth)
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    log.warn("Alfresco client 4xx response: status={} uri={}", resp.getStatusCode(), req.getURI());
                })
                .build();
    }

    /**
     * Retrieves all group IDs that the given user belongs to directly or indirectly.
     *
     * @param username the Alfresco username
     * @return list of group IDs (e.g. {@code ["GROUP_ENGINEERING", "GROUP_EVERYONE"]})
     */
    public List<String> getUserGroups(String username) {
        try {
            GroupListResponse response = restClient.get()
                    .uri("/people/{personId}/groups?maxItems=1000", username)
                    .retrieve()
                    .body(GroupListResponse.class);

            if (response == null || response.list == null || response.list.entries == null) {
                return List.of();
            }

            return response.list.entries.stream()
                    .filter(e -> e.entry != null && e.entry.id != null)
                    .map(e -> e.entry.id)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch groups for user {}: {}", username, e.getMessage());
            return List.of();
        }
    }

    // ── JSON mapping classes ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class GroupListResponse {
        @JsonProperty("list")
        public GroupList list;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class GroupList {
        @JsonProperty("entries")
        public List<GroupEntryWrapper> entries;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class GroupEntryWrapper {
        @JsonProperty("entry")
        public GroupEntry entry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class GroupEntry {
        @JsonProperty("id")
        public String id;

        @JsonProperty("displayName")
        public String displayName;
    }
}
