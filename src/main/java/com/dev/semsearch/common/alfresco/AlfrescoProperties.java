package com.dev.semsearch.common.alfresco;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for connecting to the Alfresco Content Services REST API.
 * Shared across ingest and search modules.
 */
@ConfigurationProperties(prefix = "alfresco")
public class AlfrescoProperties {

    /**
     * Base URL of the Alfresco repository (e.g. {@code http://localhost:8080/alfresco}).
     */
    private String baseUrl = "http://localhost:8080/alfresco";

    /**
     * Service account username for repository API calls.
     */
    private String username = "admin";

    /**
     * Service account password for repository API calls.
     */
    private String password = "admin";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
