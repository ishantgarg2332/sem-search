package com.dev.semsearch.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security configuration for the search application.
 * Configures HTTP Basic authentication for the REST API and permits static
 * resources for the React SPA frontend.
 */
@Configuration
@EnableWebSecurity
public class SearchSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Health and info endpoints — public
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // React SPA static resources and client-side routes — public.
                        // /documents and /admin only return index.html; the React app then
                        // shows its own login form. Without this, refreshing those pages got a
                        // 401 and the browser's Basic-auth popup. The data behind them is still
                        // protected by the /api/** rules below.
                        .requestMatchers(
                                "/", "/index.html", "/favicon.svg", "/icons.svg",
                                "/*.js", "/*.css", "/*.map",
                                "/assets/**",
                                "/documents", "/admin"
                        ).permitAll()
                        // Admin endpoints — require ADMIN role
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Search and document management — any authenticated user
                        .requestMatchers("/api/search/**").authenticated()
                        .requestMatchers("/api/documents/**").authenticated()
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    /**
     * CORS configuration for development (Vite dev server on port 5173)
     * and production (same-origin, no CORS needed).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:5173",  // Vite dev server
                "http://localhost:8085"   // Spring Boot direct access
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails admin = User.builder()
                .username("admin")
                .password(passwordEncoder.encode("admin"))
                .roles("USER", "ADMIN")
                .build();

        UserDetails alice = User.builder()
                .username("alice")
                .password(passwordEncoder.encode("alice"))
                .roles("USER")
                .build();

        UserDetails bob = User.builder()
                .username("bob")
                .password(passwordEncoder.encode("bob"))
                .roles("USER")
                .build();

        return new InMemoryUserDetailsManager(admin, alice, bob);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

