package com.dev.semsearch.search.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Service for issuing and validating JSON Web Tokens (JWT) signed with HMAC-SHA256.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] keyBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret key must be at least 32 characters long for HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generates a signed JWT token containing user identity, roles, and authorities.
     */
    public String generateToken(String username, List<String> roles, List<String> authorities) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getExpirationHours(), ChronoUnit.HOURS);

        return Jwts.builder()
                .subject(username)
                .claim("roles", roles != null ? roles : List.of("ROLE_USER"))
                .claim("authorities", authorities != null ? authorities : List.of())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses and validates the token, returning claims payload if valid.
     */
    public Optional<Claims> validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extracts username (subject) from token.
     */
    public Optional<String> extractUsername(String token) {
        return validateToken(token).map(Claims::getSubject);
    }

    /**
     * Extracts roles from token.
     */
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        return validateToken(token)
                .map(claims -> {
                    Object roles = claims.get("roles");
                    if (roles instanceof List<?> list) {
                        return list.stream().map(Object::toString).toList();
                    }
                    return Collections.<String>emptyList();
                })
                .orElse(Collections.emptyList());
    }

    /**
     * Extracts authorities from token.
     */
    @SuppressWarnings("unchecked")
    public List<String> extractAuthorities(String token) {
        return validateToken(token)
                .map(claims -> {
                    Object auths = claims.get("authorities");
                    if (auths instanceof List<?> list) {
                        return list.stream().map(Object::toString).toList();
                    }
                    return Collections.<String>emptyList();
                })
                .orElse(Collections.emptyList());
    }
}
