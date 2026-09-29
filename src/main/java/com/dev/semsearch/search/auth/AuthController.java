package com.dev.semsearch.search.auth;

import com.dev.semsearch.common.alfresco.AlfrescoProperties;
import com.dev.semsearch.common.alfresco.AlfrescoTicketClient;
import com.dev.semsearch.common.alfresco.AlfrescoUserClient;
import com.dev.semsearch.common.alfresco.AlfrescoUserClient.AlfrescoPerson;
import com.dev.semsearch.common.alfresco.UserAlreadyExistsException;
import com.dev.semsearch.search.auth.AuthDtos.LoginRequest;
import com.dev.semsearch.search.auth.AuthDtos.LoginResponse;
import com.dev.semsearch.search.auth.AuthDtos.SignupRequest;
import com.dev.semsearch.search.auth.AuthDtos.UserSummary;
import com.dev.semsearch.search.authority.AuthorityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * REST controller for authentication endpoints: login, signup, and current user profile.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{3,30}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");

    private final AlfrescoTicketClient ticketClient;
    private final AlfrescoUserClient userClient;
    private final AuthorityService authorityService;
    private final JwtService jwtService;
    private final boolean signupEnabled;

    public AuthController(
            AlfrescoTicketClient ticketClient,
            AlfrescoUserClient userClient,
            AuthorityService authorityService,
            JwtService jwtService,
            @Value("${security.signup.enabled:true}") boolean signupEnabled
    ) {
        this.ticketClient = ticketClient;
        this.userClient = userClient;
        this.authorityService = authorityService;
        this.jwtService = jwtService;
        this.signupEnabled = signupEnabled;
    }

    /**
     * Authenticates a user against Alfresco repository and issues a JWT token.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required"));
        }

        String username = request.username().trim();
        Optional<String> ticketOpt = ticketClient.createTicket(username, request.password());

        if (ticketOpt.isEmpty()) {
            log.warn("Failed login attempt for user: {}", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        boolean isAdmin = userClient.isAdmin(username);
        List<String> roles = isAdmin ? List.of("ROLE_USER", "ROLE_ADMIN") : List.of("ROLE_USER");
        List<String> authorities = authorityService.getAuthoritiesForUser(username);

        String token = jwtService.generateToken(username, roles, authorities);

        Optional<AlfrescoPerson> personOpt = userClient.getUser(username);
        UserSummary userSummary = new UserSummary(
                username,
                personOpt.map(AlfrescoPerson::firstName).orElse(username),
                personOpt.map(AlfrescoPerson::lastName).orElse(""),
                personOpt.map(AlfrescoPerson::email).orElse(""),
                roles,
                isAdmin
        );

        log.info("User logged in successfully: {} (admin={})", username, isAdmin);
        return ResponseEntity.ok(new LoginResponse(token, userSummary));
    }

    /**
     * Registers a new user account in Alfresco and returns a JWT token for immediate access.
     */
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
        if (!signupEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User registration is currently disabled"));
        }

        if (request == null || request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required"));
        }

        String username = request.username().trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Username must be 3-30 characters long and contain only letters, numbers, underscores, dashes, or dots"));
        }

        if (request.password().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 6 characters long"));
        }

        String email = request.email() != null ? request.email().trim() : "";
        if (email.isBlank() || !EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A valid email address is required"));
        }

        try {
            AlfrescoPerson person = userClient.createUser(
                    username,
                    request.password(),
                    request.firstName(),
                    request.lastName(),
                    email
            );

            // Generate initial JWT token for the newly created user
            List<String> roles = List.of("ROLE_USER");
            List<String> authorities = List.of(username, AuthorityService.GROUP_EVERYONE);
            String token = jwtService.generateToken(username, roles, authorities);

            UserSummary userSummary = new UserSummary(
                    person.id(),
                    person.firstName(),
                    person.lastName(),
                    person.email(),
                    roles,
                    false
            );

            log.info("User registered successfully: {}", username);
            return ResponseEntity.status(HttpStatus.CREATED).body(new LoginResponse(token, userSummary));

        } catch (UserAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Registration failed for user {}: {}", username, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Registration failed: " + e.getMessage()));
        }
    }

    /**
     * Returns details of the currently authenticated user based on the security context.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }

        String username = authentication.getName();
        boolean isAdmin = userClient.isAdmin(username);
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        Optional<AlfrescoPerson> personOpt = userClient.getUser(username);

        UserSummary userSummary = new UserSummary(
                username,
                personOpt.map(AlfrescoPerson::firstName).orElse(username),
                personOpt.map(AlfrescoPerson::lastName).orElse(""),
                personOpt.map(AlfrescoPerson::email).orElse(""),
                roles,
                isAdmin
        );

        return ResponseEntity.ok(userSummary);
    }
}
