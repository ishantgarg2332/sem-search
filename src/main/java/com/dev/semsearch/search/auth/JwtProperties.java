package com.dev.semsearch.search.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    /** Secret key for HMAC-SHA256 signing (at least 32 characters / 256 bits). */
    private String secret = "semsearch-super-secret-jwt-signing-key-for-hs256-production-ready!";

    /** Token expiration in hours. */
    private long expirationHours = 24;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationHours() {
        return expirationHours;
    }

    public void setExpirationHours(long expirationHours) {
        this.expirationHours = expirationHours;
    }
}
