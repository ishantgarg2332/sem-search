package com.dev.semsearch.search.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;
    private JwtProperties properties;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setSecret("test-secret-key-at-least-32-characters-long-hs256!");
        properties.setExpirationHours(24);
        jwtService = new JwtService(properties);
    }

    @Test
    void testGenerateAndValidateToken() {
        String token = jwtService.generateToken(
                "abc",
                List.of("ROLE_USER"),
                List.of("abc", "GROUP_EVERYONE")
        );

        assertThat(token).isNotBlank();

        Optional<Claims> claimsOpt = jwtService.validateToken(token);
        assertThat(claimsOpt).isPresent();
        assertThat(claimsOpt.get().getSubject()).isEqualTo("abc");

        assertThat(jwtService.extractUsername(token)).contains("abc");
        assertThat(jwtService.extractRoles(token)).containsExactly("ROLE_USER");
        assertThat(jwtService.extractAuthorities(token)).containsExactly("abc", "GROUP_EVERYONE");
    }

    @Test
    void testAdminTokenHasAdminRole() {
        String token = jwtService.generateToken(
                "admin",
                List.of("ROLE_USER", "ROLE_ADMIN"),
                List.of("admin", "GROUP_ALFRESCO_ADMINISTRATORS", "GROUP_EVERYONE")
        );

        assertThat(jwtService.extractRoles(token)).contains("ROLE_ADMIN", "ROLE_USER");
        assertThat(jwtService.extractAuthorities(token)).contains("GROUP_ALFRESCO_ADMINISTRATORS");
    }

    @Test
    void testTamperedTokenFailsValidation() {
        String token = jwtService.generateToken("abc", List.of("ROLE_USER"), List.of());
        String tampered = token.substring(0, token.length() - 5) + "abcde";

        assertThat(jwtService.validateToken(tampered)).isEmpty();
        assertThat(jwtService.extractUsername(tampered)).isEmpty();
    }

    @Test
    void testSecretKeyTooShortThrowsException() {
        JwtProperties badProps = new JwtProperties();
        badProps.setSecret("too-short");

        assertThatThrownBy(() -> new JwtService(badProps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 characters long");
    }
}
