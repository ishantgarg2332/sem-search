package com.dev.semsearch.common.alfresco;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST client for user management operations against the Alfresco Content Services v1 REST API.
 * Uses admin credentials to create and query users and their group memberships.
 */
@Component
public class AlfrescoUserClient {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoUserClient.class);
    private static final String API_PATH = "/alfresco/api/-default-/public/alfresco/versions/1";
    public static final String ADMIN_GROUP = "GROUP_ALFRESCO_ADMINISTRATORS";

    private final RestClient restClient;

    public AlfrescoUserClient(AlfrescoProperties props) {
        String credentials = Base64.getEncoder().encodeToString(
                (props.getUsername() + ":" + props.getPassword()).getBytes());

        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl().replaceAll("/+$", "") + API_PATH)
                .defaultHeader("Authorization", "Basic " + credentials)
                .build();
    }

    /**
     * Creates a new user in Alfresco.
     * When created, Alfresco automatically associates the user with GROUP_EVERYONE.
     *
     * @throws UserAlreadyExistsException if username or email already exists
     */
    public AlfrescoPerson createUser(String username, String password, String firstName, String lastName, String email) {
        try {
            Map<String, Object> body = Map.of(
                    "id", username,
                    "password", password,
                    "firstName", firstName != null && !firstName.isBlank() ? firstName : username,
                    "lastName", lastName != null ? lastName : "",
                    "email", email,
                    "enabled", true
            );

            PersonResponse response = restClient.post()
                    .uri("/people")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PersonResponse.class);

            if (response == null || response.entry == null) {
                throw new IllegalStateException("Alfresco user creation returned empty response");
            }

            log.info("Created new Alfresco user: {}", username);
            return response.entry.toModel();
        } catch (HttpClientErrorException.Conflict e) {
            log.warn("User already exists in Alfresco: {}", username);
            throw new UserAlreadyExistsException("User '" + username + "' or email already exists");
        } catch (HttpClientErrorException e) {
            log.error("Failed to create user in Alfresco (status {}): {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new UserAlreadyExistsException("User '" + username + "' or email already exists");
            }
            throw new RuntimeException("Alfresco user creation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Checks if a user exists in Alfresco.
     */
    public boolean userExists(String username) {
        return getUser(username).isPresent();
    }

    /**
     * Retrieves user profile details.
     */
    public Optional<AlfrescoPerson> getUser(String username) {
        try {
            PersonResponse response = restClient.get()
                    .uri("/people/{personId}", username)
                    .retrieve()
                    .body(PersonResponse.class);

            if (response == null || response.entry == null) {
                return Optional.empty();
            }
            return Optional.of(response.entry.toModel());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to fetch user {}: {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Checks if user has admin privileges.
     * Alfresco's primary admin is 'admin', and users in GROUP_ALFRESCO_ADMINISTRATORS are admins.
     */
    public boolean isAdmin(String username) {
        if ("admin".equalsIgnoreCase(username)) {
            return true;
        }
        try {
            GroupListResponse response = restClient.get()
                    .uri("/people/{personId}/groups?maxItems=1000", username)
                    .retrieve()
                    .body(GroupListResponse.class);

            if (response != null && response.list != null && response.list.entries != null) {
                return response.list.entries.stream()
                        .anyMatch(e -> e.entry != null && ADMIN_GROUP.equalsIgnoreCase(e.entry.id));
            }
        } catch (Exception e) {
            log.error("Failed to check admin status for {}: {}", username, e.getMessage());
        }
        return false;
    }

    public record AlfrescoPerson(
            String id,
            String firstName,
            String lastName,
            String email,
            boolean enabled
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PersonResponse {
        @JsonProperty("entry")
        public PersonEntry entry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PersonEntry {
        public String id;
        public String firstName;
        public String lastName;
        public String email;
        public Boolean enabled;

        public AlfrescoPerson toModel() {
            return new AlfrescoPerson(
                    id,
                    firstName != null ? firstName : "",
                    lastName != null ? lastName : "",
                    email != null ? email : "",
                    enabled != null ? enabled : true
            );
        }
    }

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
    }
}
