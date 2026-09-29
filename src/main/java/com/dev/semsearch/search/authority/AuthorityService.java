package com.dev.semsearch.search.authority;

import com.dev.semsearch.search.config.SearchCacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service to resolve all security authorities for a user (username, group IDs, and GROUP_EVERYONE).
 * Results are cached in Caffeine for 5 minutes per {@code DECISIONS.md}.
 */
@Service
public class AuthorityService {

    private static final Logger log = LoggerFactory.getLogger(AuthorityService.class);
    public static final String GROUP_EVERYONE = "GROUP_EVERYONE";

    private final AlfrescoAuthorityClient authorityClient;

    public AuthorityService(AlfrescoAuthorityClient authorityClient) {
        this.authorityClient = authorityClient;
    }

    /**
     * Resolves all reader authority IDs for the given user, cached for 5 minutes.
     *
     * @param username the username of the authenticated user
     * @return list of authority strings that can be matched against the {@code readers} field
     */
    @Cacheable(value = SearchCacheConfig.USER_AUTHORITIES_CACHE, key = "#username")
    public List<String> getAuthoritiesForUser(String username) {
        log.debug("Resolving authorities from Alfresco for user: {}", username);
        Set<String> authorities = new HashSet<>();

        if (username != null && !username.isBlank()) {
            authorities.add(username);
            List<String> groups = authorityClient.getUserGroups(username);
            authorities.addAll(groups);
        }

        // GROUP_EVERYONE is always added to every user's authorities
        authorities.add(GROUP_EVERYONE);

        List<String> result = authorities.stream().sorted().toList();
        log.info("Resolved {} authorities for user {}: {}", result.size(), username, result);
        return result;
    }
}
