package com.dev.semsearch.search.config;

import com.dev.semsearch.search.auth.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import com.dev.semsearch.search.auth.JwtProperties;
import com.dev.semsearch.search.auth.JwtService;
import org.springframework.context.annotation.Import;

/**
 * Spring Security configuration for the search application.
 * Configures JWT Bearer authentication for the REST API and permits static
 * resources and client-side routes for the React SPA frontend.
 */
@Configuration
@EnableWebSecurity
@Import({JwtAuthFilter.class, JwtService.class, JwtProperties.class})
public class SearchSecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SearchSecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Health and info endpoints — public
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Authentication endpoints (login, signup) — public
                        .requestMatchers("/api/auth/login", "/api/auth/signup").permitAll()
                        // React SPA static resources and client-side routes — public.
                        // /documents, /admin, /workspaces return index.html; React handles routing.
                        .requestMatchers(
                                "/", "/index.html", "/favicon.svg", "/icons.svg",
                                "/*.js", "/*.css", "/*.map",
                                "/assets/**",
                                "/login", "/signup", "/documents", "/admin", "/workspaces"
                        ).permitAll()
                        // Admin endpoints — require ADMIN role
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Protected APIs — require authentication
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/search/**").authenticated()
                        .requestMatchers("/api/documents/**").authenticated()
                        .requestMatchers("/api/workspaces/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

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
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
