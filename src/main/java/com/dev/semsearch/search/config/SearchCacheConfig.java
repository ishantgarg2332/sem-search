package com.dev.semsearch.search.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Cache configuration for search module.
 * Sets up a 5-minute TTL Caffeine cache for user authority resolution per DECISIONS.md.
 */
@Configuration
@EnableCaching
public class SearchCacheConfig {

    public static final String USER_AUTHORITIES_CACHE = "user-authorities";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(USER_AUTHORITIES_CACHE);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(10_000));
        return cacheManager;
    }
}
