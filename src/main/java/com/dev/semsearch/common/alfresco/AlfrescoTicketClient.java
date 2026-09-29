package com.dev.semsearch.common.alfresco;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Component
public class AlfrescoTicketClient {

    private static final String AUTH_PATH = "/alfresco/api/-default-/public/authentication/versions/1";

    private final RestClient authClient;

    public AlfrescoTicketClient(AlfrescoProperties props) {
        // No default Authorization header: logging in is the one call that needs none.
        this.authClient = RestClient.builder()
                .baseUrl(props.getBaseUrl().replaceAll("/+$", "") + AUTH_PATH)
                .build();
    }

    /**
     * Returns the ticket if Alfresco accepts the credentials, empty if it rejects
     * them.
     */
    public Optional<String> createTicket(String username, String password) {
        try {
            TicketResponse response = authClient.post()
                    .uri("/tickets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("userId", username, "password", password))
                    .retrieve()
                    .body(TicketResponse.class);

            if (response == null || response.entry == null || response.entry.id == null) {
                throw new IllegalStateException("Alfresco login returned no ticket");
            }
            return Optional.of(response.entry.id);

        } catch (HttpClientErrorException e) {
            // 4xx = Alfresco rejected the credentials (403 for a wrong password).
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TicketResponse {
        public TicketEntry entry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TicketEntry {
        public String id;
        public String userId;
    }
}
